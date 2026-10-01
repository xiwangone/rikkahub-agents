package me.rerere.rikkahub.data.ai

import android.content.Context
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import me.rerere.rikkahub.data.vault.SecretMasker
import org.koin.java.KoinJavaComponent.getKoin
import java.io.File

/**
 * 工具输出后处理：超长截断（全文落盘、预览回上下文）与敏感信息掩码。
 *
 * 落盘目录经 context 注入；掩码用的 vault 保持经 Koin 获取。
 */
private const val TAG = "GenerationLoop"

internal class ToolOutputProcessor(
    private val context: Context,
) {

    fun maybeTruncateToolOutput(
        toolCallId: String,
        output: List<UIMessagePart>,
        hasShellAccess: Boolean,
        maxChars: Int,
        digestKeywords: List<String>? = null,
    ): List<UIMessagePart> {
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val nonTextParts = output.filter { it !is UIMessagePart.Text }
        val totalChars = textParts.sumOf { it.text.length }

        if (totalChars <= maxChars || !hasShellAccess) return output

        AppLog.i(TAG, "maybeTruncateToolOutput: truncating tool $toolCallId output ($totalChars chars, digest=${digestKeywords != null})")

        val fullText = textParts.joinToString("\n") { it.text }
        val digestMode = digestKeywords != null
        val preview =
            fullText.take(
                if (digestMode) TOOL_OUTPUT_DIGEST_PREVIEW_CHARS else TOOL_OUTPUT_PREVIEW_CHARS,
            )

        val fileName = "${toolCallId}.txt"
        val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        File(outputDir, fileName).writeText(fullText)

        return listOf(
            UIMessagePart.Text(
                buildString {
                    appendLine("[Tool output truncated: $totalChars characters total]")
                    appendLine("Full output saved to: /tool_outputs/$fileName")
                    appendLine("Use shell to read: `cat /tool_outputs/$fileName`")
                    appendLine("Use shell to search: `grep \"pattern\" /tool_outputs/$fileName`")
                    // 关键行预览：省一次「落盘后再 grep」的往返（错误行 + 末尾若干行 + 总行数）。
                    // ⚠ 很多工具输出是 JSON（如 shell 的 {"stdout":"a\nb"}），其中的换行是**字面 `\n`**，
                    // 直接按真实换行切分会得到「1 行」并失去全部意义 —— 先展开转义换行再统计。
                    val logical = fullText.replace("\\n", "\n").replace("\\r", "")
                    val lines = logical.split('\n')
                    val keywords =
                        digestKeywords?.takeIf { it.isNotEmpty() } ?: TOOL_OUTPUT_DIGEST_DEFAULT_KEYWORDS
                    val hitLimit = if (digestMode) TOOL_OUTPUT_DIGEST_MAX_HITS else 5
                    val errorLines =
                        lines.withIndex()
                            .filter { (_, l) -> keywords.any { k -> l.contains(k, ignoreCase = true) } }
                            .take(hitLimit)
                    appendLine(
                        "Total lines: ${lines.size}" +
                            if (errorLines.isEmpty()) "" else " · error-like: ${errorLines.size}",
                    )
                    errorLines.forEach { (i, l) -> appendLine("  ! line ${i + 1}: ${l.take(160)}") }
                    val tail = lines.takeLast(5).filter { it.isNotBlank() }
                    if (tail.isNotEmpty()) {
                        appendLine("  … last ${tail.size} non-blank line(s):")
                        tail.forEach { appendLine("  | ${it.take(160)}") }
                    }
                    appendLine()
                    append(preview)
                }
            )
        ) + nonTextParts
    }

    suspend fun maskToolOutput(parts: List<UIMessagePart>): List<UIMessagePart> {
        val vaultRepo = runCatching { getKoin().get<CredentialVaultRepository>() }.getOrNull()
            ?: return parts
        try {
            SecretMasker.refresh(vaultRepo)
        } catch (_: Exception) {
            // 掩码失败不阻断工具结果（安全兜底降级为原样输出）
        }
        return parts.map { part ->
            if (part is UIMessagePart.Text) {
                UIMessagePart.Text(
                    text = SecretMasker.mask(part.text),
                    metadata = part.metadata, // 保留 metadata（如 DiffMetadata 存 diff 供 UI 渲染红绿）
                )
            } else {
                part
            }
        }
    }
}

// 工具输出硬上限已统一到设置值（settings.toolOutputMaxChars，默认 8K / 范围 1–32K），
// 不再写死 32K（曾与 diff_files 的 40K 矛盾）。
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024
// 摘要模式（助手级名单命中时）：预览更小、关键词命中的行更多 —— 规则化"摘要"，
// 目标是「够用的骨架 + 可检索的全文落盘」，而不是把整段塞回上下文。
private const val TOOL_OUTPUT_DIGEST_PREVIEW_CHARS = 1200
private const val TOOL_OUTPUT_DIGEST_MAX_HITS = 20
