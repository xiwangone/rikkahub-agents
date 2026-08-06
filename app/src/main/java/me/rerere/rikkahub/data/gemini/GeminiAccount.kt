package me.rerere.rikkahub.data.gemini

import kotlinx.serialization.Serializable

/**
 * One signed-in Google account usable against Cloud Code Assist.
 *
 * [projectId] is the `cloudaicompanionProject` resolved once at sign-in through
 * loadCodeAssist / onboardUser. Every generate request has to carry it, so it is stored with the
 * tokens rather than rediscovered per request.
 */
@Serializable
data class GeminiAccount(
    val id: String,
    val name: String,
    val email: String = "",
    val projectId: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val enabled: Boolean = true,
    val tokenStatus: GeminiTokenStatus = GeminiTokenStatus.UNKNOWN,
)

@Serializable
enum class GeminiTokenStatus {
    UNKNOWN,
    AVAILABLE,
    EXPIRED,
    INVALID,
}

internal fun GeminiAccount.isAvailable(): Boolean =
    enabled && tokenStatus != GeminiTokenStatus.INVALID
