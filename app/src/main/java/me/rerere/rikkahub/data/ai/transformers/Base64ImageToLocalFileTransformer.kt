package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.files.FilesManager
import org.koin.java.KoinJavaComponent.getKoin

object Base64ImageToLocalFileTransformer : OutputMessageTransformer {
    // 逐条独立转换（单条消息的图片落盘），不依赖同批次其他消息
    override val supportsIncremental: Boolean get() = true

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val filesManager = getKoin().get<FilesManager>()
        return messages.map { message ->
            filesManager.convertBase64ImagePartToLocalFile(message)
        }
    }
}
