package com.drdisagree.teledrive.presentation.preview

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.drdisagree.teledrive.core.files.htmlNavigationAllowed
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.preview_html_safe_mode

/**
 * Safe static HTML rendering: JavaScript off, file/content access off, network loads
 * blocked, a null base URL, and every navigation refused (see [htmlNavigationAllowed]).
 * Script-driven pages render degraded or blank — that is the safe mode working, and
 * the caption above the page says so instead of leaving the user to discover it.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlPreview(html: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(Res.string.preview_html_safe_mode),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.blockNetworkLoads = true
                    settings.setSupportZoom(false)
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
                    }
                    loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                }
            }
        )
    }
}
