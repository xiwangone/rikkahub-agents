package me.rerere.rikkahub.data.ai.transformers

import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.document.DocxParser
import me.rerere.document.EpubParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import java.io.File

object DocumentAsPromptTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return withContext(Dispatchers.IO) {
            messages.map { message ->
                message.copy(
                    parts = message.parts.toMutableList().apply {
                        val documents = filterIsInstance<UIMessagePart.Document>()
                        if (documents.isNotEmpty()) {
                            documents.forEach { document ->
                                val content = readDocumentContent(document)
                                val path = resolveWorkspacePath(document.url)
                                val pathAttr = path?.let { " path=\"$it\"" } ?: ""
                                val prompt = buildReferencePrompt(
                                    fileName = document.fileName,
                                    workspacePath = path,
                                    content = content,
                                    inlineLimitChars = ctx.settings.displaySetting.attachmentInlineThreshold,
                                    hasReadableFileTool = ctx.hasReadableFileTool,
                                ) ?: """
                                  <UploadFile name="${document.fileName}"$pathAttr>
                                  ```
                                  $content
                                  ```
                                  </UploadFile>
                                  """.trimMargin()
                                add(0, UIMessagePart.Text(prompt))
                            }
                        }
                        val images = filterIsInstance<UIMessagePart.Image>()
                        images.forEach { image ->
                            val path = resolveWorkspacePath(image.url) ?: return@forEach
                            val prompt = """
                              <UploadFile name="${File(path).name}" path="$path" />
                              """.trimMargin()
                            add(0, UIMessagePart.Text(prompt))
                        }
                    }
                )
            }
        }
    }

    /**
     * 大附件的**引用式**呈现：正文长度超过 [inlineLimitChars] 且本轮确实能按 path 读文件时，
     * 不再内联正文，只给 `name` + `path` + 首尾摘要 + 一行取用指令（对齐图片 part 的写法）。
     *
     * 返回 null = 不满足引用条件，调用方按原样整篇内联。以下三种情况**必须**保持内联，
     * 否则模型既拿不到正文、也没有任何工具能把正文取回来：
     *  - 阈值内的小附件（零行为变化）；
     *  - 本轮没有可读取文件的工作区工具（纯聊天 provider / 工作区未就绪）；
     *  - `path` 解析不出来（不在 filesDir/upload 下，模型无从按路径读取）。
     *
     * 退化保护取任务书的 ②+③ 组合：② 按本轮真实工具集 gate（默认阈值 8000 字符本身也偏保守，
     * 兼顾 ①）；③ 保留首尾摘要（见 [excerptOf]），模型至少知道文件大概是什么。
     */
    internal fun buildReferencePrompt(
        fileName: String,
        workspacePath: String?,
        content: String,
        inlineLimitChars: Int,
        hasReadableFileTool: Boolean,
    ): String? {
        if (!hasReadableFileTool || workspacePath == null) return null
        if (content.length <= inlineLimitChars.coerceAtLeast(1)) return null
        return buildString {
            append(
                "This uploaded file is too large to inline, so its content is NOT included below. " +
                    "Read it with the workspace file tools (workspace_read_file / workspace_shell) " +
                    "at path `$workspacePath` whenever you need its contents.\n",
            )
            append("<UploadFile name=\"$fileName\" path=\"$workspacePath\">\n")
            append("```\n")
            append(excerptOf(content))
            append("\n")
            append("```\n")
            append("</UploadFile>")
        }
    }

    /**
     * 首尾摘要：单边上限 [EXCERPT_CHARS_PER_SIDE] 字符，总长有界（≤ 2×M+省略号）；
     * 头尾相接（正文 ≤ 2M）时给全量、不插省略号。只为让模型知道「文件大致是什么」，
     * 不是正文替代品。
     */
    internal fun excerptOf(content: String): String =
        if (content.length <= EXCERPT_CHARS_PER_SIDE * 2) {
            // 不超两侧之和：头与尾已覆盖全文，直接给原文；此处拼接会把重叠中段重复一遍。
            content
        } else {
            val head = content.take(EXCERPT_CHARS_PER_SIDE)
            val tail = content.takeLast(EXCERPT_CHARS_PER_SIDE)
            "$head\n...\n$tail"
        }

    // 引用式摘要单边字符数上限。
    private const val EXCERPT_CHARS_PER_SIDE = 1_000

    private fun parsePdfAsText(file: File): String {
        return PdfParser.parserPdf(file)
    }

    private fun parseDocxAsText(file: File): String {
        return DocxParser.parse(file)
    }

    private fun parsePptxAsText(file: File): String {
        return PptxParser.parse(file)
    }

    private fun parseEpubAsText(file: File): String {
        return EpubParser.parse(file)
    }

    // 上传文件保存在 filesDir/upload 下, 该目录通过 proot 挂载到 workspace 的 /upload
    // 返回文件在 workspace 内的绝对路径, 便于 AI 用 workspace 工具直接读取原始文件
    private fun resolveWorkspacePath(url: String): String? {
        val file = runCatching { url.toUri().toFile() }.getOrNull() ?: return null
        return resolveWorkspacePathForFile(file)
    }

    // 纯函数, 不依赖 android.net.Uri, 以便单元测试覆盖 upload 目录网关判断
    internal fun resolveWorkspacePathForFile(file: File): String? {
        if (file.parentFile?.name != "upload") return null
        return "/upload/${file.name}"
    }

    private fun readDocumentContent(document: UIMessagePart.Document): String {
        val file = runCatching { document.url.toUri().toFile() }.getOrNull()
            ?: return "[ERROR, invalid file uri: ${document.fileName}]"
        if (!file.exists() || !file.isFile) {
            return "[ERROR, file not found: ${document.fileName}]"
        }
        return runCatching {
            when (document.mime) {
                "application/pdf" -> parsePdfAsText(file)
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> parseDocxAsText(file)
                "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> parsePptxAsText(file)
                "application/epub+zip" -> parseEpubAsText(file)
                else -> file.readText()
            }
        }.getOrElse {
            "[ERROR, failed to read file: ${document.fileName}]"
        }
    }
}
