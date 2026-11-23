package me.rerere.rikkahub.data.ai.catalog

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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

    @Volatile
    private var localParsed: List<Entry> = emptyList()

    @Volatile
    private var baselineParsed: List<Entry> = emptyList()

    @Volatile
    private var loadedLocalCount: Int = 0

    @Volatile
    private var loadedBaselineCount: Int = 0

    /** 载入两张表（本机表在前 → 同长命中平局时本机优先；查询取最长命中，表内先声明者优先）。 */
    fun load() {
        val local = readLocal()?.let { parseOverrideTable(it, "local") }.orEmpty()
        val baseline = readBaseline()?.let { parseOverrideTable(it, "baseline") }.orEmpty()
        localParsed = local
        baselineParsed = baseline
        overrideEntries = local + baseline
        loadedLocalCount = local.size
        loadedBaselineCount = baseline.size
        AppLog.i(TAG, "overrides loaded: local=${local.size} baseline=${baseline.size}")
    }

    /** 本机表当前条目（快照；编辑器据此展示与修改）。 */
    fun localEntries(): List<Entry> = localParsed

    /** 基线表当前条目（快照；编辑器据此标注来源）。 */
    fun baselineEntries(): List<Entry> = baselineParsed

    /** 本机表条目数（供 UI 展示）。 */
    fun localEntryCount(): Int = loadedLocalCount

    /** 基线表条目数（供 UI 展示）。 */
    fun baselineEntryCount(): Int = loadedBaselineCount

    /**
     * 导入本机表：**先校验可解析**（非法则整体拒绝、不落盘），再写入 App 私有目录并重载。
     *
     * `models` 里有条目、却一条都没解析出来（如 `{"models":[{},{}]}`）也拒绝：
     * 否则会把本机表写成一张空表，用户看到的是「导入成功」而覆盖全丢。
     * `{"models":[]}` 是明确的清空意图，接受（等价于 [clearLocal]）。
     *
     * @return 是否导入成功；失败时调用方应提示用户检查 JSON 格式。
     */
    fun importLocal(text: String): Boolean {
        val entries = parseOverrideTable(text, "import") ?: return false
        val slots = modelSlotCount(text) ?: 0
        if (entries.isEmpty() && slots > 0) {
            AppLog.w(TAG, "import local overrides rejected: 0 valid entries in $slots slots")
            return false
        }
        return runCatching {
            writeLocalFileAtomic(text)
            load()
            true
        }.onFailure { AppLog.w(TAG, "import local overrides failed: ${it.message}", it) }.getOrDefault(false)
    }

    /** 导出当前**基线表**原文（供用户在此基础上编辑后导入）。 */
    fun exportBaselineText(): String? = readBaseline()

    /** 清除本机表（回到基线行为）。 */
    fun clearLocal(): Boolean =
        runCatching {
            val f = File(context.filesDir, FILE_NAME)
            val deleted = !f.exists() || f.delete()
            load()
            deleted
        }.onFailure { AppLog.w(TAG, "clear local overrides failed: ${it.message}", it) }.getOrDefault(false)

    /**
     * 写入/更新本机表里的一条：`match` 相同（去空白、忽略大小写）即替换，
     * 否则追加。写盘前先从磁盘重载，避免覆盖启动后被外部改过的内容。
     * 空 `match` 拒绝写入。
     */
    fun upsertLocal(entry: Entry): Boolean {
        val normalized = entry.copy(match = entry.match.trim())
        if (normalized.match.isEmpty()) return false
        load()
        return runCatching {
            val updated = upsertInList(localParsed, normalized)
            writeLocalFileAtomic(serializeEntries(updated))
            load()
            true
        }.onFailure { AppLog.w(TAG, "upsert local override failed: ${it.message}", it) }.getOrDefault(false)
    }

    /**
     * 删除本机表里的一条（`match` 去空白、忽略大小写）。无匹配条目时返回 false，
     * 不动文件。
     */
    fun removeLocal(match: String): Boolean {
        load()
        val updated = removeFromList(localParsed, match)
        if (updated.size == localParsed.size) return false
        return runCatching {
            writeLocalFileAtomic(serializeEntries(updated))
            load()
            true
        }.onFailure { AppLog.w(TAG, "remove local override failed: ${it.message}", it) }.getOrDefault(false)
    }

    fun inputModalities(modelId: String): Set<Modality>? = lookup(modelId)?.input

    fun outputModalities(modelId: String): Set<Modality>? = lookup(modelId)?.output

    fun abilities(modelId: String): Set<ModelAbility>? = lookup(modelId)?.abilities

    fun contextLength(modelId: String): Int? = lookup(modelId)?.contextLength

    /** 当前条目数（供 UI 展示“外置表：N 条”）。 */
    fun size(): Int = overrideEntries.size

    /**
     * 命中条目里取 `match` **最长**者（更具体的条目优先）；平局取先声明者
     * （本机表整体在基线表前，故本机优先）。与 [ModelCapabilityResolver] 的
     * 查询口径一致。
     *
     * 旧实现是 firstOrNull（首条命中即返回）：`deepseek` 会抢在更具体的
     * `deepseek-flash` 之前生效，只因其声明在表内靠前。改为最长匹配后，
     * 短前缀条目只兜底未被更具体条目覆盖的模型。
     */
    private fun lookup(modelId: String): Entry? =
        longestMatch(overrideEntries, modelId)

    /**
     * 原子写本机表：先写临时文件再 rename 替换，进程中途被杀也不会留下半截 JSON
     * （半截表会导致下次启动整表解析失败被忽略，用户覆盖静默丢失）。
     */
    private fun writeLocalFileAtomic(content: String) {
        val target = File(context.filesDir, FILE_NAME)
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(content)
        if (tmp.renameTo(target)) return
        // rename 失败（个别文件系统目标已存在等）：退化为先删目标再改名
        if (target.exists() && !target.delete()) {
            tmp.delete()
            error("atomic replace failed: cannot delete $FILE_NAME")
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            error("atomic replace failed: rename $FILE_NAME.tmp -> $FILE_NAME")
        }
    }

    private fun readLocal(): String? =
        runCatching {
            val f = File(context.filesDir, FILE_NAME)
            if (f.exists()) f.readText() else null
        }.onFailure { AppLog.w(TAG, "read local overrides failed: ${it.message}", it) }.getOrNull()

    private fun readBaseline(): String? =
        runCatching {
            context.assets.open(FILE_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.onFailure { AppLog.w(TAG, "read baseline overrides failed: ${it.message}", it) }.getOrNull()

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

        /**
         * 纯匹配（无 IO）：给定条目里命中 `modelId` 且 `match` **最长**的那条；
         * 等长时取先声明者。见 [lookup] 的注释。
         */
        internal fun longestMatch(entries: List<Entry>, modelId: String): Entry? {
            if (entries.isEmpty()) return null
            val id = modelId.lowercase()
            var best: Entry? = null
            for (entry in entries) {
                val match = entry.match
                if (match.isEmpty() || !id.contains(match.lowercase())) continue
                if (match.length > (best?.match?.length ?: -1)) best = entry
            }
            return best
        }

        /** 纯解析（无 IO）：`models` 数组的元素个数；整表非法返回 null。 */
        internal fun modelSlotCount(text: String): Int? {
            val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                ?: return null
            return (root["models"] as? JsonArray)?.size
        }

        /** 纯序列化（无 IO）：与 [parseEntries] 互逆；空字段不写入（即该项不覆盖）。 */
        internal fun serializeEntries(entries: List<Entry>): String {
            val root = buildJsonObject {
                put("version", 1)
                putJsonArray("models") {
                    entries.forEach { add(entryToJsonObject(it)) }
                }
            }
            return json.encodeToString(JsonObject.serializer(), root)
        }

        /** 纯列表操作：`match` 相同（去空白、忽略大小写）即替换，否则追加。 */
        internal fun upsertInList(entries: List<Entry>, entry: Entry): List<Entry> {
            val match = entry.match.trim()
            val normalized = entry.copy(match = match)
            val idx = entries.indexOfFirst { it.match.trim().equals(match, ignoreCase = true) }
            return if (idx >= 0) {
                entries.toMutableList().also { it[idx] = normalized }
            } else {
                entries + normalized
            }
        }

        /** 纯列表操作：删除 `match` 相同（去空白、忽略大小写）的条目。 */
        internal fun removeFromList(entries: List<Entry>, match: String): List<Entry> {
            val target = match.trim()
            return entries.filterNot { it.match.trim().equals(target, ignoreCase = true) }
        }

        /** 单条解析：`match` 缺失即视为非法（跳过）。 */
        internal fun JsonObject.toEntry(): Entry? = parseEntry(this)
    }
}

