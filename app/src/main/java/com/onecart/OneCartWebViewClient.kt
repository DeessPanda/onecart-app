package com.onecart

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Custom WebViewClient that keeps http/https links inside the WebView,
 * but launches external apps for other schemes (upi://, tel:, mailto:, intent://, etc.)
 */
class OneCartWebViewClient(private val context: Context) : WebViewClient() {

    companion object {
        private const val TAG = "OneCartWebViewClient"
    }

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
}