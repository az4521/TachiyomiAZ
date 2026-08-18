package eu.kanade.tachiyomi.util.system

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

object WebViewUtil {
    val WEBVIEW_UA_VERSION_REGEX by lazy {
        Regex(""".*Chrome/(\d+)\..*""")
    }

    private const val SYSTEM_SETTINGS_PACKAGE = "com.android.settings"
    private const val CHROME_PACKAGE = "com.android.chrome"

    const val MINIMUM_WEBVIEW_VERSION = 84

    fun supportsWebView(context: Context): Boolean {
        try {
            // May throw android.webkit.WebViewFactory$MissingWebViewPackageException if WebView
            // is not installed
            CookieManager.getInstance()
        } catch (e: Exception) {
            return false
        }

        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_WEBVIEW)
    }

    @SuppressLint("WebViewApiAvailability")
    fun getVersion(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val webView = WebView.getCurrentWebViewPackage() ?: return "how did you get here?"

            val pm = context.packageManager
            val label = webView.applicationInfo!!.loadLabel(pm)
            val version = webView.versionName
            return "$label $version"
        } else {
            return getWebViewUA(WebView(context))
        }
    }

    fun spoofedPackageName(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(CHROME_PACKAGE, PackageManager.GET_META_DATA)

            CHROME_PACKAGE
        } catch (_: PackageManager.NameNotFoundException) {
            SYSTEM_SETTINGS_PACKAGE
        }
    }
}

fun WebView.isOutdated(): Boolean {
    return getWebViewMajorVersion(this) < WebViewUtil.MINIMUM_WEBVIEW_VERSION
}

@SuppressLint("SetJavaScriptEnabled")
fun WebView.setDefaultSettings() {
    with(settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        useWideViewPort = true
        loadWithOverviewMode = true
        cacheMode = WebSettings.LOAD_DEFAULT

        // Leave multiple-window support off. Turning it on makes WebView refuse to open
        // window.open()/target="_blank" links itself: it defers to
        // WebChromeClient.onCreateWindow, whose default implementation drops the request without
        // a trace, so every popup-opening tap becomes a silent no-op. Nothing here implements
        // onCreateWindow, so off is the only setting that actually opens popups -- WebView then
        // loads them in place, in this same view.
        setSupportMultipleWindows(false)

        // Allow zooming
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
    }

    // Some embedded challenge frames (e.g. Turnstile) need third-party cookies to work
    CookieManager.getInstance().acceptThirdPartyCookies(this)
}

/**
 * Sets the WebView's User-Agent and, on supported WebView versions, synchronizes the
 * corresponding user-agent client-hint metadata so the browser fingerprint stays consistent
 * between the `User-Agent` header and the `Sec-CH-UA` client hints. Without this, a spoofed
 * desktop UA can leak the real Android WebView identity through client hints and break
 * Cloudflare/Turnstile challenges.
 */
fun WebView.setUserAgent(userAgent: String) {
    settings.userAgentString = userAgent

    if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return

    val versionMatch = CHROME_VERSION_REGEX.find(userAgent) ?: return
    val majorVersion = versionMatch.groupValues[1]
    val fullVersion = majorVersion + versionMatch.groupValues[2].ifEmpty { ".0.0.0" }

    try {
        val metadata = WebSettingsCompat.getUserAgentMetadata(settings)
        val brandVersionList = metadata.brandVersionList.map { brandVersion ->
            val brand = when (brandVersion.brand) {
                WEBVIEW_BRAND -> CHROME_BRAND
                CHROMIUM_BRAND -> CHROMIUM_BRAND
                else -> return@map brandVersion
            }

            UserAgentMetadata.BrandVersion.Builder()
                .setBrand(brand)
                .setMajorVersion(majorVersion)
                .setFullVersion(fullVersion)
                .build()
        }

        WebSettingsCompat.setUserAgentMetadata(
            settings,
            UserAgentMetadata.Builder(metadata)
                .setBrandVersionList(brandVersionList)
                .setFullVersion(fullVersion)
                .build()
        )
    } catch (e: Exception) {
        Log.e("WebViewUtil", "Failed to set user agent metadata", e)
    }
}

private const val WEBVIEW_BRAND = "Android WebView"
private const val CHROMIUM_BRAND = "Chromium"
private const val CHROME_BRAND = "Google Chrome"
private val CHROME_VERSION_REGEX = """Chrome/(\d+)(\.[\d.]+)?""".toRegex()

// Based on https://stackoverflow.com/a/29218966
private fun getWebViewMajorVersion(webview: WebView): Int {
    val originalUA: String = webview.settings.userAgentString

    // Next call to getUserAgentString() will get us the default
    webview.settings.userAgentString = null

    val uaRegexMatch = WebViewUtil.WEBVIEW_UA_VERSION_REGEX.matchEntire(webview.settings.userAgentString)
    val webViewVersion: Int =
        if (uaRegexMatch != null && uaRegexMatch.groupValues.size > 1) {
            uaRegexMatch.groupValues[1].toInt()
        } else {
            0
        }

    // Revert to original UA string
    webview.settings.userAgentString = originalUA

    return webViewVersion
}

private fun getWebViewUA(webview: WebView): String {
    val originalUA: String = webview.settings.userAgentString

    // Next call to getUserAgentString() will get us the default
    webview.settings.userAgentString = null

    // Grab the default UA
    val defaultUA = webview.settings.userAgentString

    // Revert to original UA string
    webview.settings.userAgentString = originalUA

    return defaultUA
}