// ── 条目 <-> JSON 的纯转换：放在文件级，companion 只留业务入口（避免函数数越过 detekt 阈值）──

private fun entryToJsonObject(entry: ModelCapabilityOverrides.Entry): JsonObject = buildJsonObject {
    put("match", entry.match)
    entry.input?.let { modalities ->
        putJsonArray("input") { modalities.forEach { add(it.name.lowercase()) } }
    }
    entry.output?.let { modalities ->
        putJsonArray("output") { modalities.forEach { add(it.name.lowercase()) } }
    }
    entry.abilities?.let { abilities ->
        putJsonArray("abilities") { abilities.forEach { add(it.name.lowercase()) } }
    }
    entry.contextLength?.let { put("contextLength", it) }
}

private fun jsonStringList(obj: JsonObject, key: String): List<String>? =
    (obj[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull?.lowercase() }

private fun jsonToModalities(obj: JsonObject, key: String): Set<Modality>? =
    jsonStringList(obj, key)?.mapNotNull { name ->
        Modality.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }?.toSet()

private fun jsonToAbilities(obj: JsonObject, key: String): Set<ModelAbility>? =
    jsonStringList(obj, key)?.mapNotNull { name ->
        ModelAbility.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }?.toSet()

/** 单条解析：`match` 缺失即视为非法（跳过）。 */
internal fun parseEntry(obj: JsonObject): ModelCapabilityOverrides.Entry? {
    val match = (obj["match"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    return ModelCapabilityOverrides.Entry(
        match = match,
        input = jsonToModalities(obj, "input"),
        output = jsonToModalities(obj, "output"),
        abilities = jsonToAbilities(obj, "abilities"),
        contextLength = (obj["contextLength"] as? JsonPrimitive)?.intOrNull,
    )
}
