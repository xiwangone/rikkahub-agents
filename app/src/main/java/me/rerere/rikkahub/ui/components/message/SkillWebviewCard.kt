package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.browser.BrowserActivity

/**
 * Pass 3 (Phase 18B-card): chat-side renderer for `UIMessagePart.Text` parts whose
 * metadata carries a `rikkahub.webview` block. Emitted by JS skills via
 * [me.rerere.rikkahub.skills.js.RunJsTool] when a skill returns a `webview` payload.
 *
 * The shape we look for, written by Phase 20-audit's `runJsTool` change:
 *
 * ```json
 * {
 *   "rikkahub.webview": {
 *     "url": "https://… or file://…",
 *     "iframe": true|false,
 *     "aspect_ratio": 1.6,
 *     "source": "js_skill:<name>"
 *   }
 * }
 * ```
 *
 * v1 of the renderer (this Pass) does NOT inline an iframe — that's the deferred
 * Phase 18B-inline. Instead we draw a small "Open in browser" card. Tapping fires
 * [BrowserActivity.intent] so the same persistent profile + AI tooling the LLM uses for
 * agentic browsing also serves as the skill output viewer. "Browser as the viewer" — exact
 * route the spec asks for: tap to view, scroll to dismiss.
 *
 * The card also surfaces the iframe-vs-direct intent as an overline ("Direct" when the
 * skill explicitly opted out of iframe embedding) so power-users can tell the two apart
 * before tapping. Failing to detect the metadata block returns null — the calling renderer
 * falls back to the standard markdown text rendering.
 */
@Composable
internal fun SkillWebviewCardOrNull(
    part: UIMessagePart.Text,
    modifier: Modifier = Modifier,
): Boolean {
    val webview = remember(part.metadata) { extractWebviewMeta(part.metadata) } ?: return false
    val context = LocalContext.current

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Overline: tells the user whether the skill expected an iframe (embedded) or
            // a direct full-screen open. v1 always opens direct in BrowserActivity; the
            // label is informational.
            Text(
                text =
                    stringResource(
                        if (webview.iframe) {
                            R.string.skill_webview_card_embedded
                        } else {
                            R.string.skill_webview_card_direct
                        },
                    ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            // Title: the skill's "Open in a viewer" text (or whatever they returned). The
            // skill author writes this to be human-readable; we keep it as-is.
            Text(
                text = part.text.ifBlank { stringResource(R.string.skill_webview_card_default_title) },
                style = MaterialTheme.typography.titleMedium,
            )
            // URL preview line — gives the user the destination before the tap.
            Text(
                text = webview.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(onClick = {
                    // Routes through the in-app browser so the persistent profile, tool
                    // toggles, and HARDLINE all apply uniformly. Falls back to about:blank
                    // if the URL was somehow lost between metadata write + render.
                    //
                    // When the skill declared `iframe: true` for a remote URL, top-level
                    // navigation would hit the endpoint's own "must be used in an iframe"
                    // rejection page (e.g. the Google Maps Embed API). Open a locally built
                    // wrapper page that embeds it instead. It is handed over as a `data:` URL
                    // rather than a cache file: the browser's file:// whitelist only admits
                    // filesDir/skills, and a data: page needs no disk write at all.
                    runCatching {
                        val launchUrl =
                            if (shouldWrapInIframe(webview.url, webview.iframe)) {
                                iframeWrapperDataUrl(webview.url)
                            } else {
                                webview.url
                            }
                        context.startActivity(BrowserActivity.intent(context, launchUrl))
                    }
                }) {
                    Text(stringResource(R.string.skill_webview_card_open))
                }
            }
        }
    }
    return true
}

/** Compact value type for the webview metadata block. */
private data class WebviewMeta(
    val url: String,
    val iframe: Boolean,
    val source: String?,
)

/**
 * Pull a [WebviewMeta] out of the part's metadata. Returns null if the metadata is null,
 * doesn't have the `rikkahub.webview` key, or has a malformed `url`. Defensive parsing —
 * any unexpected shape falls back to null and the standard markdown renderer takes over.
 */
private fun extractWebviewMeta(metadata: JsonObject?): WebviewMeta? {
    val webview = metadata?.get("rikkahub.webview")?.jsonObject ?: return null
    val url = webview["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    val iframe = webview["iframe"]?.jsonPrimitive?.booleanOrNull ?: true
    val source = webview["source"]?.jsonPrimitive?.contentOrNull
    return WebviewMeta(url = url, iframe = iframe, source = source)
}

/**
 * Cheap predicate for whether this part carries a usable `rikkahub.webview` metadata
 * block, without doing the full render. Shares [extractWebviewMeta] so it can never
 * disagree with what [SkillWebviewCardOrNull] actually renders — used by the tool step to
 * decide whether a `run_js` tool's nested output parts should be rendered as webview cards.
 */
internal fun UIMessagePart.Text.hasSkillWebviewMeta(): Boolean =
    extractWebviewMeta(metadata) != null

/**
 * True only when the skill declared `iframe: true` and [url] is a remote `http`/`https`
 * address. A `file://` skill page (e.g. the virtual-piano skill) is already local and
 * full-screen — wrapping it in an iframe buys nothing and would break any relative asset
 * paths inside it.
 */
internal fun shouldWrapInIframe(url: String, iframe: Boolean): Boolean {
    if (!iframe) return false
    val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
    return scheme == "http" || scheme == "https"
}

/**
 * Builds a minimal full-height HTML document that embeds [url] in an `<iframe>`. Some skill
 * endpoints (e.g. the Google Maps Embed API) render a plain-text rejection page
 * ("must be used in an iframe") when navigated to top-level, but render correctly when
 * embedded. [url] may be skill-authored and is therefore untrusted — it is HTML-attribute-
 * escaped before interpolation so it cannot break out of the `src` attribute.
 */
internal fun buildIframeWrapperHtml(url: String): String {
    val escapedUrl =
        url
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("'", "&#39;")
    return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <style>html, body { height: 100%; margin: 0; }</style>
        </head>
        <body>
        <iframe src="$escapedUrl" style="width:100%; height:100%; border:0; display:block"></iframe>
        </body>
        </html>
    """.trimIndent()
}

/** [buildIframeWrapperHtml] for [url] as a self-contained base64 `data:` URL. */
internal fun iframeWrapperDataUrl(url: String): String =
    "data:text/html;charset=utf-8;base64," +
        java.util.Base64.getEncoder().encodeToString(buildIframeWrapperHtml(url).toByteArray(Charsets.UTF_8))
