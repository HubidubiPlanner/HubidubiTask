package com.prodos.mobile.web

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

/**
 * WebView setup (PLAN.md Phase 5):
 * - Assets served through WebViewAssetLoader so the origin is https://, not
 *   file:// — secure-context APIs (WebMessagePort, crypto) depend on this.
 * - JavaScript on; everything else off. No file access, no universal access
 *   from file URLs, no mixed content.
 * - Bridge is WebMessagePort/postMessage, wired in MainActivity — NOT
 *   addJavascriptInterface (coarse-grained, poor security history).
 *
 * Plugin view bundles are cached by the sync layer and served here from local
 * storage; a view never loads code over the network at runtime.
 */
object ProdosWebView {

    const val DOMAIN = "appassets.androidplatform.net"
    const val BASE = "https://$DOMAIN"

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView, pluginBundlesDir: String) {
        val loader = WebViewAssetLoader.Builder()
            .setDomain(DOMAIN)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(webView.context))
            // Plugin bundles cached to files, verified before extraction.
            .addPathHandler("/plugins/", WebViewAssetLoader.InternalStoragePathHandler(
                webView.context, java.io.File(pluginBundlesDir)))
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest,
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest,
            ): Boolean {
                // Keep navigation inside the app origin; never open external URLs.
                return request.url.host != DOMAIN
            }
        }
    }

    fun coreUiUrl(): Uri = Uri.parse("$BASE/assets/web/index.html")
}
