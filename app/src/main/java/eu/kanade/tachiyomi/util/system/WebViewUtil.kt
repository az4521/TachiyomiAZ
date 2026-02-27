package eu.kanade.tachiyomi.util.system

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

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
    }
}

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
