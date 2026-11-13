package me.rerere.rikkahub.data.vault

import me.rerere.rikkahub.data.db.entity.VaultCredentialEntity

/**
 * 凭证一次使用的用途（P0-2：目的地绑定 + 类型校验的检查点）。
 *
 * 用途决定本次调用期望的凭证类型——三种不可混用：SSH 私钥不能当口令、
 * 口令不能当私钥、API Key 不能当 SSH 口令（反之亦然）。
 */
enum class CredentialUse(
    /** 用途描述（进拒绝文案）。 */
    val label: String,
    /** 本次调用可接受的凭证类型（[CredentialType] 取值）。 */
    val acceptedTypes: Set<String>,
) {
    /** vault_ssh_exec auth=key：SSH 私钥握手。 */
    SSH_KEY("SSH 密钥认证", setOf(CredentialType.SSH_KEY)),

    /** vault_ssh_exec auth=password：SSH 口令认证。 */
    SSH_PASSWORD("SSH 口令认证", setOf(CredentialType.BASIC_AUTH)),

    /** vault_http_exec：凭证值注入 HTTP 请求头。云 AK 的密文与 API Key 同为"头部令牌"形态，一并接受。 */
    HTTP_HEADER("HTTP 请求头注入", setOf(CredentialType.API_KEY, CredentialType.CLOUD_AK)),
}

/** 凭证本次要去的目的地（P0-2：目的地绑定只认这两种形状）。 */
sealed interface CredentialDestination {

    /** SSH 目标：模型传的 host / port / user（逻辑目的地，隧道解析之前）。 */
    data class Ssh(val host: String, val port: Int, val user: String) : CredentialDestination

    /** HTTP 目标：模型传的完整 URL。 */
    data class Http(val url: String) : CredentialDestination
}

/**
 * 凭证目的地绑定 + 类型校验（P0-2，[CredentialResolver.resolve] 的检查点）。
 *
 * 规则：
 * - **目的地绑定**：凭证 `metaJson.endpoint` 为空 = 未绑定，不限制（历史凭证默认）；
 *   绑定后本次请求必须命中，否则拒绝。SSH 的 endpoint 写 `host` / `host:port` /
 *   `user@host` / `user@host:port`（可带 `ssh://` 前缀，多个用逗号分隔）；HTTP 的
 *   endpoint 写接口基址 URL（`https://api.example.com[/v1]`），请求 URL 必须同
 *   scheme、同 host、同端口、且路径在基址路径之下。
 * - **类型校验**：以凭证库 `type` 字段为准；空（历史未分类）时按写入侧同一口径
 *   [CredentialType.infer] 从名字/值/公钥推断。类型与用途不匹配即拒绝——自定义类型
 *   （custom-fields）与 TOTP 种子不属于三种用途中的任何一种，同样拒绝。
 *
 * 返回 null = 通过；返回非 null = 拒绝原因（可直接展示给模型/用户）。
 */
object CredentialBinding {

    fun check(
        entry: VaultCredentialEntity,
        value: String,
        use: CredentialUse,
        destination: CredentialDestination,
    ): String? {
        val meta = CredentialMeta.decode(entry.metaJson)
        val endpoint = meta["endpoint"]?.trim().orEmpty()
        if (endpoint.isNotEmpty() && !destinationMatches(endpoint, destination)) {
            return "该凭证未授权用于此目的地（凭证绑定 endpoint 为 $endpoint，本次请求为 ${destination.describe()}）"
        }
        val actual = effectiveType(entry, value)
        if (actual !in use.acceptedTypes) {
            return "凭证类型与用途不匹配：本次${use.label}需要 ${use.acceptedTypes.joinToString("/")} 类型凭证，" +
                "该凭证类型为 $actual（请在凭证编辑页更正类型，或换一枚对应类型的凭证）"
        }
        return null
    }

    private fun CredentialDestination.describe(): String = when (this) {
        is CredentialDestination.Ssh -> "$host:$port"
        is CredentialDestination.Http -> url.take(200)
    }

