package me.rerere.rikkahub.ui.pages.setting

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.data.quota.QuotaAuthType
import me.rerere.rikkahub.data.quota.QuotaCredentialManager
import me.rerere.rikkahub.data.quota.QuotaParser
import me.rerere.rikkahub.data.quota.QuotaPreferences
import me.rerere.rikkahub.data.quota.QuotaProviderConfig
import me.rerere.rikkahub.data.quota.QuotaSnapshotHolder
import me.rerere.rikkahub.data.quota.QuotaSnapshot
import me.rerere.rikkahub.data.quota.QuotaStatus
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuotaConsolePage(providerId: String) {
    val context = LocalContext.current
    val quotaPreferences: QuotaPreferences = koinInject()
    val credentialManager: QuotaCredentialManager = koinInject()
    val providers by quotaPreferences.providers.collectAsState(initial = emptyList())
    val provider = providers.find { it.id == providerId }
    val scope = rememberCoroutineScope()

    if (provider == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Provider not found") },
                    navigationIcon = { BackButton() },
                )
            },
        ) {
            Text(
                "Provider not found",
                modifier = Modifier.padding(it),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        return
    }

    var snapshot by remember { mutableStateOf<QuotaSnapshot?>(null) }
    var lastError by remember { mutableStateOf<String?>(null) }
    var credentialGrabbed by remember { mutableStateOf(provider.credential != null) }

    val webViewState =
        rememberWebViewState(
            url = provider.consoleUrl,
            settings = {
                javaScriptEnabled = true
                domStorageEnabled = true
                builtInZoomControls = true
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
                // 允许第三方 cookie（部分平台跨域认证需要）
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
            },
        )

    // 监听悬浮窗「刷新额度」事件 → 重新解析
    val appEventBus: me.rerere.rikkahub.data.event.AppEventBus = koinInject()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        appEventBus.events.collect { event ->
            if (event is me.rerere.rikkahub.data.event.AppEvent.QuotaRefreshRequested) {
                webViewState.webView?.let { webView ->
                    lastError = null
                    parseQuota(webView, provider) { snap ->
                        snapshot = snap
                        QuotaSnapshotHolder.addSnapshot(snap)
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(provider.label.ifBlank { "Console" }) },
                navigationIcon = { BackButton() },
                actions = {
                    if (!credentialGrabbed) {
                        IconButton(onClick = {
                            scope.launch {
                                // 尝试从 CookieManager 捕获 Cookie
                                val cm = CookieManager.getInstance()
                                val url = provider.consoleUrl
                                val cookie = cm.getCookie(url)
                                if (!cookie.isNullOrBlank()) {
                                    credentialManager.captureAndSave(
                                        providerId = provider.id,
                                        authType = QuotaAuthType.COOKIE,
                                        rawValue = cookie,
                                        keyName = "Cookie",
                                    )
                                    credentialGrabbed = true
                                }
                            }
                        }) {
                            Icon(HugeIcons.Key01, contentDescription = "捕获凭证")
                        }
                    }
                    IconButton(onClick = { webViewState.reload() }) {
                        Icon(HugeIcons.Refresh01, contentDescription = "Reload")
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
        ) {
            // WebView 区域
            WebView(
                state = webViewState,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                onCreated = { webView ->
                    // 注入凭证
                    injectCredentials(webView, provider, scope, credentialManager)

                    // 页面加载完成 → 自动解析 + 自动捕获凭证
                    webView.webViewClient =
                        object : android.webkit.WebViewClient() {
                            override fun onPageFinished(
                                view: WebView?,
                                url: String?,
                            ) {
                                super.onPageFinished(view, url)
                                view?.let {
                                    parseQuota(it, provider) { snap ->
                                        snapshot = snap
                                        QuotaSnapshotHolder.addSnapshot(snap)
                                    }
                                }

                                // 首次登录成功后自动捕获 Cookie
                                if (!credentialGrabbed && url?.contains("login") != true) {
                                    scope.launch {
                                        tryCaptureCookie(
                                            webView,
                                            provider,
                                            credentialManager,
                                        ) { credentialGrabbed = true }
                                    }
                                }
                            }
                        }
                },
            )

            // 凭证状态提示条
            if (credentialGrabbed) {
                Text(
                    text = "✓ 凭证已保存，下次自动登录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            } else if (provider.authType != QuotaAuthType.NONE) {
                Text(
                    text = "请在 WebView 中登录平台，然后点击 🔑 捕获凭证",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            // 解析结果卡片
            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "解析结果",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = {
                                webViewState.webView?.let { webView ->
                                    lastError = null
                                    parseQuota(webView, provider) { snap ->
                                        snapshot = snap
                                        QuotaSnapshotHolder.addSnapshot(snap)
                                    }
                                }
                            },
                        ) {
                            Text("手动解析")
                        }
                    }

                    snapshot?.let { snap ->
                        val statusColor =
                            when (snap.status) {
                                QuotaStatus.GREEN -> {
                                    MaterialTheme.colorScheme.primary
                                }

                                QuotaStatus.YELLOW -> {
                                    androidx.compose.ui.graphics
                                        .Color(0xFFEAB308)
                                }

                                QuotaStatus.RED -> {
                                    MaterialTheme.colorScheme.error
                                }

                                QuotaStatus.UNKNOWN -> {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            }
                        Column(modifier = Modifier.padding(top = 12.dp)) {
                            Row {
                                Text("原始文本: ", fontWeight = FontWeight.Bold)
                                Text(
                                    snap.rawText.ifBlank { "(empty)" },
                                    fontFamily = JetbrainsMono,
                                )
                            }
                            Row {
                                Text("数值: ", fontWeight = FontWeight.Bold)
                                Text("%.2f".format(snap.numericValue), fontFamily = JetbrainsMono)
                            }
                            Row {
                                Text("百分比: ", fontWeight = FontWeight.Bold)
                                Text(
                                    "%.1f%%".format(snap.percentage),
                                    fontFamily = JetbrainsMono,
                                    color = statusColor,
                                )
                            }
                            Row {
                                Text("状态: ", fontWeight = FontWeight.Bold)
                                Text(
                                    when (snap.status) {
                                        QuotaStatus.GREEN -> "🟢 充足"
                                        QuotaStatus.YELLOW -> "🟡 紧张"
                                        QuotaStatus.RED -> "🔴 危险"
                                        QuotaStatus.UNKNOWN -> "⚫ 未知"
                                    },
                                    color = statusColor,
                                )
                            }
                        }
                    } ?: lastError?.let {
                        Text(
                            text = "解析失败: $it",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 注入已保存凭证到 WebView。
 */
private fun injectCredentials(
    webView: android.webkit.WebView,
    provider: QuotaProviderConfig,
    scope: kotlinx.coroutines.CoroutineScope,
    credentialManager: QuotaCredentialManager,
) {
    when (provider.authType) {
        QuotaAuthType.COOKIE -> {
            scope.launch {
                val cookie = credentialManager.getDecryptedValue(provider.id)
                if (cookie.isNotBlank()) {
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    // 按分号拆分并逐条注入
                    cookie.split(";").forEach { entry ->
                        cm.setCookie(provider.consoleUrl, entry.trim())
                    }
                    cm.flush()
                }
            }
        }

        QuotaAuthType.BEARER,
        QuotaAuthType.BASIC,
        QuotaAuthType.CUSTOM_HEADER,
        -> {
            // 手动凭证模式：通过拦截请求注入 header
            val authValue = provider.manualAuthValue
            val keyName =
                provider.manualAuthKeyName.ifBlank {
                    when (provider.authType) {
                        QuotaAuthType.BEARER -> "Authorization"
                        QuotaAuthType.BASIC -> "Authorization"
                        else -> "X-API-Key"
                    }
                }
            if (authValue.isNotBlank()) {
                val headerValue =
                    when (provider.authType) {
                        QuotaAuthType.BEARER -> {
                            "Bearer $authValue"
                        }

                        QuotaAuthType.BASIC -> {
                            val user = provider.manualAuthUsername
                            val basic =
                                android.util.Base64.encodeToString(
                                    "$user:$authValue".toByteArray(),
                                    android.util.Base64.NO_WRAP,
                                )
                            "Basic $basic"
                        }

                        else -> {
                            authValue
                        }
                    }
                // 通过 WebView 额外 headers 注入（loadUrl 时生效）
                val extraHeaders = mapOf(keyName to headerValue)
                webView.loadUrl(provider.consoleUrl, extraHeaders)
            }
        }

        QuotaAuthType.QUERY_PARAM -> {
            // Query 参数模式：拼接 ?key=value
            val keyName = provider.manualAuthKeyName.ifBlank { "api_key" }
            val authValue = provider.manualAuthValue
            if (authValue.isNotBlank()) {
                val separator = if (provider.consoleUrl.contains("?")) "&" else "?"
                webView.loadUrl("${provider.consoleUrl}$separator$keyName=$authValue")
            }
        }

        QuotaAuthType.NONE -> { /* 无鉴权 */ }
    }
}

/**
 * 从 CookieManager 捕获登录后的 Cookie 并加密保存。
 */
private suspend fun tryCaptureCookie(
    webView: android.webkit.WebView,
    provider: QuotaProviderConfig,
    credentialManager: QuotaCredentialManager,
    onCaptured: () -> Unit,
) {
    val cm = CookieManager.getInstance()
    val cookie = cm.getCookie(provider.consoleUrl)
    if (!cookie.isNullOrBlank()) {
        credentialManager.captureAndSave(
            providerId = provider.id,
            authType = QuotaAuthType.COOKIE,
            rawValue = cookie,
            keyName = "Cookie",
        )
        onCaptured()
    }
}

private fun parseQuota(
    webView: android.webkit.WebView,
    config: QuotaProviderConfig,
    onResult: (QuotaSnapshot) -> Unit,
) {
    QuotaParser.evaluate(webView, config, onResult)
}
