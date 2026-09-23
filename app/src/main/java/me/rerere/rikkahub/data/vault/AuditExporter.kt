package me.rerere.rikkahub.data.vault

import me.rerere.rikkahub.data.db.entity.VaultAuditLogEntity

/**
 * 审计记录导出序列化（JSON / CSV）。
 *
 * 只含名称 / 用途 / 时间，无任何秘密值，可放心留档。
 * 独立于 [VaultExporter]：那边是凭据密文导出（对象函数数已近 detekt 上限），职责分开。
 */
object AuditExporter {

    /** 审计记录导出为 JSON。 */
    fun toJson(logs: List<VaultAuditLogEntity>): String {
        val sb = StringBuilder("[")
        logs.forEachIndexed { i, l ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":").append(l.id)
                .append(",\"credentialName\":\"").append(jsonEscape(l.credentialName)).append('"')
                .append(",\"caller\":\"").append(jsonEscape(l.caller)).append('"')
                .append(",\"action\":\"").append(jsonEscape(l.action)).append('"')
                .append(",\"tsMs\":").append(l.tsMs).append('}')
        }
        return sb.append(']').toString()
    }

    /** 审计记录导出为 CSV（首行表头）。 */
    fun toCsv(logs: List<VaultAuditLogEntity>): String {
        val sb = StringBuilder("id,credentialName,caller,action,tsMs\n")
        logs.forEach { l ->
            sb.append(l.id).append(',').append(csvEscape(l.credentialName)).append(',')
                .append(csvEscape(l.caller)).append(',').append(csvEscape(l.action)).append(',').append(l.tsMs).append('\n')
        }
        return sb.toString()
    }

    private fun jsonEscape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun csvEscape(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n')) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
