package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool
import android.content.Context
import android.os.SystemClock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.BackupEncryptionManager
import me.rerere.rikkahub.data.sync.S3Sync
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import org.koin.java.KoinJavaComponent.getKoin
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.BiometricResultBuffer
import me.rerere.rikkahub.data.ai.tools.local.CameraResultBuffer
import me.rerere.rikkahub.data.ai.tools.local.InteractiveToolStreamer
import me.rerere.rikkahub.data.ai.tools.local.AccessibilityServiceHandle
import me.rerere.rikkahub.data.ai.tools.local.buildChartDisplayTool
import me.rerere.rikkahub.data.ai.tools.local.buildJavascriptTool
import me.rerere.rikkahub.data.ai.tools.local.deviceInfoTool
import me.rerere.rikkahub.data.ai.tools.local.diagnosticsTool
import me.rerere.rikkahub.data.ai.tools.local.callLogTool
import me.rerere.rikkahub.data.ai.tools.local.cameraPhotoTool
import me.rerere.rikkahub.data.ai.tools.local.clickNodeTool
import me.rerere.rikkahub.data.ai.tools.local.downloadTool
import me.rerere.rikkahub.data.ai.tools.local.fingerprintTool
import me.rerere.rikkahub.data.ai.tools.local.findNodeTool
import me.rerere.rikkahub.data.ai.tools.local.getBrightnessTool
import me.rerere.rikkahub.data.ai.tools.local.getVolumeTool
import me.rerere.rikkahub.data.ai.tools.local.globalActionTool
import me.rerere.rikkahub.data.ai.tools.local.testModelTool
import me.rerere.rikkahub.data.ai.tools.local.listContactsTool
import me.rerere.rikkahub.data.ai.tools.local.listSmsInboxTool
import me.rerere.rikkahub.data.ai.tools.local.locationTool
import me.rerere.rikkahub.data.ai.tools.local.longPressTool
import me.rerere.rikkahub.data.ai.tools.local.mediaScannerTool
import me.rerere.rikkahub.data.ai.tools.local.micRecorderTool
import me.rerere.rikkahub.data.ai.tools.local.notificationTool
import me.rerere.rikkahub.data.ai.tools.local.getMediaStatusTool
import me.rerere.rikkahub.data.ai.tools.local.pauseMediaTool
import me.rerere.rikkahub.data.ai.tools.local.playMediaTool
import me.rerere.rikkahub.data.ai.tools.local.resumeMediaTool
import me.rerere.rikkahub.data.ai.tools.local.seekMediaTool
import me.rerere.rikkahub.data.ai.tools.local.readWindowTreeTool
import me.rerere.rikkahub.data.ai.tools.local.scrollTool
import me.rerere.rikkahub.data.ai.tools.local.searchContactsTool
import me.rerere.rikkahub.data.ai.tools.local.searchSmsTool
import me.rerere.rikkahub.data.ai.tools.local.setBrightnessTool
import me.rerere.rikkahub.data.ai.tools.local.setVolumeTool
import me.rerere.rikkahub.data.ai.tools.local.shareTool
import me.rerere.rikkahub.data.ai.tools.local.speechToTextTool
import me.rerere.rikkahub.data.ai.tools.local.stopMediaTool
import me.rerere.rikkahub.data.ai.tools.local.swipeTool
import me.rerere.rikkahub.data.ai.tools.local.takeScreenshotTool
import me.rerere.rikkahub.data.ai.tools.local.tapTool
import me.rerere.rikkahub.data.ai.tools.local.toastTool
import me.rerere.rikkahub.data.ai.tools.local.torchTool
import me.rerere.rikkahub.data.ai.tools.local.vibrateTool
import me.rerere.rikkahub.data.ai.tools.local.deleteSshHostTool
import me.rerere.rikkahub.data.ai.tools.local.forgetSshHostKeyTool
import me.rerere.rikkahub.data.ai.tools.local.listSshHostsTool
import me.rerere.rikkahub.data.ai.tools.local.telegramAddWhitelistTool
import me.rerere.rikkahub.data.ai.tools.local.telegramDeleteCommandsTool
import me.rerere.rikkahub.data.ai.tools.local.telegramDisableTool
import me.rerere.rikkahub.data.ai.tools.local.telegramEnableTool
import me.rerere.rikkahub.data.ai.tools.local.telegramGetCommandsTool
import me.rerere.rikkahub.data.ai.tools.local.telegramRemoveWhitelistTool
import me.rerere.rikkahub.data.ai.tools.local.telegramSendDocumentTool
import me.rerere.rikkahub.data.ai.tools.local.telegramSendMessageTool
import me.rerere.rikkahub.data.ai.tools.local.telegramSendPhotoTool
import me.rerere.rikkahub.data.ai.tools.local.telegramSetAssistantTool
import me.rerere.rikkahub.data.ai.tools.local.telegramSetCommandsTool
import me.rerere.rikkahub.data.ai.tools.local.telegramSetDefaultChatTool
import me.rerere.rikkahub.data.ai.tools.local.telegramSetTokenTool
import me.rerere.rikkahub.data.ai.tools.local.telegramStatusTool
import me.rerere.rikkahub.data.ai.tools.local.saveSshHostTool
import me.rerere.rikkahub.data.ai.tools.local.sshPresetsTool
import me.rerere.rikkahub.data.ai.tools.local.sshJobPollTool
import me.rerere.rikkahub.data.ai.tools.local.sshDownloadTool
import me.rerere.rikkahub.data.ai.tools.local.sshExecSavedTool
import me.rerere.rikkahub.data.ai.tools.local.vaultDeployKeyTool
import me.rerere.rikkahub.data.ai.tools.local.sshExecTool
import me.rerere.rikkahub.data.ai.tools.local.sshUploadTool
import me.rerere.rikkahub.data.ai.tools.local.writeTextFileTool
import me.rerere.rikkahub.data.ai.tools.local.showImageTool
import me.rerere.rikkahub.data.ai.tools.local.openFileTool
import me.rerere.rikkahub.data.ai.tools.local.transcribeAudioFileTool
import me.rerere.rikkahub.data.ai.tools.local.whisperStatusTool
import me.rerere.rikkahub.data.ai.tools.local.listFilesTool
import me.rerere.rikkahub.data.ai.tools.local.readFileTool
import me.rerere.rikkahub.data.ai.tools.local.writeBinaryFileTool
import me.rerere.rikkahub.data.ai.tools.local.deleteFileTool
import me.rerere.rikkahub.data.ai.tools.local.moveFileTool
import me.rerere.rikkahub.data.ai.tools.local.copyFileTool
import me.rerere.rikkahub.data.ai.tools.local.createDirectoryTool
import me.rerere.rikkahub.data.ai.tools.local.fileInfoTool
import me.rerere.rikkahub.data.ai.tools.local.findFilesTool
import me.rerere.rikkahub.data.ai.tools.local.dismissNotificationTool
import me.rerere.rikkahub.data.ai.tools.local.listActiveNotificationsTool
import me.rerere.rikkahub.data.ai.tools.local.listRecentNotificationsTool
import me.rerere.rikkahub.data.ai.tools.local.notificationActionClickTool
import me.rerere.rikkahub.data.ai.tools.local.notificationReplyTool
import me.rerere.rikkahub.data.ai.tools.local.notificationStatusTool
import me.rerere.rikkahub.data.ai.tools.local.batchCopyTool
import me.rerere.rikkahub.data.ai.tools.local.batchMoveTool
import me.rerere.rikkahub.data.ai.tools.local.batchDeleteTool
import me.rerere.rikkahub.data.ai.tools.local.webFetchTool
import me.rerere.rikkahub.data.ai.tools.local.webExtractTool
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.utils.readClipboardText
import me.rerere.rikkahub.utils.writeClipboardText
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * 工具注册表：LocalToolOption 到工具构建逻辑的映射。
 *
 * 从 LocalTools.getTools 抽出——原函数 428 行、圈复杂度 64，
 * 本质是 50+ 个 `if (enabled(X)) { tools.add(...) }` 的注册表。
 * 改为数据驱动后，getTools 只剩循环 + 授权门判断。
 *
 * 构建 lambda 以 LocalTools 为 receiver（取 context 与各类依赖），
 * 以 tools 列表为参数（部分工具的 knownToolNamesProvider 闭包需要它）。
 */
internal data class ToolEntry(
    val option: LocalToolOption,
    val addTo: LocalTools.(tools: MutableList<Tool>, invocationContext: ToolInvocationContext) -> Unit,
)

