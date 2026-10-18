package app.alcovaibe.vkmusicexport.vk

import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/** Mobile Chrome compatibility, using the installed Chromium engine's version. */
object BrowserProfile {
    enum class Mode(val label: String) {
        CHROME_COMPAT("Chrome-совместимый"), SYSTEM_WEBVIEW("Штатный WebView")
    }

    private val chromiumVersion = Regex("Chrome/([0-9]+(?:\\.[0-9]+){3})")

    fun mobileUserAgent(defaultAgent: String): String {
        if (!chromiumVersion.containsMatchIn(defaultAgent)) return defaultAgent
        return defaultAgent.replace(Regex(";\\s*wv(?=[;)])"), "")
            .replace(Regex("\\s+Version/[0-9.]+"), "")
    }

    fun apply(view: WebView, mode: Mode = Mode.CHROME_COMPAT) {
        // A system-mode view is newly constructed: retain both its UA and original metadata.
        if (mode == Mode.SYSTEM_WEBVIEW) return
        val defaultAgent = WebSettings.getDefaultUserAgent(view.context)
        val uaVersion = chromiumVersion.find(defaultAgent)?.groupValues?.get(1) ?: return
        val supportsMetadata = WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)
        val original = if (supportsMetadata) WebSettingsCompat.getUserAgentMetadata(view.settings) else null
        // Set once, before any navigation: changing a UA mid-login reloads the page.
        view.settings.userAgentString = mobileUserAgent(defaultAgent)
        if (original != null && supportsMetadata) {
            val fullVersion = WebView.getCurrentWebViewPackage()?.versionName
                ?.takeIf { Regex("[0-9]+(?:\\.[0-9]+){3}").matches(it) } ?: uaVersion
            val major = fullVersion.substringBefore('.')
            fun brand(name: String) = UserAgentMetadata.BrandVersion.Builder()
                .setBrand(name).setMajorVersion(major).setFullVersion(fullVersion).build()
            val brands = original.brandVersionList.filterNot {
                it.brand in setOf("Android WebView", "Chromium", "Google Chrome")
            } + listOf(brand("Chromium"), brand("Google Chrome"))
            WebSettingsCompat.setUserAgentMetadata(view.settings, UserAgentMetadata.Builder(original)
                .setBrandVersionList(brands).setFullVersion(fullVersion).build())
        }
    }
}
