package me.rerere.rikkahub.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import me.rerere.rikkahub.data.vault.VaultSessionManager
import me.rerere.rikkahub.web.ForbiddenException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.web.UnauthorizedException

/**
 * Vault 解密 API（阶段 2）。
 *
 * 安全模型：
 * - 远程调用（ECS/沙箱经 Web 桥隧道）不弹指纹，用会话 token（VaultSessionManager）
 * - token 由 App 内指纹验证后签发，30 分钟有效
 * - 每次解密返回单条明文；token 校验失败返回 401
 *
 * 端点：
 * - POST /vault/decrypt     {token, name} → {value}
 * - GET  /vault/session     {token} → {valid}
 */
fun Route.vaultRoutes(
    repository: CredentialVaultRepository,
    sessionManager: VaultSessionManager,
) {
    route("/vault") {
        post("/decrypt") {
            val request = call.receive<DecryptRequest>()
            val token = request.token
            if (token.isBlank() || !sessionManager.verifyToken(token, VaultSessionManager.SCOPE_DECRYPT)) {
                throw UnauthorizedException("Invalid or expired vault session token")
            }
            val entry = repository.getByName(request.name)
                ?: throw NotFoundException("Credential not found: ${request.name}")
            val plaintext = repository.decryptValue(entry)
                ?: throw ForbiddenException("Decryption failed")
            // 审计：记录远程解密调用
            repository.logAccess(request.name, "remote-api", "decrypt")
            call.respond(
                HttpStatusCode.OK,
                DecryptResponse(value = plaintext),
            )
        }

        get("/session") {
            val token = call.request.queryParameters["token"].orEmpty()
            val valid = token.isNotBlank() && sessionManager.verifyToken(token)
            call.respond(HttpStatusCode.OK, SessionStatusResponse(valid = valid))
        }
    }
}

@Serializable
data class DecryptRequest(
    val token: String,
    val name: String,
)

@Serializable
data class DecryptResponse(
    val value: String,
)

@Serializable
data class SessionStatusResponse(
    val valid: Boolean,
)
