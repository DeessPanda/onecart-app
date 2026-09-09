package com.onecart

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.onecart.databinding.FragmentWebviewBinding

/**
 * Generic WebView fragment that loads a shopping/delivery website.
 * Handles: JavaScript, cookies, geolocation permissions,
 * and proper URL scheme handling.
 */
class WebViewFragment : Fragment() {

    companion object {
        private const val ARG_URL = "url"
        private const val TAG = "WebViewFragment"

        fun newInstance(url: String): WebViewFragment {
            val fragment = WebViewFragment()
            val args = Bundle().apply { putString(ARG_URL, url) }
            fragment.arguments = args
            return fragment
        }
    }

    private var _binding: FragmentWebviewBinding? = null
    private val binding get() = _binding!!

    private lateinit var webView: WebView

    private var url: String = ""
    private var hasLocationPermission = false
    private var pendingGeolocationCallback: GeolocationPermissions.Callback? = null
    private var pendingGeolocationOrigin: String? = null
    private var savedInstanceStateBundle: Bundle? = null

    // Activity Result launcher for location permission
    private val locationPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasLocationPermission = granted
            if (granted) {
                pendingGeolocationCallback?.invoke(pendingGeolocationOrigin!!, true, false)
            } else {
                pendingGeolocationCallback?.invoke(pendingGeolocationOrigin!!, false, false)
                if (context != null) {
                    Toast.makeText(context, R.string.location_permission_denied, Toast.LENGTH_SHORT).show()
                }
            }
            pendingGeolocationCallback = null
            pendingGeolocationOrigin = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceStateBundle = savedInstanceState
        arguments?.getString(ARG_URL)?.let { url = it }
        // Check if location permission is already granted
        hasLocationPermission = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWebviewBinding.inflate(inflater, container, false)
        webView = binding.webView

        setupWebView()
        loadUrl()

        return binding.root
    }

    private fun setupWebView() {
        val settings: WebSettings = webView.settings

        // Enable JavaScript and DOM storage
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true

        // Enable geolocation (modern API)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            settings.setGeolocationEnabled(true)
        } else {
            // Deprecated but needed for older API levels
            @Suppress("DEPRECATION")
            settings.javaClass.getDeclaredField("geolocationEnabled").apply { isAccessible = true }.setBoolean(settings, true)
        }
        settings.setGeolocationDatabasePath(requireContext().filesDir.path)

        // Allow mixed content (for sites that load HTTP resources on HTTPS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        // Set mobile Chrome user agent
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        // Enable zoom controls
        settings.builtInZoomControls = true
        settings.displayZoomControls = false

        // Cache settings
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // Cookie manager - accept cookies including third-party
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, true)
        }

        // Set custom WebViewClient for URL handling
        webView.webViewClient = OneCartWebViewClient(requireContext())

        // Set custom WebChromeClient for geolocation and other UI
        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                Log.d(TAG, "Geolocation requested for origin: $origin")
                handleGeolocationPrompt(origin, callback)
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                // Could update toolbar title here if needed
            }
        }

        // Restore scroll position if available
        savedInstanceStateBundle?.let {
            webView.restoreState(it)
        }
    }

    private fun handleGeolocationPrompt(origin: String, callback: GeolocationPermissions.Callback) {
        if (hasLocationPermission) {
            // Permission already granted, allow immediately
            callback.invoke(origin, true, false)
        } else {
            // Need to request permission
            pendingGeolocationCallback = callback
            pendingGeolocationOrigin = origin

            // Check if we should show rationale
            if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                // Show rationale and then request
                if (context != null) {
                    Toast.makeText(context, R.string.location_permission_rationale, Toast.LENGTH_LONG).show()
                }
            }

            // Launch the system permission dialog
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun loadUrl() {
        if (url.isNotEmpty()) {
            webView.loadUrl(url)
        }
    }

    fun canGoBack(): Boolean {
        return webView.canGoBack()
    }

    fun goBack() {
        if (webView.canGoBack()) {
            webView.goBack()
        }
    }

    override fun onPause() {
        super.onPause()
        // Persist cookies when app goes to background
        CookieManager.getInstance().flush()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroyView() {
        // Clean up WebView to prevent memory leaks
        webView.stopLoading()
        webView.webViewClient = object : WebViewClient() {}
        webView.webChromeClient = WebChromeClient()
        webView.destroy()
        _binding = null
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }
}