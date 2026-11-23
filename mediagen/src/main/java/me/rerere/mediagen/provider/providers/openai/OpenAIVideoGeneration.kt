package me.rerere.mediagen.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.mediagen.model.MediaGenerationError
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaGenerationOutput
import me.rerere.mediagen.model.MediaGenerationRequest
import me.rerere.mediagen.model.MediaGenerationStatus
import me.rerere.mediagen.model.MediaGenerationTask
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.providers.bearerRequest
import me.rerere.mediagen.provider.providers.executeJson
import me.rerere.mediagen.provider.providers.long
import me.rerere.mediagen.provider.providers.postJson
import me.rerere.mediagen.provider.providers.string
import okhttp3.OkHttpClient

/**
 * OpenAI 兼容的视频接口（Sora 协议）：提交后得到任务 ID，轮询到 `completed` 再从
 * `{baseUrl}/videos/{id}/content` 取视频字节。完成态地址与 baseUrl 同源，下载时要带
 * [MediaGenerationProviderSetting.downloadHeaders]。
 */
internal class OpenAIVideoGeneration(
    private val client: OkHttpClient,
) {
    private val id = OpenAIMediaGenerationProvider.PROVIDER_ID

    suspend fun create(
        setting: MediaGenerationProviderSetting.OpenAI,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): MediaGenerationTask {
        val baseUrl = setting.baseUrl.trimEnd('/')
        val httpRequest = bearerRequest("$baseUrl/videos", setting.apiKey)
            .postJson(buildCreateBody(model, request))
            .build()
        return parseTask(setting, client.executeJson(httpRequest, id), model.modelId)
    }

    suspend fun query(
        setting: MediaGenerationProviderSetting.OpenAI,
        model: MediaGenerationModel,
        taskId: String,
    ): MediaGenerationTask {
        val baseUrl = setting.baseUrl.trimEnd('/')
        val httpRequest = bearerRequest("$baseUrl/videos/$taskId", setting.apiKey).get().build()
        return parseTask(setting, client.executeJson(httpRequest, id), model.modelId)
    }

    internal fun buildCreateBody(
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): JsonObject = buildJsonObject {
        val prompt = requireNotNull(request.prompt?.takeIf(String::isNotBlank)) {
            "OpenAI video generation requires a non-empty prompt"
        }
        request.extraParameters.forEach { (key, value) -> put(key, value) }
        put("model", model.modelId)
        put("prompt", prompt)
        // Sora 兼容端点用 mode 区分生成方式：agnes 2.5 系列是 text / keyframe / reference，
        // 文生视频为 text。本适配器目前只做文生视频。
        put("mode", "text")
        // durationSeconds 为 -1 时交给模型决定，不发字段；Sora 协议里 seconds 是字符串档位
        request.durationSeconds?.takeIf { it >= 0 }?.let { put("seconds", it.toString()) }
        request.resolution?.takeIf(String::isNotBlank)?.let { put("size", it) }
        request.aspectRatio?.takeIf(String::isNotBlank)?.let { put("aspect_ratio", it) }
        request.seed?.let { put("seed", it) }
        require(request.count == null || request.count == 1) { "OpenAI generates one video per job" }
        require(request.inputs.isEmpty()) { "OpenAI video generation does not support image inputs yet" }
        require(request.generateAudio == null) { "OpenAI video generation does not expose generateAudio" }
        require(request.watermark == null) { "OpenAI video generation does not expose watermark" }
        require(request.promptEnhancement == null) {
            "OpenAI video generation does not expose prompt enhancement"
        }
        require(request.callbackUrl == null) { "OpenAI video generation does not expose callbackUrl" }
    }

    internal fun parseTask(
        setting: MediaGenerationProviderSetting.OpenAI,
        root: JsonObject,
        fallbackModel: String? = null,
    ): MediaGenerationTask {
        val baseUrl = setting.baseUrl.trimEnd('/')
        // Sora 协议返回 id；agnes 2.5 系列也可能只给 video_id / task_id
        val taskId = root.string("id") ?: root.string("video_id") ?: root.string("task_id")
            ?: error("OpenAI response does not contain id")
        val status = mapStatus(root.string("status"))
        val errorMessage = when (val failure = root["error"]) {
            is JsonObject -> failure.string("message")
            is JsonPrimitive -> failure.contentOrNull
            else -> null
        }
        return MediaGenerationTask(
            id = taskId,
            provider = id,
            model = fallbackModel,
            status = status,
            outputs = if (status == MediaGenerationStatus.SUCCEEDED) {
                listOf(
                    MediaGenerationOutput(
                        url = "$baseUrl/videos/$taskId/content",
                        mimeType = "video/mp4",
                    ),
                )
            } else {
                emptyList()
            },
            error = errorMessage?.let { MediaGenerationError(message = it) },
            createdAtEpochSeconds = root.long("created_at"),
            metadata = root,
        )
    }

    private fun mapStatus(status: String?): MediaGenerationStatus = when (status?.lowercase()) {
        "queued", "pending" -> MediaGenerationStatus.QUEUED
        "in_progress" -> MediaGenerationStatus.RUNNING
        "completed" -> MediaGenerationStatus.SUCCEEDED
        "failed" -> MediaGenerationStatus.FAILED
        "cancelled", "canceled" -> MediaGenerationStatus.CANCELLED
        "expired" -> MediaGenerationStatus.EXPIRED
        else -> MediaGenerationStatus.UNKNOWN
    }
}
