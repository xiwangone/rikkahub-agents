package me.rerere.rikkahub.data.ai.catalog

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import me.rerere.rikkahub.data.log.AppLog
import java.io.File

/**
 * 模型能力外置表（双轨）。
 *
 * - **基线表**：`assets/model-capabilities.json` —— 随发版更新；
 * - **本机表**：App 私有目录 `files/model-capabilities.json` —— 用户 / AI 导入，**优先于基线**。
 *
 * 语义是**覆盖**（不是并集）：命中即直接采用该条给出的能力，不再与内置登记表合并 ——
 * 因为并集只能"补漏标"，要**纠正错标**必须能覆盖。未命中的字段仍走"内置表 ∪ 目录"。
 *
 * 失败策略：**整表解析失败 → 忽略该表并记日志**（绝不静默生效）；单条非法 → 跳过该条、保留其余。
 * 删除文件即回到旧行为（不写任何持久状态）。
 */
class ModelCapabilityOverrides(
    private val context: Context,
) {
    /** 表里的一条：`match` 为大小写不敏感的子串匹配；其余字段为 null 表示"该项不覆盖"。 */
    data class Entry(
        val match: String,
        val input: Set<Modality>? = null,
        val output: Set<Modality>? = null,
        val abilities: Set<ModelAbility>? = null,
        val contextLength: Int? = null,
    )

    @Volatile
    private var overrideEntries: List<Entry> = emptyList()

    /** 载入两张表（本机表在前 → 同 modelId 命中时本机优先；表内先声明者优先）。 */
    fun load() {
        val local = readLocal()?.let { parseOverrideTable(it, "local") }.orEmpty()
        val baseline = readBaseline()?.let { parseOverrideTable(it, "baseline") }.orEmpty()
        overrideEntries = local + baseline
        AppLog.i(TAG, "overrides loaded: local=${local.size} baseline=${baseline.size}")
    }

    fun inputModalities(modelId: String): Set<Modality>? = lookup(modelId)?.input

    fun outputModalities(modelId: String): Set<Modality>? = lookup(modelId)?.output

    fun abilities(modelId: String): Set<ModelAbility>? = lookup(modelId)?.abilities

    fun contextLength(modelId: String): Int? = lookup(modelId)?.contextLength

    /** 当前条目数（供 UI 展示“外置表：N 条”）。 */
    fun size(): Int = overrideEntries.size

    private fun lookup(modelId: String): Entry? {
        if (overrideEntries.isEmpty()) return null
        val id = modelId.lowercase()
        return overrideEntries.firstOrNull { it.match.isNotEmpty() && id.contains(it.match.lowercase()) }
    }

    private fun readLocal(): String? =
        runCatching {
            val f = File(context.filesDir, FILE_NAME)
            if (f.exists()) f.readText() else null
        }.onFailure { AppLog.w(TAG, "read local overrides failed: ${it.message}") }.getOrNull()

    private fun readBaseline(): String? =
        runCatching {
            context.assets.open(FILE_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.onFailure { AppLog.w(TAG, "read baseline overrides failed: ${it.message}") }.getOrNull()

    /** 解析一张表；整表非法（非 JSON / 结构不对）返回 null。单条非法只跳过该条。 */
    private fun parseOverrideTable(text: String, source: String): List<Entry>? =
        parseEntries(text)
            ?.also { AppLog.i(TAG, "$source parsed: ${it.size} entries") }
            ?: run {
                AppLog.w(TAG, "parse $source overrides failed, ignored")
                null
            }

    companion object {
        private const val TAG = "ModelOverrides"
        private const val FILE_NAME = "model-capabilities.json"

        private val json = Json { ignoreUnknownKeys = true }

        /** 纯解析（无 IO）：解析失败返回 null（调用方据此整表忽略）。供 [load] 与单测共用。 */
        internal fun parseEntries(text: String): List<Entry>? =
            runCatching {
                val root = json.parseToJsonElement(text) as? JsonObject ?: error("root is not an object")
                val models = root["models"] as? JsonArray ?: error("no 'models' array")
                models.mapNotNull { (it as? JsonObject)?.toEntry() }
            }.getOrNull()

        private fun JsonObject.stringList(key: String): List<String>? =
            (this[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull?.lowercase() }

        private fun JsonObject.toModalities(key: String): Set<Modality>? =
            stringList(key)?.mapNotNull { name ->
                Modality.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            }?.toSet()

        private fun JsonObject.toAbilities(key: String): Set<ModelAbility>? =
            stringList(key)?.mapNotNull { name ->
                ModelAbility.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            }?.toSet()

        /** 单条解析：`match` 缺失即视为非法（跳过）。 */
        internal fun JsonObject.toEntry(): Entry? {
            val match = (this["match"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return null
            return Entry(
                match = match,
                input = toModalities("input"),
                output = toModalities("output"),
                abilities = toAbilities("abilities"),
                contextLength = (this["contextLength"] as? JsonPrimitive)?.intOrNull,
            )
        }
    }
}
