package com.drdisagree.teledrive.presentation.preview

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.drdisagree.teledrive.core.files.HtmlWebViewPolicy
import com.drdisagree.teledrive.core.files.htmlNavigationAllowed
import com.drdisagree.teledrive.core.files.htmlSubresourceAllowed
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.preview_html_safe_mode
import com.drdisagree.teledrive.resources.preview_truncated_download_view
import java.io.ByteArrayInputStream

/**
 * HTML rendering (round 3 A10 rework of the vc181 safe viewer).
 *
 * The page's own inline scripts RUN ([HtmlWebViewPolicy.JAVA_SCRIPT_ENABLED]) with
 * DOM storage, because real-world pages — the field case was a 52-item checklist
 * whose items render and whose checkboxes are driven by an inline script — only
 * ever showed their static top portion with scripts off. Every route out of the
 * WebView stays closed, exactly as in the vc181 safe mode: file/content access off,
 * file-URL access off in both directions, network loads blocked, subresources
 * answered with an empty response unless they are `data:`/`about:` (see
 * [htmlSubresourceAllowed]), every navigation refused except the initial load and
 * same-document fragment jumps (see [htmlNavigationAllowed]), download links
 * swallowed, windows cannot open, and NO JavaScript interface is registered — a
 * script can only rearrange its own page.
 *
 * Tap safety: the previous client overrode only `shouldOverrideUrlLoading`, which
 * left three tap-time paths unhandled — a renderer-process death crashed the whole
 * app (the platform contract when `onRenderProcessGone` is not handled), a download
 * link made the WebView attempt a load itself, and fragment/relative taps against
 * the null base URL bypassed the navigation callback. All three are closed below,
 * and the WebView is destroyed when the composable leaves composition (the Files
 * pager disposes pages aggressively) so no load can outlive its view.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlPreview(html: String, truncated: Boolean = false, modifier: Modifier = Modifier) {
    // The html most recently handed to the WebView: the AndroidView factory runs
    // once per view, so a new document arriving in the same composition slot (the
    // in-zip pager reuses the slot for the next entry) must be loaded explicitly —
    // before this, the first document stayed on screen for every later one.
    var loadedHtml by remember { mutableStateOf<String?>(null) }
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(Res.string.preview_html_safe_mode),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        if (truncated) {
            Text(
                text = stringResource(Res.string.preview_truncated_download_view),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.javaScriptEnabled = HtmlWebViewPolicy.JAVA_SCRIPT_ENABLED
                    settings.domStorageEnabled = HtmlWebViewPolicy.DOM_STORAGE_ENABLED
                    settings.allowFileAccess = HtmlWebViewPolicy.ALLOW_FILE_ACCESS
                    settings.allowContentAccess = HtmlWebViewPolicy.ALLOW_CONTENT_ACCESS
                    settings.allowFileAccessFromFileURLs =
                        HtmlWebViewPolicy.ALLOW_FILE_ACCESS_FROM_FILE_URLS
                    settings.allowUniversalAccessFromFileURLs =
                        HtmlWebViewPolicy.ALLOW_UNIVERSAL_ACCESS_FROM_FILE_URLS
                    settings.blockNetworkLoads = HtmlWebViewPolicy.BLOCK_NETWORK_LOADS
                    settings.javaScriptCanOpenWindowsAutomatically =
                        HtmlWebViewPolicy.JAVA_SCRIPT_CAN_OPEN_WINDOWS_AUTOMATICALLY
                    settings.setSupportMultipleWindows(HtmlWebViewPolicy.SUPPORT_MULTIPLE_WINDOWS)
                    settings.mediaPlaybackRequiresUserGesture =
                        HtmlWebViewPolicy.MEDIA_PLAYBACK_REQUIRES_USER_GESTURE
                    settings.loadWithOverviewMode = HtmlWebViewPolicy.LOAD_WITH_OVERVIEW_MODE
                    settings.useWideViewPort = HtmlWebViewPolicy.USE_WIDE_VIEW_PORT
                    settings.setSupportZoom(HtmlWebViewPolicy.SUPPORT_ZOOM)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean = !htmlNavigationAllowed(request?.url?.toString())

                        @Deprecated("Deprecated in Java")
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            url: String?
                        ): Boolean = !htmlNavigationAllowed(url)

                        /**
                         * Subresources follow [htmlSubresourceAllowed]: only what
                         * the document already carries (`data:`/`about:`) loads;
                         * anything else gets an empty response instead of a fetch.
                         * The main-frame loadData document itself never passes
                         * through here.
                         */
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val url = request?.url?.toString()
                            return if (htmlSubresourceAllowed(url)) {
                                null
                            } else {
                                WebResourceResponse(
                                    "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
                                )
                            }
                        }

                        /**
                         * A dead renderer must never take the app with it: consume
                         * the event and drop the WebView (the platform crashes the
                         * whole app when this is left unhandled). The preview is
                         * gone, but the app — and the file list behind it — lives.
                         */
                        override fun onRenderProcessGone(
                            view: WebView?,
                            detail: RenderProcessGoneDetail?
                        ): Boolean {
                            view?.destroy()
                            return true
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            // Defence in depth alongside shouldOverrideUrlLoading:
                            // a main-frame load that is not the document itself or
                            // a fragment jump inside it is stopped immediately.
                            if (url != null && !htmlNavigationAllowed(url)) {
                                view?.stopLoading()
                            }
                            super.onPageStarted(view, url, favicon)
                        }
                    }
                    // Tapping a download link is a no-op, not a navigation attempt
                    // the WebView would otherwise try to load on its own.
                    setDownloadListener { _, _, _, _, _ -> }
                    loadedHtml = html
                    loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                }
            },
            update = { webView ->
                if (loadedHtml != html) {
                    loadedHtml = html
                    webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                }
            },
            onRelease = { webView ->
                webView.stopLoading()
                webView.webViewClient = WebViewClient()
                webView.setDownloadListener(null)
                webView.destroy()
            }
        )
    }
}
