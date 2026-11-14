package me.rerere.rikkahub.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Environment
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import java.io.File
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.data.files.FileFolders

/**
 * Top-level Compose root for [BrowserActivity]. Lays out:
 *   - [BrowserAddressBar] (top — read-only URL + nav controls)
 *   - [WebView] embedded via AndroidView (middle — fills weight=1f)
 *   - [BrowserAiStripe] (bottom — AI status, expandable to recent actions list)
 *
 * The WebView is created exactly once via `remember { WebView(ctx).apply { … } }` and
 * reused across recompositions — recreating it on every recomp would lose page state
 * and re-fetch the URL. Same pattern as Phase 19's WebViewPage.
 */
@OptIn(ExperimentalUuidApi::class)
@Composable
fun BrowserView(
    onWebViewReady: (WebView) -> Unit,
    onUrlChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onLoadProgress: (Int) -> Unit,
    onCanGoBackChange: (Boolean) -> Unit,
    onCanGoForwardChange: (Boolean) -> Unit,
    canGoBackState: MutableState<Boolean>,
    canGoForwardState: MutableState<Boolean>,
    currentUrlState: MutableState<String>,
    currentTitleState: MutableState<String>,
    loadProgressState: MutableState<Int>,
    onClose: () -> Unit,
    onBackTap: () -> Unit,
    onForwardTap: () -> Unit,
    onRefreshTap: () -> Unit,
    onStopAi: () -> Unit,
    onShowFileChooser: (ValueCallback<Array<Uri>>, String) -> Boolean,
    onNavigate: (String) -> Unit,
    initialUrl: String,
    conversationId: Uuid?,
) {
    Scaffold(
        topBar = {
            BrowserAddressBar(
                url = currentUrlState.value,
                canGoBack = canGoBackState.value,
                canGoForward = canGoForwardState.value,
                onClose = onClose,
                onBack = onBackTap,
                onForward = onForwardTap,
                onRefresh = onRefreshTap,
                onStopAi = onStopAi,
                onNavigate = onNavigate,
            )
        },
        bottomBar = {
            BrowserAiStripe()
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (loadProgressState.value in 1..99) {
                    LinearProgressIndicator(
                        progress = { loadProgressState.value / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                WebViewHost(
                    modifier = Modifier.fillMaxSize(),
                    initialUrl = initialUrl,
                    onWebViewReady = onWebViewReady,
                    onUrlChange = onUrlChange,
                    onTitleChange = onTitleChange,
                    onLoadProgress = onLoadProgress,
                    onCanGoBackChange = onCanGoBackChange,
                    onCanGoForwardChange = onCanGoForwardChange,
                    onShowFileChooser = onShowFileChooser,
                )
            }
            // Bottom-anchored mini-chat overlay. Self-hides when conversationId is null
            // (manual launch from Settings without a chat context).
            BrowserMiniChat(
                conversationId = conversationId,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 8.dp),
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebViewHost(
    modifier: Modifier = Modifier,
    initialUrl: String,
    onWebViewReady: (WebView) -> Unit,
    onUrlChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onLoadProgress: (Int) -> Unit,
    onCanGoBackChange: (Boolean) -> Unit,
    onCanGoForwardChange: (Boolean) -> Unit,
    onShowFileChooser: (ValueCallback<Array<Uri>>, String) -> Boolean,
) {
    val ctx = LocalContext.current
    // 待用户确认的 SSL 证书错误。WebViewClient 回调里只负责把它记下来，真正弹
    // 对话框的是下面的 Compose AlertDialog——回调里直接弹系统对话框拿不到
    // Compose 的主题，也容易在重组时丢状态。
    val sslErrorState = remember { mutableStateOf<SslErrorRequest?>(null) }
    // Error-page copy resolved in the Composable scope and handed to the
    // WebViewClient via rememberUpdatedState: the client callbacks are not
    // Composable, so they cannot call stringResource themselves, and reading
    // them through context.getString there would go stale on config change.
    // The State object is stable; callbacks read .value at error time, so a
    // locale change that recomposes this scope is picked up by the next error.
    val errorPageStringsState = rememberUpdatedState(
        ErrorPageStrings(
            title = stringResource(R.string.browser_error_page_title),
            cannotOpenTemplate = stringResource(R.string.browser_error_page_cannot_open),
            reasonTemplate = stringResource(R.string.browser_error_page_reason),
            reload = stringResource(R.string.browser_error_page_reload),
            errorCodeTemplate = stringResource(R.string.browser_error_code),
        ),
    )
    // Construct the WebView ONCE — `remember` survives recomp + (with key=Unit) survives
    // configuration changes (the Activity declares `configChanges` so the system never
    // recreates us). Recreating per-recomp would reset history, scroll, and JS state.
    val webView = remember {
        // Enable Chrome DevTools attachment ONLY in debug builds. In release, leaving
        // this on lets anyone with adb (lent phone, ADB-over-WiFi attacker) attach
        // chrome://inspect and read the WebView's cookies / localStorage / authenticated
        // session bodies. Gate behind BuildConfig.DEBUG — turning Chrome inspection on
        // in release is a privacy posture choice the user never consented to.
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        WebView(ctx).apply {
            // WebView derives its CSS viewport height (100vh/100dvh/percentage heights)
            // from its LayoutParams, not from its actual measured/laid-out size. Without
            // this, the default WRAP_CONTENT makes vh/% heights resolve to 0 even though
            // Compose's AndroidView correctly sizes the view on screen (measured on
            // device: innerHeight=774 but 100vh=0). HeadlessBrowserSession already sets
            // its own fixed-size LayoutParams for the same reason; MATCH_PARENT here lets
            // the real Compose-driven size flow through instead.
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )

            // Shared with HeadlessBrowserSession — every render-related setting
            // (mixedContentMode, hardware layer, autoplay, UA strip, file:// access)
            // lives in configureWebViewForRikka so foreground + headless behave
            // identically. See BrowserWebViewConfig.kt for the why.
            configureWebViewForRikka(this)

            // 下载：没设 DownloadListener 时点下载链接会静默失败。交给系统
            // DownloadManager 发起下载（通知栏可见、保存到公开 Downloads 目录），
            // 不自己写文件 IO。
            setDownloadListener { url, _, contentDisposition, mimetype, _ ->
                // 只接受 http(s) 下载：下载 URL 由页面控制，可能混进 file://、
                // intent://、javascript: 等 scheme，交给 DownloadManager 既无
                // 意义也可能被利用来探测本地路径；非 http(s) 直接丢弃，只记
                // 一条 WARN，不弹任何提示。
                if (!isHttpOrHttpsUrl(url)) {
                    AppLog.w("RikkaWebView", "download dropped: non-http(s) scheme")
                    return@setDownloadListener
                }
                val fileName = sanitizeDownloadFileName(
                    URLUtil.guessFileName(url, contentDisposition, mimetype),
                )
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    // 下载走系统 DownloadManager，与 WebView 不是同一个 HTTP
                    // 栈：不带 UA 和 Cookie 时需要登录态的资源会 401，站方按
                    // UA 分流的也会拿到错误版本。把当前 WebView 的 UA 与该
                    // URL 的 Cookie 带上。
                    addRequestHeader("User-Agent", settings.userAgentString)
                    CookieManager.getInstance().getCookie(url)?.let { cookie ->
                        addRequestHeader("Cookie", cookie)
                    }
                }
                val dm = ctx.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as? DownloadManager
                dm?.enqueue(request)
                AppLog.i("RikkaWebView", "download started: $url -> Downloads/$fileName")
            }

            // Profile dir is informational — the global WebView databases live where the
            // WebView wants. We create the dir ourselves in BrowserActivity.onCreate so
            // Pass 3's "Clear browsing data" has a stable target to wipe.
            File(ctx.filesDir, "browser-profile").apply { if (!exists()) mkdirs() }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    // Block page/JS-initiated navigation into file:// unless the current
                    // document is already file:// (skill webview cards moving between
                    // their own sub-pages). App-initiated loadUrl() bypasses this
                    // callback, so opening a skill card stays unaffected. Without this,
                    // a browsed page (or eval'd JS) could steer the WebView into
                    // app-private files and exfiltrate them via browser_get_text.
                    val toFile = request?.url?.scheme.equals("file", ignoreCase = true)
                    return toFile && view?.url?.startsWith("file:", ignoreCase = true) != true
                }

                /**
                 * Egress audit only — the response is never altered: returning null hands the
                 * request back to the default handler. Non-GET requests are the ones that can
                 * carry page data outward (form POST, fetch/XHR with a body); GET navigations
                 * and sub-resources are the norm and would flood the log. The request body is
                 * not observable from WebResourceRequest, so BrowserAudit records
                 * `body=unknown` instead of inventing one.
                 */
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    BrowserAudit.maybeRecordRequest(request, origin = "foreground")
                    // 资源层白名单：只放行技能目录内的 file://（详见 isLocalFileRequestAllowed 注释）
                    if (!isLocalFileRequestAllowed(request?.url, File(ctx.filesDir, FileFolders.SKILLS))) {
                        return localFileForbiddenResponse(request?.url)
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (url != null) onUrlChange(url)
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url != null) onUrlChange(url)
                    onCanGoBackChange(view?.canGoBack() == true)
                    onCanGoForwardChange(view?.canGoForward() == true)
                    // Adb-friendly white-page diagnostic. Tag = "RikkaWebView". Filter:
                    //   adb logcat -s RikkaWebView
                    // Dumps body innerText length + first 100 chars + meta viewport so
                    // you can tell at a glance whether the DOM is populated (render bug)
                    // or empty (load bug) without needing chrome://inspect.
                    view?.evaluateJavascript(
                        """
                        JSON.stringify({
                          rs: document.readyState,
                          docLen: (document.documentElement ? document.documentElement.outerHTML.length : 0),
                          bodyLen: (document.body ? document.body.innerText.length : 0),
                          children: (document.body ? document.body.children.length : 0),
                          firstText: (document.body ? document.body.innerText.slice(0, 100) : ''),
                          viewport: (function(){var m=document.querySelector('meta[name=viewport]');return m?m.content:'';})()
                        })
                        """.trimIndent(),
                    ) { json ->
                        AppLog.d("RikkaWebView", "onPageFinished $url -> $json")
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    super.onReceivedError(view, request, error)
                    val isMainFrame = request?.isForMainFrame == true
                    AppLog.w(
                        "RikkaWebView",
                        "onReceivedError mainFrame=$isMainFrame url=${request?.url} code=${error?.errorCode} desc=${error?.description}",
                    )
                    // 主页面加载失败（断网、DNS、超时）时给一张错误页，写明失败原因
                    // 和当前 URL，附重新加载按钮——之前只记日志、页面留白，用户不知道
                    // 发生了什么。子 frame（图片、脚本）失败不处理，只记日志，
                    // 否则一个坏资源就会把整页换成错误页。
                    if (isMainFrame) {
                        // 进入 if 时编译器已把 request 智能转为非空（isMainFrame 由
                        // request?.isForMainFrame 推导），这里直接用 request.url；
                        // 日志行在 if 外、request 仍可空，那里保持 request?.url。
                        val failedUrl = request.url.toString().orEmpty()
                        val reason = error?.description?.toString().orEmpty()
                            .ifBlank {
                                errorPageStringsState.value.errorCodeTemplate.format(
                                    error?.errorCode ?: 0,
                                )
                            }
                        showLoadErrorPage(
                            view,
                            failedUrl,
                            reason,
                            errorPageStringsState.value,
                        )
                    }
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?,
                ) {
                    super.onReceivedSslError(view, handler, error)
                    AppLog.w(
                        "RikkaWebView",
                        "onReceivedSslError url=${error?.url} primaryError=${error?.primaryError}",
                    )
                    if (handler == null || error == null) return
                    // 绝对不静默 proceed：自签证书在局域网/自建服务上常见，但放行与否
                    // 必须用户当面决定。这里只把请求记下来，由下面的 AlertDialog
                    // 让用户点「继续访问」才 proceed，点取消或关掉对话框则 cancel。
                    // 同一时间只弹一个证书对话框，避免多个 handler 堆叠泄漏；
                    // 后来的直接取消，不静默放行。
                    if (sslErrorState.value != null) {
                        handler.cancel()
                        return
                    }
                    sslErrorState.value = SslErrorRequest(handler, error)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    onLoadProgress(newProgress)
                }
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    if (title != null) onTitleChange(title)
                }
                override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                    // Surface page-side console.log / console.error to logcat so the user
                    // can debug white pages from a terminal without DevTools.
                    if (message != null) {
                        val priority = when (message.messageLevel()) {
                            ConsoleMessage.MessageLevel.ERROR -> Log.ERROR
                            ConsoleMessage.MessageLevel.WARNING -> Log.WARN
                            ConsoleMessage.MessageLevel.DEBUG -> Log.DEBUG
                            else -> Log.INFO
                        }
                        Log.println(
                            priority,
                            "RikkaWebViewConsole",
                            "${message.sourceId()}:${message.lineNumber()} ${message.message()}",
                        )
                    }
                    return true
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?,
                ): Boolean {
                    // 页面里的 <input type="file">：没实现这个回调时点了没反应。
                    // accept 属性（如 image/*）取第一个传给系统选择器，空则通配。
                    // 选择器由 BrowserActivity 用 Activity Result API 调起，结果回传给
                    // filePathCallback；用户取消时 Activity 那边回传 null。
                    if (filePathCallback == null) return false
                    val mimeType = fileChooserParams?.acceptTypes
                        ?.firstOrNull()?.takeIf { it.isNotBlank() } ?: "*/*"
                    return onShowFileChooser(filePathCallback, mimeType)
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    // 一律拒绝，不静默授权。内置浏览器常由 AI 自动驱动访问第三
                    // 方页面，页面请求的摄像头/麦克风绝不能在用户不知情时放行；
                    // 需要音视频的页面请在系统浏览器里打开。注意这只是 WebView
                    // 层的拒绝，系统层权限不受影响。
                    request?.deny()
                }

                override fun onGeolocationPermissionsShowPrompt(
                    origin: String?,
                    callback: GeolocationPermissions.Callback?,
                ) {
                    // 定位一律拒绝（第二个参数 false = 不授权，第三个 false =
                    // 不记住）。内置浏览器常由 AI 自动驱动访问第三方页面，位置
                    // 是敏感信息，绝不能在用户不知情时静默放行。
                    callback?.invoke(origin, false, false)
                }

                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message?,
                ): Boolean {
                    // target="_blank" / window.open()：没实现这个回调时点了没反应。
                    // 新建一个临时 WebView 接住这次打开，等它开始加载拿到真实 URL 后
                    // 载入主 WebView、销毁临时 WebView——不会真的多开一个窗口。
                    val mainWebView = view ?: return false
                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                    val tempWebView = WebView(mainWebView.context).apply {
                        // 临时 WebView 也要走同一套渲染设置，否则新窗口页可能白屏。
                        configureWebViewForRikka(this)
                        webViewClient = object : WebViewClient() {
                            // 是否已经把这次打开处理完：onPageStarted 和
                            // shouldOverrideUrlLoading 都可能先触发，只处理一次。
                            var handled = false

                            // 把新窗口的 URL 交给主 WebView 并销毁临时 WebView。
                            // about:blank 弹窗（JS 生成的空窗口）没有真实 URL，
                            // 只销毁临时 WebView、不导航主 WebView，避免把当前页顶成空白。
                            // 返回 true 表示这次打开已处理。
                            private fun handToMain(url: String, tempView: WebView?): Boolean {
                                if (handled) return true
                                handled = true
                                // 只把 http(s) 交给主 WebView：window.open() 的 URL
                                // 由页面控制，file://、intent://、javascript:、
                                // data: 等 scheme 一旦载入主 WebView，轻则顶掉当前
                                // 页，重则触发外部应用或读取本地文件；about:blank
                                // 弹窗本来就没有真实 URL，同样不导航。
                                if (isHandToMainUrlAllowed(url)) {
                                    mainWebView.loadUrl(url)
                                } else {
                                    AppLog.d("RikkaWebView", "handToMain dropped non-http(s) url")
                                }
                                // 销毁推迟到回调结束之后，避免在 WebView 自身回调里
                                // 同步 destroy 撞上正在进行的导航。
                                tempView?.post { tempView.destroy() }
                                return true
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val url = request?.url?.toString() ?: return false
                                return handToMain(url, view)
                            }

                            override fun onPageStarted(
                                view: WebView?,
                                url: String?,
                                favicon: Bitmap?,
                            ) {
                                super.onPageStarted(view, url, favicon)
                                if (url == null) return
                                handToMain(url, view)
                            }
                        }
                    }
                    transport.webView = tempWebView
                    resultMsg.sendToTarget()
                    return true
                }
            }

            loadUrl(initialUrl)
            onWebViewReady(this)
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { webView },
        )
        // SSL 证书确认对话框：用户点「继续访问」才 proceed，点取消或关掉
        // 对话框则 cancel，绝对不静默放行，也不记「总是信任」。
        sslErrorState.value?.let { req ->
            AlertDialog(
                onDismissRequest = {
                    req.handler.cancel()
                    sslErrorState.value = null
                },
                title = { Text(stringResource(R.string.browser_ssl_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.browser_ssl_body,
                            stringResource(sslErrorReasonRes(req.error.primaryError)),
                            req.error.url,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        req.handler.proceed()
                        sslErrorState.value = null
                    }) { Text(stringResource(R.string.browser_ssl_continue)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        req.handler.cancel()
                        sslErrorState.value = null
                    }) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
    }
}

/** 待用户确认的 SSL 证书错误：handler 与 error 成对保存，确认后 proceed、取消后 cancel。 */
private data class SslErrorRequest(val handler: SslErrorHandler, val error: SslError)

/** `SslError.primaryError` 对应的原因字符串资源。 */
private fun sslErrorReasonRes(primaryError: Int): Int = when (primaryError) {
    SslError.SSL_NOTYETVALID -> R.string.browser_ssl_reason_not_yet_valid
    SslError.SSL_EXPIRED -> R.string.browser_ssl_reason_expired
    SslError.SSL_IDMISMATCH -> R.string.browser_ssl_reason_mismatch
    SslError.SSL_UNTRUSTED -> R.string.browser_ssl_reason_untrusted
    SslError.SSL_DATE_INVALID -> R.string.browser_ssl_reason_date_invalid
    else -> R.string.browser_ssl_reason_invalid
}

/** HTML 转义：错误页里要原样显示 URL 与失败原因，不能把页面可控内容当标记渲染。 */
private fun escapeHtml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/**
 * 主页面加载失败时的错误页：写明失败原因和当前 URL，附「重新加载」按钮。
 * 用 `loadDataWithBaseURL` 以失败 URL 为基准载入——文档地址就是原 URL，
 * 点「重新加载」会重新请求原页面，不是重载这张错误页本身。
 * 文案由调用方在 Composable 作用域取好传入；`view` 为空时无法加载，直接返回。
 */
private data class ErrorPageStrings(
    val title: String,
    val cannotOpenTemplate: String,
    val reasonTemplate: String,
    val reload: String,
    val errorCodeTemplate: String,
)

private fun showLoadErrorPage(
    view: WebView?,
    failedUrl: String,
    reason: String,
    strings: ErrorPageStrings,
) {
    view ?: return
    val safeUrl = escapeHtml(failedUrl)
    val safeReason = escapeHtml(reason)
    val html =
        "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<title>${strings.title}</title></head>" +
            "<body style=\"font:16px/1.6 sans-serif;padding:24px 16px;color:#222\">" +
            "<h2>${strings.title}</h2>" +
            "<p>${strings.cannotOpenTemplate.format(safeUrl)}</p>" +
            "<p>${strings.reasonTemplate.format(safeReason)}</p>" +
            "<button onclick=\"window.location.reload()\" " +
            "style=\"font-size:16px;padding:10px 20px\">${strings.reload}</button>" +
            "</body></html>"
    view.loadDataWithBaseURL(failedUrl, html, "text/html", "UTF-8", null)
}

private val URL_SCHEME_REGEX = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

/** 下载文件名按 UTF-8 字节数封顶（ext4/f2fs 单文件名 255 字节，留余量）。 */
private const val MAX_DOWNLOAD_FILE_NAME_BYTES = 200

/** 超长文件名截断时保留的扩展名最长字符数。 */
private const val MAX_DOWNLOAD_FILE_EXTENSION_LENGTH = 10

/**
 * 下载与新窗口共用的 scheme 门：只放行 http/https。页面可控的 URL 里
 * 可能混进 file://、intent://、javascript:、data: 等 scheme，交给
 * DownloadManager 既无意义也可能被利用来探测本地路径；载入主 WebView
 * 则可能顶掉当前页或触发外部应用。纯字符串解析，不依赖 android.net.Uri，
 * 可在纯 JVM 单测里跑。
 */
internal fun isHttpOrHttpsUrl(url: String): Boolean {
    val scheme = URL_SCHEME_REGEX.find(url.trim())?.groupValues?.get(1)?.lowercase()
    return scheme == "http" || scheme == "https"
}

/**
 * 新窗口交给主 WebView 的放行条件：非空、不是 about:blank、且 scheme 是
 * http/https（见 [isHttpOrHttpsUrl]）。
 */
internal fun isHandToMainUrlAllowed(url: String): Boolean {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return false
    if (trimmed.equals("about:blank", ignoreCase = true)) return false
    return isHttpOrHttpsUrl(trimmed)
}

/**
 * 清理下载文件名：`URLUtil.guessFileName` 的结果来自页面可控的 URL 与
 * Content-Disposition，可能带目录穿越（`../../evil.apk`）或非法字符，
 * 直接交给 `setDestinationInExternalPublicDir` 会写出 Downloads 目录。
 * 只保留最后一段路径、替换非法字符、超长时按字节截断并保留扩展名，
 * 拿不到合法名字时回退到 `download`。
 */
internal fun sanitizeDownloadFileName(raw: String): String {
    val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
    val cleaned = base.map { ch ->
        if (ch.code < 0x20 || ch in "\"*:<>?|") '_' else ch
    }.joinToString("").trim(' ', '.')
    if (cleaned.isEmpty()) return "download"
    if (cleaned.toByteArray(Charsets.UTF_8).size <= MAX_DOWNLOAD_FILE_NAME_BYTES) return cleaned
    val dot = cleaned.lastIndexOf('.')
    val ext = if (dot > 0 && cleaned.length - dot <= MAX_DOWNLOAD_FILE_EXTENSION_LENGTH) {
        cleaned.substring(dot)
    } else {
        ""
    }
    val stem = cleaned.dropLast(ext.length)
    var end = stem.length
    while (end > 0) {
        val candidate = stem.take(end) + ext
        if (candidate.toByteArray(Charsets.UTF_8).size <= MAX_DOWNLOAD_FILE_NAME_BYTES) return candidate
        end--
    }
    return "download"
}
