package com.onecart

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/**
 * Custom WebViewClient that keeps http/https links inside the WebView,
 * launches external apps for other schemes (upi://, tel:, mailto:, intent://, etc.),
 * and runs the popup blocker (install prompts, cosmetic banners, ad requests).
 */
class OneCartWebViewClient(private val context: Context) : WebViewClient() {

    companion object {
        private const val TAG = "OneCartWebViewClient"
    }

    private val blocker = PopupBlocker.load(context.applicationContext)

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        return shouldOverrideUrlLoading(view, url)
    }

    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        Log.d(TAG, "shouldOverrideUrlLoading: $url")

        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase() ?: ""

        // Keep http/https inside the WebView
        if (scheme == "http" || scheme == "https") {
            return false
        }

        // For all other schemes, try to launch externally
        try {
            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)

            // Suppress "open in the shopping app" deep links so the site
            // stays in the WebView (fall back to the web URL if provided).
            val packageName = intent.component?.packageName ?: intent.getPackage()
            if (packageName != null && blocker.isBlockedApp(packageName)) {
                val fallback = intent.getStringExtra("browser_fallback_url")
                Log.d(TAG, "Suppressing app deep link ($packageName) for: $url")
                if (fallback != null) {
                    view.loadUrl(fallback)
                }
                return true
            }

            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
            Log.d(TAG, "Launched external intent for: $url")
            return true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No app found to handle: $url", e)
            // Fallback: try generic VIEW intent
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, uri)
                fallbackIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(fallbackIntent)
                return true
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to launch fallback intent for: $url", ex)
                return true // Still return true to prevent WebView from trying to load it
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing URI: $url", e)
            return true
        }
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        val scheme = Uri.parse(url ?: "").scheme?.lowercase() ?: ""
        if (scheme != "http" && scheme != "https") return
        if (blocker.debug) Log.d(TAG, "Injecting popup blocker for: $url")
        view.evaluateJavascript(blocker.cleanupScript(), null)
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        val host = url.host ?: return null
        if (blocker.isBlockedRequest(host)) {
            Log.d(TAG, "Blocked request to: $host")
            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        }
        return null
    }
}