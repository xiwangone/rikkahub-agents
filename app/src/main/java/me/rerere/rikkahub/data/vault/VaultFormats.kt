package me.rerere.rikkahub.data.vault

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 凭证库标准导入导出格式（与 .vault 加密格式并行）。
 *
 * - CSV：最通用明文格式，迁移到任何管理器
 * - Bitwarden JSON：主流管理器兼容格式
 *
 * 明文格式的值支持环境变量插值：`${VAR}` 或 `$VAR` 形式，
 * 导出时用 [interpolateEnv] 把 ${VAR} 替换为实际环境变量值。
 */
object VaultFormats {

    const val FORMAT_VAULT = "vault"      // 加密 .vault（默认）
    const val FORMAT_CSV = "csv"          // 明文 CSV
    const val FORMAT_BITWARDEN = "bitwarden" // 明文 Bitwarden JSON

    // ================= 环境变量插值 =================

    /**
     * 将值中的 ${VAR} / $VAR 替换为环境变量值。
     * 未定义的环境变量保留原样（不报错），以便导入后手动补。
     */
    fun interpolateEnv(value: String): String {
        // ${VAR} 形式
        var result = Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}").replace(value) { m ->
            System.getenv(m.groupValues[1]) ?: m.value
        }
        // $VAR 形式（非 ${} 的）
        result = Regex("\\$([A-Za-z_][A-Za-z0-9_]*)").replace(result) { m ->
            // 跳过已被 ${} 处理的部分：$ 后紧跟 { 的不处理
            if (m.value.startsWith("${")) m.value
            else System.getenv(m.groupValues[1]) ?: m.value
        }
        return result
    }

    // ================= CSV =================

    /** 导出 CSV：name,value,description,group（值做 CSV 转义）。 */
    fun toCsv(entries: List<VaultExporter.Quad>): String {
        val sb = StringBuilder()
        sb.append("name,value,description,group\n")
        entries.forEach { e ->
            sb.append(csvEscape(e.name)).append(',')
                .append(csvEscape(interpolateEnv(e.plaintext))).append(',')
                .append(csvEscape(e.description)).append(',')
                .append(csvEscape(e.group)).append('\n')
        }
        return sb.toString()
    }

    /** 解析 CSV（兼容简单引号转义）。返回 Quad 列表。 */
    fun fromCsv(content: String): List<VaultExporter.Quad> {
        val lines = content.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return emptyList()
        // 跳过表头（若有 name/value/description/group 之一）
        val start = if (lines[0].contains("name") || lines[0].startsWith("name,")) 1 else 0
        return lines.drop(start).mapNotNull { line ->
            val cols = csvSplit(line)
            if (cols.size >= 2) {
                VaultExporter.Quad(
                    name = cols[0].trim(),
                    plaintext = cols.getOrElse(1) { "" }.trim(),
                    description = cols.getOrElse(2) { "" }.trim(),
                    group = cols.getOrElse(3) { "" }.trim(),
                )
            } else null
        }
    }

    private fun csvEscape(s: String): String {
        if (s.contains(',') || s.contains('"') || s.contains('\n')) {
            return "\"" + s.replace("\"", "\"\"") + "\""
        }
        return s
    }

    private fun csvSplit(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { result.add(sb.toString()); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        result.add(sb.toString())
        return result
    }

    // ================= Bitwarden JSON =================

    @Serializable
    data class BitwardenItem(
        val id: String? = null,
        val organizationId: String? = null,
        val folderId: String? = null,
        val type: Int = 1,
        val reprompt: Int = 0,
        val name: String,
        val notes: String? = null,
        val favorite: Boolean = false,
        val login: BitwardenLogin = BitwardenLogin(),
        val collectionIds: List<String>? = null,
    )

    @Serializable
    data class BitwardenLogin(
        val uris: List<BitwardenUri>? = null,
        val username: String? = null,
        val password: String? = null,
        val totp: String? = null,
    )

    @Serializable
    data class BitwardenUri(val match: Int? = null, val uri: String? = null)

    @Serializable
    data class BitwardenFolder(val id: String, val name: String)

    @Serializable
    data class BitwardenExport(
        val encrypted: Boolean = false,
        val folders: List<BitwardenFolder> = emptyList(),
        val items: List<BitwardenItem> = emptyList(),
    )

    /** 导出 Bitwarden JSON（folder 映射 group）。 */
    fun toBitwarden(entries: List<VaultExporter.Quad>): String {
        val groups = entries.map { it.group }.distinct().filter { it.isNotBlank() }
        val folders = groups.map { BitwardenFolder(id = it, name = it) }
        val folderIdByName = folders.associate { it.name to it.id }
        val items = entries.map { e ->
            BitwardenItem(
                name = e.name,
                notes = e.description.ifBlank { null },
                login = BitwardenLogin(
                    username = e.name,
                    password = interpolateEnv(e.plaintext),
                ),
                folderId = folderIdByName[e.group],
            )
        }
        return Json { prettyPrint = true }.encodeToString(BitwardenExport(folders = folders, items = items))
    }

    /** 解析 Bitwarden JSON（兼容 encrypted=false 明文导出）。 */
    fun fromBitwarden(content: String): List<VaultExporter.Quad> {
        val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(content).jsonObject
        val folderIdToName = (json["folders"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content to it.jsonObject["name"]?.jsonPrimitive?.content }
            ?.associate { (id, name) -> id to name }
            ?: emptyMap()
        val items = (json["items"] as? kotlinx.serialization.json.JsonArray) ?: return emptyList()
        return items.mapNotNull { item ->
            val obj = item.jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val login = obj["login"]?.jsonObject
            val password = login?.get("password")?.jsonPrimitive?.content ?: ""
            val notes = obj["notes"]?.jsonPrimitive?.content ?: ""
            val folderId = obj["folderId"]?.jsonPrimitive?.content
            val group = folderId?.let { folderIdToName[it] } ?: ""
            VaultExporter.Quad(name, password, notes, group)
        }
    }
}
