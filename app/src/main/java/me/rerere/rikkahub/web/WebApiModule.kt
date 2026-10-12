package me.rerere.rikkahub.web

import android.content.Context
import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
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
import me.rerere.rikkahub.utils.isRemoteHostAllowed
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

private const val WEB_JWT_ISSUER = "rikkahub-web"
private const val WEB_JWT_AUDIENCE = "rikkahub-web-client"
private const val WEB_JWT_SUBJECT = "web-access"
private const val WEB_JWT_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000
private const val WEB_ACCESS_TOKEN_QUERY_KEY = "access_token"
private const val WEB_AUTH_PROVIDER_NAME = "auth-jwt"
private const val WEB_AUTH_CHALLENGE_KEY = "rikkahub-web-api"

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
    // 网段白名单：webServerAllowedNetworks 非空时，仅放行匹配 CIDR 的来源 IP（默认空 = 不限制）
    // 用插件 + 异常终止（而非 intercept），避免 Ktor 3 pipeline 上下文写法差异
    install(
        createApplicationPlugin("NetworkAllowList") {
            onCall { call ->
                val allowedNetworks = settingsStore.settingsFlow.value.webServerAllowedNetworks
                if (!isRemoteHostAllowed(call.request.local.remoteHost, allowedNetworks)) {
                    throw NetworkNotAllowedException(call.request.local.remoteHost)
                }
            }
        },
    )

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
        exception<NetworkNotAllowedException> { call, cause ->
            call.respond(
                HttpStatusCode.Forbidden,
                ErrorResponse(cause.message ?: "Network not allowed", HttpStatusCode.Forbidden.value),
            )
        }
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(cause.message ?: "Internal server error", 500)
            )
        }
    }

    // P0-46：认证不再由「启动时的设置快照」决定装不装。provider 始终注册，路由始终
    // authenticate(WEB_AUTH_PROVIDER_NAME)；「这次请求要不要校验」逐请求现读设置：
    //  - 已加载且关闭 JWT → skipWhen 命中，provider 整个跳过（与关闭态旧行为一致：无 principal）
    //  - 已加载且开启 JWT → 走 authenticate() 校验，口令逐请求现读
    //  - 尚未加载（Settings.dummy / init=true）→ 不跳过，authenticate() 里 fail-closed 拒绝
    install(Authentication) {
        provider(WEB_AUTH_PROVIDER_NAME) {
            skipWhen { _ ->
                val settings = settingsStore.settingsFlow.value
                !settings.init && !settings.webServerJwtEnabled
            }
            authenticate { authContext ->
                val settings = settingsStore.settingsFlow.value
                if (settings.init) {
                    // 冷启动窗口：读到 dummy 值不等于「关闭」，一律拒绝直到设置真正加载完成
                    authContext.denyWebAuth(
                        AuthenticationFailedCause.NoCredentials,
                        HttpStatusCode.ServiceUnavailable,
                        "Web settings are not loaded",
                    )
                    return@authenticate
                }

                // 支持 `$$凭证名` 引用；逐请求现读，改口令后旧 token 立即失效
                val currentPassword =
                    me.rerere.rikkahub.data.vault.VaultProviderKeyRefs.resolveOrNull(settings.webServerAccessPassword)
                        ?: ""
                if (currentPassword.isBlank()) {
                    authContext.denyWebAuth(
                        AuthenticationFailedCause.InvalidCredentials,
                        HttpStatusCode.Forbidden,
                        "Access password is not configured",
                    )
                    return@authenticate
                }

                val call = authContext.call
                val token = extractAccessToken(
                    authorizationHeader = call.request.headers[HttpHeaders.Authorization],
                    queryToken = call.request.queryParameters[WEB_ACCESS_TOKEN_QUERY_KEY],
                )
                if (token == null) {
                    authContext.denyWebAuth(
                        AuthenticationFailedCause.NoCredentials,
                        HttpStatusCode.Unauthorized,
                        "Unauthorized",
                    )
                    return@authenticate
                }

                val jwtPrincipal = runCatching { buildWebJwtVerifier(currentPassword).verify(token) }
                    .getOrNull()
                    ?.takeIf { it.subject == WEB_JWT_SUBJECT }
                    ?.let { JWTPrincipal(it) }

                if (jwtPrincipal == null) {
                    authContext.denyWebAuth(
                        AuthenticationFailedCause.InvalidCredentials,
                        HttpStatusCode.Unauthorized,
                        "Unauthorized",
                    )
                } else {
                    authContext.principal(WEB_AUTH_PROVIDER_NAME, jwtPrincipal)
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

                // 支持 `$$凭证名` 引用；未命中不再降级为字面量——否则条目名即口令
                val accessPassword =
                    me.rerere.rikkahub.data.vault.VaultProviderKeyRefs.resolveOrNull(settings.webServerAccessPassword) ?: ""
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

            // P0-46：结构上只注册一次；开关为关时由 provider 的 skipWhen 逐请求放行
            authenticate(WEB_AUTH_PROVIDER_NAME) {
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

/**
 * 登记一次认证拒绝：状态码与响应体沿用原 `jwt { challenge { … } }` 的形状
 * （ErrorResponse，不带 WWW-Authenticate）。challenge 函数由 Ktor 在「路由层级没有任何
 * provider 给出 principal」时才执行，因此这里只登记，不直接 respond。
 */
private fun AuthenticationContext.denyWebAuth(
    cause: AuthenticationFailedCause,
    status: HttpStatusCode,
    message: String,
) {
    challenge(WEB_AUTH_CHALLENGE_KEY, cause) { challenge, call ->
        call.respond(status, ErrorResponse(message, status.value))
        challenge.complete()
    }
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

/** 来源 IP 不在允许网段内时抛出，由 StatusPages 映射为 403 */
class NetworkNotAllowedException(remoteHost: String) :
    RuntimeException("Network not allowed: $remoteHost")
