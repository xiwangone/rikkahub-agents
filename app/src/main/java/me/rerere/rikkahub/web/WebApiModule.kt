package me.rerere.rikkahub.web

import android.content.Context
import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.web.dto.ErrorResponse
import me.rerere.rikkahub.web.dto.WebAuthTokenRequest
import me.rerere.rikkahub.web.dto.WebAuthTokenResponse
import me.rerere.rikkahub.web.routes.aiIconRoutes
import me.rerere.rikkahub.web.routes.assetsRoutes
import me.rerere.rikkahub.web.routes.conversationRoutes
import me.rerere.rikkahub.web.routes.eventsRoutes
import me.rerere.rikkahub.web.routes.filesRoutes
import me.rerere.rikkahub.web.routes.folderRoutes
import me.rerere.rikkahub.web.routes.settingsRoutes
import java.security.MessageDigest
import java.util.Date
import java.util.UUID

private const val WEB_JWT_ISSUER = "rikkahub-web"
private const val WEB_JWT_AUDIENCE = "rikkahub-web-client"
private const val WEB_JWT_SUBJECT = "web-access"
private const val WEB_JWT_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000
private const val WEB_ACCESS_TOKEN_QUERY_KEY = "access_token"
private const val WEB_AUTH_REALM = "rikkahub-web-api"

/**
 * Configure Web API for the Ktor application.
 * This should be called from app module when starting the web server.
 *
 * Example usage:
 * ```
 * startWebServer(port = 8080) {
 *     configureWebApi(context, chatService, conversationRepo, settingsStore, filesManager)
 * }
 * ```
 */
fun Application.configureWebApi(
    context: Context,
    chatService: ChatService,
    conversationRepo: ConversationRepository,
    folderRepo: FolderRepository,
    settingsStore: SettingsStore,
    filesManager: FilesManager,
    vaultRepository: me.rerere.rikkahub.data.vault.CredentialVaultRepository,
    vaultSessionManager: me.rerere.rikkahub.data.vault.VaultSessionManager,
) {
    val jwtEnabled = settingsStore.settingsFlow.value.webServerJwtEnabled

    // 网段白名单：webServerAllowedNetworks 非空时，仅放行匹配 CIDR 的来源 IP（默认空 = 不限制）
    intercept(ApplicationCallPipeline.Plugins) {
        // Ktor PipelineContext 以 context 暴露 ApplicationCall
        val call = context
        val allowedNetworks = settingsStore.settingsFlow.value.webServerAllowedNetworks
        if (!isRemoteHostAllowed(call.request.origin.remoteHost, allowedNetworks)) {
            call.respond(
                HttpStatusCode.Forbidden,
                ErrorResponse("Network not allowed: ${call.request.origin.remoteHost}", HttpStatusCode.Forbidden.value),
            )
            finish()
        }
    }

    install(ContentNegotiation) {
        json(JsonInstant)
    }

    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ErrorResponse("Not Found", status.value))
        }
        exception<ApiException> { call, cause ->
            call.respond(cause.status, ErrorResponse(cause.message, cause.status.value))
        }
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(cause.message ?: "Internal server error", 500)
            )
        }
    }

    if (jwtEnabled) {
        install(Authentication) {
            jwt("auth-jwt") {
                realm = WEB_AUTH_REALM
                verifier { _ ->
                    // Dynamically read the current password on each request so that
                    // tokens signed after a password change are validated correctly.
                    val currentPassword = settingsStore.settingsFlow.value.webServerAccessPassword
                    val secret = currentPassword.ifBlank {
                        // Keep protected routes closed when jwt is enabled but password is missing.
                        "__missing_password_${UUID.randomUUID()}__"
                    }
                    buildWebJwtVerifier(secret)
                }
                authHeader { call ->
                    extractAccessToken(
                        authorizationHeader = call.request.headers[HttpHeaders.Authorization],
                        queryToken = call.request.queryParameters[WEB_ACCESS_TOKEN_QUERY_KEY]
                    )?.let { token ->
                        HttpAuthHeader.Single("Bearer", token)
                    }
                }
                validate { credential ->
                    val currentPassword = settingsStore.settingsFlow.value.webServerAccessPassword
                    if (currentPassword.isBlank()) {
                        null
                    } else {
                        credential.payload.subject?.takeIf { it == WEB_JWT_SUBJECT }?.let {
                            io.ktor.server.auth.jwt.JWTPrincipal(credential.payload)
                        }
                    }
                }
                challenge { _, _ ->
                    val currentPassword = settingsStore.settingsFlow.value.webServerAccessPassword
                    if (currentPassword.isBlank()) {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            ErrorResponse("Access password is not configured", HttpStatusCode.Forbidden.value)
                        )
                    } else {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            ErrorResponse("Unauthorized", HttpStatusCode.Unauthorized.value)
                        )
                    }
                }
            }
        }
    }

    routing {
        route("/api") {
            post("/auth/token") {
                val settings = settingsStore.settingsFlow.value
                if (!settings.webServerJwtEnabled) {
                    throw BadRequestException("JWT auth is disabled")
                }

                val accessPassword = settings.webServerAccessPassword
                if (accessPassword.isBlank()) {
                    throw BadRequestException("Access password is not configured")
                }

                val request = call.receive<WebAuthTokenRequest>()
                if (!secureEquals(request.password, accessPassword)) {
                    throw UnauthorizedException("Invalid password")
                }

                val requestedScope = request.scope?.lowercase()?.takeIf { it == WEB_SCOPE_READ || it == WEB_SCOPE_FULL } ?: WEB_SCOPE_READ
                val (token, expiresAt) = createWebJwt(accessPassword, requestedScope)
                call.respond(
                    HttpStatusCode.OK,
                    WebAuthTokenResponse(
                        token = token,
                        expiresAt = expiresAt
                    )
                )
            }

            aiIconRoutes(context)

            // Vault 解密 API 已退役（2026-08-14）：凭证通道迁出 Web 完成——
            // App 内 AI 走 vault_* 直通（不经 Web）；沙箱 vault-get 改用 load-creds.sh 备用。
            // Web 服务恢复纯能力面（chat/files/settings——不含凭证）。

            if (jwtEnabled) {
                authenticate("auth-jwt") {
                    conversationRoutes(chatService, conversationRepo, folderRepo, settingsStore)
                    folderRoutes(chatService, folderRepo, settingsStore)
                    eventsRoutes(chatService, conversationRepo, folderRepo, settingsStore)
                    settingsRoutes(settingsStore)
                    filesRoutes(filesManager, context)
                    assetsRoutes(context)
                }
            } else {
                conversationRoutes(chatService, conversationRepo, folderRepo, settingsStore)
                folderRoutes(chatService, folderRepo, settingsStore)
                eventsRoutes(chatService, conversationRepo, folderRepo, settingsStore)
                settingsRoutes(settingsStore)
                filesRoutes(filesManager, context)
                assetsRoutes(context)
            }
        }
    }
}

