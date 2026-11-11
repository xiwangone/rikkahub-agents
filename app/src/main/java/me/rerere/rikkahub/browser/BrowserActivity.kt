package me.rerere.rikkahub.browser

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import java.io.File

/**
 * Visible foreground browser the LLM drives via the 17 browser tools (Pass 2).
 *
 * Pass 1 ships the shell: WebView + address bar + AI stripe. No tools yet — the user
 * can launch via Settings → Browser → "Open browser" (or, in Pass 2+, via the AI's
 * `browser_open` tool) and see an empty about:blank with the Compose chrome.
 *
 * **Lifecycle contract.** This Activity is the sole owner of its WebView. It binds
 * itself into [BrowserController] in onCreate and unbinds in onDestroy. The controller
 * holds only a [java.lang.ref.WeakReference] so the GC can reclaim the WebView even if
 * a stale tool call leaks the reference past finish().
 *
 * **Profile dir.** Cookies + localStorage live in `${filesDir}/browser-profile/` so
 * they survive process death and app updates. The dir is created on first launch.
 * Pass 3 will register an exclusion in `backup_rules.xml` to avoid restoring auth
 * cookies onto a fresh device.
 */
@OptIn(ExperimentalUuidApi::class)
class BrowserActivity : ComponentActivity() {

    private var webView: WebView? = null
    private val canGoBack = mutableStateOf(false)
    private val canGoForward = mutableStateOf(false)
    private val currentUrl = mutableStateOf("about:blank")
    private val currentTitle = mutableStateOf("")
    private val loadProgress = mutableStateOf(0)
    private var conversationId: Uuid? = null

    /**
     * 文件选择：页面 `<input type="file">` 的回传通道。WebView 的
     * `WebChromeClient.onShowFileChooser` 只给一个 `ValueCallback<Array<Uri>>`，
     * 必须由 Activity 用 Activity Result API 调系统选择器、把结果回传给它。
     * 回调只存一份：新的选择请求到来时先把旧的以 null 回传（用户没选完就点了
     * 另一个上传框），避免旧回调永远挂起；Activity 销毁时同样以 null 收尾，
     * 不让 WebView 侧一直等一个永远不会来的结果。
     */
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            val callback = fileChooserCallback
            fileChooserCallback = null
            // 用户取消时系统返回空列表，按约定回传 null（不是空数组）。
            if (uris.isEmpty()) callback?.onReceiveValue(null)
            else callback?.onReceiveValue(uris.toTypedArray())
        }

    /**
     * 供 BrowserView 的 WebChromeClient 调用：调起系统文件选择器。
     * [mimeType] 取自页面的 accept 属性（如 image），空则为通配。
     */
    fun requestFileChooser(callback: ValueCallback<Array<Uri>>, mimeType: String): Boolean {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = callback
        filePickerLauncher.launch(mimeType.ifBlank { "*/*" })
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Optional companion conversation. browser_open passes the calling chat's id so the
        // mini-chat overlay can drive the same chat — user watches the AI work and can
        // queue follow-up prompts without leaving the activity.
        conversationId = intent?.getStringExtra(EXTRA_CONVERSATION_ID)
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }

        // Best-effort profile dir creation. The WebView falls back to its default location if
        // creation fails — never a crash-on-launch risk. Pass 3's "Clear browsing data" will
        // also wipe + recreate this dir.
        val profileDir = File(filesDir, "browser-profile")
        if (!profileDir.exists()) {
            profileDir.mkdirs()
        }

        // Global cookie store config — applies to every WebView in the process. Spec calls
        // for third-party cookies on so the AI can navigate sites that auth across domains.
        CookieManager.getInstance().setAcceptCookie(true)

        // Hardware back: prefer WebView history, fall back to finishing the Activity.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webView
                if (wv != null && wv.canGoBack()) {
                    wv.goBack()
                } else {
                    finish()
                }
            }
        })

        setContent {
            RikkahubTheme {
                BrowserView(
                    onWebViewReady = { wv ->
                        // The Activity itself owns the WebView; stash it for back-press
                        // handling and bind into the global controller for tool dispatch.
                        webView = wv
                        BrowserController.bind(wv)
                        // Third-party cookies must be enabled per-WebView, not on the
                        // CookieManager singleton — the API split is a holdover from Lollipop.
                        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                    },
                    onUrlChange = { currentUrl.value = it },
                    onTitleChange = { currentTitle.value = it },
                    onLoadProgress = { loadProgress.value = it },
                    onCanGoBackChange = { canGoBack.value = it },
                    onCanGoForwardChange = { canGoForward.value = it },
                    canGoBackState = canGoBack,
                    canGoForwardState = canGoForward,
                    currentUrlState = currentUrl,
                    currentTitleState = currentTitle,
                    loadProgressState = loadProgress,
                    onClose = { finish() },
                    onBackTap = { webView?.takeIf { it.canGoBack() }?.goBack() },
                    onForwardTap = { webView?.takeIf { it.canGoForward() }?.goForward() },
                    onRefreshTap = { webView?.reload() },
                    onStopAi = { BrowserController.stopCurrentTask() },
                    onShowFileChooser = { callback, mimeType ->
                        requestFileChooser(callback, mimeType)
                    },
                    onNavigate = { raw ->
                        // 搜索引擎取运行时同步值，随 Settings 里的选择变化（默认 Bing）。
                        webView?.loadUrl(normalizeBrowserQuery(raw, BrowserController.searchEngine))
                    },
                    initialUrl = intent?.getStringExtra(EXTRA_INITIAL_URL) ?: "about:blank",
                    conversationId = conversationId,
                )
            }
        }
    }

    override fun onDestroy() {
        // 文件选择器如果还开着（用户没选完就退了），把挂起的回调以 null 收尾，
        // 不让 WebView 侧一直等一个永远不会来的结果。
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        // Tear down the WebView the Activity created — not doing so leaks the entire view
        // hierarchy plus a JS engine on every Activity finish. Order matters: clear from the
        // controller first so an in-flight tool dispatch can fail-fast on browser_not_open
        // rather than racing the destroy.
        webView?.let { wv ->
            BrowserController.unbind(wv)
            wv.stopLoading()
            wv.loadUrl("about:blank")
            (wv.parent as? android.view.ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        /** Optional extra: initial URL to navigate to on launch. Defaults to about:blank. */
        const val EXTRA_INITIAL_URL = "me.rerere.rikkahub.browser.EXTRA_INITIAL_URL"

        /**
         * Optional extra: companion conversation id (Uuid string). When set, the mini-chat
         * overlay sends prompts into this conversation so the user can keep talking to the
         * AI while watching the WebView. browser_open passes the caller's id; manual
         * launches from Settings can omit this and the overlay self-hides.
         */
        const val EXTRA_CONVERSATION_ID = "me.rerere.rikkahub.browser.EXTRA_CONVERSATION_ID"

        /**
         * Builds the launch Intent. Used by the `browser_open` tool, by the skill webview
         * card, and by Settings → Browser → "Open browser".
         */
        fun intent(
            context: android.content.Context,
            url: String? = null,
            conversationId: String? = null,
        ): Intent =
            Intent(context, BrowserActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (url != null) putExtra(EXTRA_INITIAL_URL, url)
                if (conversationId != null) putExtra(EXTRA_CONVERSATION_ID, conversationId)
            }
    }
}
