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
import me.rerere.rikkahub.web.BadRequestException
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

        // 批量解密：一次请求取回多个凭证（端上 vault-get 多 key 用）
        post("/resolve") {
            val request = call.receive<ResolveRequest>()
            if (request.token.isBlank() || !sessionManager.verifyToken(request.token, VaultSessionManager.SCOPE_DECRYPT)) {
                throw UnauthorizedException("Invalid or expired vault session token")
            }
            if (request.names.isEmpty()) {
                throw BadRequestException("names must not be empty")
            }
            val values = linkedMapOf<String, String>()
            val missing = mutableListOf<String>()
            request.names.distinct().forEach { name ->
                val entry = repository.getByName(name)
                if (entry != null) {
                    val plaintext = repository.decryptValue(entry)
                    if (plaintext != null) {
                        values[name] = plaintext
                        repository.logAccess(name, "remote-api", "resolve")
                    } else {
                        missing += name
                    }
                } else {
                    missing += name
                }
            }
            call.respond(
                HttpStatusCode.OK,
                ResolveResponse(values = values, missing = missing),
            )
        }

        // 条目列表（不含明文）：端上 vault-get --list 用
        get("/status") {
            val token = call.request.queryParameters["token"].orEmpty()
            if (token.isBlank() || !sessionManager.verifyToken(token)) {
                throw UnauthorizedException("Invalid or expired vault session token")
            }
            val entries = repository.getAll().map { entry ->
                StatusEntry(
                    name = entry.name,
                    description = entry.description,
                    group = entry.grp,
                    length = entry.valueLength,
                )
            }
            call.respond(HttpStatusCode.OK, StatusResponse(valid = true, entries = entries))
        }

        // 审计查询：端上 vault-get --audit 用
        get("/audit") {
            val token = call.request.queryParameters["token"].orEmpty()
            if (token.isBlank() || !sessionManager.verifyToken(token)) {
                throw UnauthorizedException("Invalid or expired vault session token")
            }
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
            val entries = repository.recentAudit(limit).map { log ->
                AuditEntry(
                    name = log.credentialName,
                    caller = log.caller,
                    action = log.action,
                    at = log.tsMs,
                )
            }
            call.respond(HttpStatusCode.OK, AuditResponse(entries = entries))
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

@Serializable
data class ResolveRequest(
    val token: String,
    val names: List<String> = emptyList(),
)

@Serializable
data class ResolveResponse(
    val values: Map<String, String> = emptyMap(),
    val missing: List<String> = emptyList(),
)

@Serializable
data class StatusEntry(
    val name: String,
    val description: String = "",
    val group: String = "",
    val length: Int = 0,
)

@Serializable
data class StatusResponse(
    val valid: Boolean,
    val entries: List<StatusEntry> = emptyList(),
)

@Serializable
data class AuditEntry(
    val name: String,
    val caller: String = "",
    val action: String = "",
    val at: Long = 0,
)

@Serializable
data class AuditResponse(
    val entries: List<AuditEntry> = emptyList(),
)