private fun createWebJwt(secret: String, scope: String = WEB_SCOPE_READ): Pair<String, Long> {
    val now = System.currentTimeMillis()
    val expiresAt = now + WEB_JWT_TTL_MILLIS
    val token = JWT.create()
        .withIssuer(WEB_JWT_ISSUER)
        .withAudience(WEB_JWT_AUDIENCE)
        .withSubject(WEB_JWT_SUBJECT)
        .withClaim(WEB_JWT_SCOPE_CLAIM, scope)
        .withIssuedAt(Date(now))
        .withExpiresAt(Date(expiresAt))
        .sign(Algorithm.HMAC256(secret))
    return token to expiresAt
}

private fun buildWebJwtVerifier(secret: String): JWTVerifier {
    return JWT.require(Algorithm.HMAC256(secret))
        .withIssuer(WEB_JWT_ISSUER)
        .withAudience(WEB_JWT_AUDIENCE)
        .withSubject(WEB_JWT_SUBJECT)
        .build()
}

private fun extractBearerToken(authorizationHeader: String?): String? {
    if (authorizationHeader.isNullOrBlank()) return null
    val prefix = "Bearer "
    if (!authorizationHeader.startsWith(prefix, ignoreCase = true)) return null
    return authorizationHeader.substring(prefix.length).trim().takeIf { it.isNotEmpty() }
}

private fun extractAccessToken(authorizationHeader: String?, queryToken: String?): String? {
    return extractBearerToken(authorizationHeader)
        ?: queryToken?.trim()?.takeIf { it.isNotEmpty() }
}

private fun secureEquals(left: String, right: String): Boolean {
    return MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))
}


// ========== 权限档（scope）与网段白名单工具 ==========

/** JWT 中的权限档 claim 名 */
const val WEB_JWT_SCOPE_CLAIM = "scope"

/** 只读：GET 类能力 */
const val WEB_SCOPE_READ = "read"

/** 完全授权：写类能力（发消息/写文件/改设置/SSH 执行） */
const val WEB_SCOPE_FULL = "full"

/** 读取当前请求的权限档（缺失视为只读，避免历史 token 意外获得写权限） */
fun ApplicationCall.currentScope(): String =
    principal<JWTPrincipal>()?.payload?.getClaim(WEB_JWT_SCOPE_CLAIM)?.asString()
        ?.lowercase()
        ?.takeIf { it == WEB_SCOPE_READ || it == WEB_SCOPE_FULL }
        ?: WEB_SCOPE_READ

/** 写类路由前置校验：非 full 权限直接 403 并返回 true（调用方应 return） */
suspend fun ApplicationCall.denyUnlessFullScope(): Boolean {
    if (currentScope() == WEB_SCOPE_FULL) return false
    respond(
        HttpStatusCode.Forbidden,
        ErrorResponse("Full scope required", HttpStatusCode.Forbidden.value),
    )
    return true
}

/** 来源 IP 是否在白名单内；allowedNetworks 为空 = 不限制 */
fun isRemoteHostAllowed(remoteHost: String, allowedNetworks: String): Boolean {
    val rules =
        allowedNetworks
            .split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    if (rules.isEmpty()) return true
    val addr = parseIpv4(remoteHost) ?: return false
    return rules.any { ipv4InCidr(addr, it) }
}

private fun parseIpv4(host: String): Long? {
    val parts = host.trim().removePrefix("::ffff:").split('.')
    if (parts.size != 4) return null
    var value = 0L
    for (p in parts) {
        val n = p.toIntOrNull() ?: return null
        if (n !in 0..255) return null
        value = (value shl 8) or n.toLong()
    }
    return value
}

private fun ipv4InCidr(addr: Long, cidr: String): Boolean {
    val raw = cidr.trim()
    if (raw.isEmpty()) return false
    val slash = raw.indexOf('/')
    val ipPart = if (slash >= 0) raw.substring(0, slash) else raw
    val base = parseIpv4(ipPart) ?: return false
    if (slash < 0) return base == addr
    val prefix = raw.substring(slash + 1).toIntOrNull()?.takeIf { it in 0..32 } ?: return false
    val mask = if (prefix == 0) 0L else (-1L shl (32 - prefix)) and 0xFFFFFFFFL
    return (addr and mask) == (base and mask)
}
