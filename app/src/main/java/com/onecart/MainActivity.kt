package com.onecart

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowInsetsController
import android.webkit.CookieManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.onecart.databinding.ActivityMainBinding

/**
 * MainActivity hosting 5 WebView fragments behind a bottom navigation bar.
 * Each fragment loads a different shopping/delivery website.
 * Fragments are kept alive (show/hide) to preserve scroll position and login state.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding

    // Map of bottom nav item IDs to fragment tags
    private val tabMap = mapOf(
        R.id.tab_instamart to "instamart",
        R.id.tab_zepto to "zepto",
        R.id.tab_blinkit to "blinkit",
        R.id.tab_amazon to "amazon",
        R.id.tab_flipkart to "flipkart"
    )

    // URLs for each tab
    private val urlMap = mapOf(
        "instamart" to "https://www.swiggy.com/instamart",
        "zepto" to "https://www.zepto.com",
        "blinkit" to "https://www.blinkit.com",
        "amazon" to "https://www.amazon.in",
        "flipkart" to "https://www.flipkart.com"
    )

    private var currentTabId = R.id.tab_instamart

    // Activity Result launcher for location permission (shared across all fragments)
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d(TAG, "Location permission result: $granted")
        // The fragment will handle the callback via its own launcher
        // This is a fallback for any direct permission requests from Activity
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up toolbar as action bar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)

        // Fix status bar icon visibility - use dark icons on light background
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Modern API: WindowInsetsControllerCompat
            WindowInsetsControllerCompat(window, binding.root).isAppearanceLightStatusBars = true
        } else {
            // Legacy API
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }

        // Handle window insets properly - let the root layout handle fitsSystemWindows
        // The FragmentContainerView is constrained between toolbar and bottomNav
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            // Apply insets to toolbar (top) and bottomNav (bottom)
            val toolbarInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            // Update toolbar top padding for status bar
            binding.toolbar.setPadding(
                binding.toolbar.paddingLeft,
                toolbarInsets.top,
                binding.toolbar.paddingRight,
                binding.toolbar.paddingBottom
            )

            // Update bottom nav bottom padding for navigation bar
            binding.bottomNav.setPadding(
                binding.bottomNav.paddingLeft,
                binding.bottomNav.paddingTop,
                binding.bottomNav.paddingRight,
                navInsets.bottom
            )

            insets
        }

        // Restore selected tab from saved state
        if (savedInstanceState != null) {
            currentTabId = savedInstanceState.getInt("currentTab", R.id.tab_instamart)
        }

        // Set up bottom navigation listener
        binding.bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }

        // Select the initial/restored tab
        binding.bottomNav.selectedItemId = currentTabId
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("currentTab", currentTabId)
    }

    override fun onBackPressed() {
        // Check if current fragment's WebView can go back
        val currentTag = tabMap[currentTabId]
        if (currentTag != null) {
            val fragment = supportFragmentManager.findFragmentByTag(currentTag) as? WebViewFragment
            if (fragment?.canGoBack() == true) {
                fragment.goBack()
                return
            }
        }
        // No WebView history, exit app
        super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        // Persist cookies when app goes to background
        CookieManager.getInstance().flush()
    }

    private fun showTab(tabId: Int) {
        val tag = tabMap[tabId] ?: return
        val fm = supportFragmentManager
        val transaction = fm.beginTransaction()

        // Hide all other fragments
        for (otherTag in tabMap.values) {
            if (otherTag != tag) {
                fm.findFragmentByTag(otherTag)?.let { fragment ->
                    if (fragment.isAdded) {
                        transaction.hide(fragment)
                    }
                }
            }
        }

        // Show or create the target fragment
        var fragment = fm.findFragmentByTag(tag) as? WebViewFragment
        if (fragment == null) {
            val url = urlMap[tag] ?: ""
            fragment = WebViewFragment.newInstance(url)
            transaction.add(R.id.fragmentContainer, fragment, tag)
        } else {
            transaction.show(fragment)
        }

        transaction.commit()
        currentTabId = tabId
    }
}