    private fun destinationMatches(endpoint: String, destination: CredentialDestination): Boolean =
        when (destination) {
            is CredentialDestination.Ssh ->
                endpoint.split(',').any { sshEndpointMatches(it.trim(), destination.host, destination.port, destination.user) }
            is CredentialDestination.Http -> httpEndpointMatches(endpoint, destination.url)
        }

    /** 生效类型：库里已标的类型为准；空时按写入侧同一口径从名字/值/公钥推断。 */
    internal fun effectiveType(entry: VaultCredentialEntity, value: String): String =
        entry.type.ifBlank { CredentialType.infer(entry.name, value, entry.publicKey) }

    /**
     * SSH endpoint 匹配：`host` / `host:port` / `user@host` / `user@host:port`，
     * 可带 `ssh://` 前缀；endpoint 里没写的维度（端口/用户）不限制。
     * host 不区分大小写；user 区分大小写（Linux 用户名区分大小写）。
     */
    internal fun sshEndpointMatches(endpoint: String, host: String, port: Int, user: String): Boolean {
        var e = endpoint.trim()
        if (e.isEmpty()) return false
        if (e.startsWith("ssh://", ignoreCase = true)) e = e.substring(6)
        val atIdx = e.lastIndexOf('@')
        val expectedUser = if (atIdx >= 0) e.substring(0, atIdx) else null
        val rest = if (atIdx >= 0) e.substring(atIdx + 1) else e
        if (expectedUser != null && expectedUser != user) return false
        val (expectedHost, expectedPort) = parseSshHostPort(rest) ?: return false
        if (!expectedHost.equals(host, ignoreCase = true)) return false
        if (expectedPort != null && expectedPort != port) return false
        return true
    }

    /**
     * 拆 `host[:port]`。`[v6]:port` 括号形态与无括号 IPv6（整体当 host、无端口）都处理；
     * 冒号后非数字 → 整串当 host。
     */
    private fun parseSshHostPort(rest: String): Pair<String, Int?>? {
        if (rest.isEmpty()) return null
        if (rest.startsWith("[")) {
            val close = rest.indexOf(']')
            if (close < 0) return null
            val host = rest.substring(1, close)
            if (host.isEmpty()) return null
            val tail = rest.substring(close + 1)
            if (tail.isEmpty()) return host to null
            if (!tail.startsWith(":")) return null
            val port = tail.substring(1).toIntOrNull() ?: return null
            return host to port
        }
        val colonCount = rest.count { it == ':' }
        if (colonCount == 0) return rest to null
        if (colonCount > 1) return rest to null
        val (host, portText) = rest.split(":")
        if (host.isEmpty()) return null
        val port = portText.toIntOrNull() ?: return rest to null
        return host to port
    }

    /**
     * HTTP endpoint 匹配：同 scheme、同 host（不区分大小写）、同有效端口
     * （显式端口或 scheme 默认端口 443/80）、请求路径在基址路径之下
     * （基址路径为空或 `/` 时不限路径；否则按路径段边界匹配，`/v1` 不命中 `/v10`）。
     * endpoint 不带 scheme 时按 `https://` 补全（本工具除环回外本就只允许 https）。
     */
    internal fun httpEndpointMatches(endpoint: String, url: String): Boolean {
        val base = parseEndpointUrl(endpoint) ?: return false
        val req = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        if (base.host.isNullOrBlank() || req.host.isNullOrBlank()) return false
        if (!base.scheme.equals(req.scheme, ignoreCase = true)) return false
        if (!base.host.equals(req.host, ignoreCase = true)) return false
        if (effectivePort(base) != effectivePort(req)) return false
        val basePath = base.path.trimEnd('/')
        if (basePath.isEmpty()) return true
        val reqPath = req.path
        return reqPath == basePath || reqPath.startsWith("$basePath/")
    }

    private fun parseEndpointUrl(endpoint: String): java.net.URI? {
        val trimmed = endpoint.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        return runCatching { java.net.URI(withScheme) }.getOrNull()
    }

    private fun effectivePort(uri: java.net.URI): Int =
        if (uri.port != -1) uri.port
        else if (uri.scheme.equals("https", ignoreCase = true)) 443
        else 80
}
