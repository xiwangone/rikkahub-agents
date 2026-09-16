package me.rerere.rikkahub.data.ai.catalog

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.registry.ModelCatalogBridge
import me.rerere.rikkahub.data.log.AppLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * 模型能力目录（联网可更新）。
 *
 * 背景：内置登记表（[me.rerere.ai.registry.ModelRegistry]）人工核实、离线可用，但**新模型
 * 必须等发版**。公开模型目录（OpenRouter 的 `/api/v1/models`）是目前唯一稳定提供
 * 「输入模态 + 支持的参数」元数据的公开来源，因此用它作为可更新的补充来源。
 *
 * 设计要点：
 * - **只留最新热门**：按厂商白名单收敛，每个厂商只保留最近创建的若干个，并剔除
 *   embedding / rerank / 语音 / 绘图 / 审核等非聊天模型与各类变体后缀（`:free`、`:batch`…），
 *   目录规模因此有上限，不会越拉越多。
 * - **内置优先**：内置登记表命中的模型不走目录（人工核实结果不被覆盖）；只有未登记的模型才查目录。
 * - **离线可用**：拉取失败时沿用上次缓存；无缓存时行为与从前一致。
 */
class ModelCatalogRepository(
    private val context: Context,
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val cacheFile: File get() = File(context.filesDir, "model-catalog.json")

    /** 内存索引：归一化名称 → 能力条目（同时登记「全名」与「末段名」两种键）。 */
    @Volatile
    private var index: Map<String, Entry> = emptyMap()

    @Volatile
    var lastUpdatedMs: Long = 0L
        private set

    data class Entry(
        val name: String,
        val inputImage: Boolean,
        val tool: Boolean,
        val reasoning: Boolean,
        val contextLength: Int?,
    )

    data class Status(
        val entryCount: Int,
        val updatedAtMs: Long,
    )

    init {
        // 启动即加载上次缓存，保证离线也有目录可用
        runCatching { loadFromCache() }
            .onFailure { AppLog.w(TAG, "load cached catalog failed: ${it.message}") }
    }

    fun status(): Status = Status(index.size, lastUpdatedMs)

    /** 拉取并覆盖本地目录。返回本次保留的条目数。 */
    suspend fun refresh(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(CATALOG_URL).get().build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("catalog responded ${resp.code}")
                val body = resp.body.string()
                val data = (json.parseToJsonElement(body).jsonObject["data"] as? JsonArray)
                    ?: error("catalog response has no data array")
                val entries = buildEntries(data)
                index = entries
                lastUpdatedMs = System.currentTimeMillis()
                saveToCache(body, entries.size)
                AppLog.i(TAG, "refresh: kept ${entries.size} entries (from ${data.size} models)")
                entries.size
            }
        }.onFailure { AppLog.w(TAG, "refresh failed: ${it.message}") }
    }

    // ---------------- 能力查询（供桥调用） ----------------

    fun inputModalities(modelId: String): Set<Modality>? {
        val e = lookup(modelId) ?: return null
        return if (e.inputImage) setOf(Modality.TEXT, Modality.IMAGE) else setOf(Modality.TEXT)
    }

    fun outputModalities(modelId: String): Set<Modality>? = lookup(modelId)?.let { setOf(Modality.TEXT) }

    fun abilities(modelId: String): Set<ModelAbility>? {
        val e = lookup(modelId) ?: return null
        return buildSet {
            if (e.tool) add(ModelAbility.TOOL)
            if (e.reasoning) add(ModelAbility.REASONING)
        }
    }

    private fun lookup(modelId: String): Entry? = index[normalize(modelId)]

    // ---------------- 过滤与解析 ----------------

    private fun buildEntries(data: JsonArray): Map<String, Entry> {
        val byVendor = mutableMapOf<String, MutableList<Pair<Long, Entry>>>()
        for (item in data) {
            val obj = item.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
            if (id.startsWith("~")) continue
            val slash = id.indexOf('/')
            val vendor = (if (slash > 0) id.substring(0, slash) else "").lowercase()
            if (vendor !in VENDOR_ALLOWLIST) continue
            val name = if (slash > 0) id.substring(slash + 1) else id
            if (name.any { it.isUpperCase() } && name.contains(":")) continue
            if (EXCLUDED_SUFFIXES.any { name.lowercase().contains(it) }) continue
            if (EXCLUDED_KEYWORDS.any { name.lowercase().contains(it) }) continue

            val arch = obj["architecture"]?.jsonObject
            val inputs = (arch?.get("input_modalities") as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet() ?: emptySet()
            val params = (obj["supported_parameters"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet() ?: emptySet()
            val created = obj["created"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            val ctx = obj["context_length"]?.jsonPrimitive?.intOrNull

            val entry = Entry(
                name = name,
                inputImage = "image" in inputs,
                tool = "tools" in params,
                reasoning = "reasoning" in params || "include_reasoning" in params,
                contextLength = ctx,
            )
            byVendor.getOrPut(vendor) { mutableListOf() }.add(created to entry)
        }

        val result = mutableMapOf<String, Entry>()
        for ((_, list) in byVendor) {
            // 只保留该厂商最近创建的若干个（"最新热门"）
            list.sortedByDescending { it.first }.take(MAX_PER_VENDOR).forEach { (_, e) ->
                result[normalize(e.name)] = e
                // 也登记「厂商/名称」全名，便于用完整 id 查询
                result.putIfAbsent(normalize("${e.name}"), e)
            }
        }
        return result
    }

    /** 归一化：小写并去掉非字母数字，使 `deepseek-v4.1-flash` 与 `deepseek/deepseek-v4.1-flash` 等价。 */
    private fun normalize(raw: String): String {
        val noVendor = raw.substringAfterLast('/')
        return noVendor.lowercase().filter { it.isLetterOrDigit() }
    }

    // ---------------- 缓存 ----------------

    private fun saveToCache(rawBody: String, kept: Int) {
        runCatching {
            cacheFile.writeText(rawBody)
            AppLog.i(TAG, "catalog cached: ${cacheFile.length() / 1024}KB, kept=$kept")
        }.onFailure { AppLog.w(TAG, "cache write failed: ${it.message}") }
    }

    private fun loadFromCache() {
        if (!cacheFile.exists()) return
        val body = cacheFile.readText()
        val data = (json.parseToJsonElement(body).jsonObject["data"] as? JsonArray) ?: return
        index = buildEntries(data)
        lastUpdatedMs = cacheFile.lastModified()
        AppLog.i(TAG, "catalog loaded from cache: ${index.size} entries")
    }

    companion object {
        private const val TAG = "ModelCatalog"
        private const val CATALOG_URL = "https://openrouter.ai/api/v1/models"

        /** 每个厂商保留的模型数上限。 */
        private const val MAX_PER_VENDOR = 8

        /** 只保留主流大厂（含国内大厂），避免目录无限膨胀。 */
        private val VENDOR_ALLOWLIST = setOf(
            "openai", "anthropic", "google", "x-ai", "meta-llama", "mistralai", "amazon", "nvidia", "microsoft", "cohere",
            "deepseek", "qwen", "z-ai", "moonshotai", "minimax", "bytedance", "volcengine", "tencent", "baidu",
            "stepfun", "inclusionai", "01-ai", "internlm", "baichuan", "sensetime", "meituan", "xiaomi", "zhipuai",
        )

        /** 变体后缀（免费/批处理/在线等），不单独登记。 */
        private val EXCLUDED_SUFFIXES = listOf(":free", ":batch", ":extended", ":online", ":thinking", ":nitro", "-preview:")

        /** 非聊天类模型关键词。 */
        private val EXCLUDED_KEYWORDS = listOf(
            "embed", "rerank", "whisper", "tts", "audio", "lyria", "veo", "image-gen", "dall-e", "moderation",
            "guard", "reranker", "ocr", "davinci", "babbage", "ada-", "curie",
        )
    }
}

/** 把目录实现安装给注册表桥（app 启动时调用一次）。 */
fun ModelCatalogRepository.installBridge() {
    ModelCatalogBridge.install(
        object : ModelCatalogBridge.Provider {
            override fun inputModalities(modelId: String): Set<Modality>? = this@installBridge.inputModalities(modelId)

            override fun outputModalities(modelId: String): Set<Modality>? = this@installBridge.outputModalities(modelId)

            override fun abilities(modelId: String): Set<ModelAbility>? = this@installBridge.abilities(modelId)
        },
    )
}
