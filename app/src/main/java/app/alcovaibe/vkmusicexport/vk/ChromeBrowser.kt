package app.alcovaibe.vkmusicexport.vk

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics

/** Real Chrome activity, never an embedded browser or a spoofed user agent. */
object ChromeBrowser {
    const val PACKAGE = "com.android.chrome"
    const val HOME = "https://vk.ru/"
    fun loginIntent(): Intent = Intent(Intent.ACTION_VIEW,Uri.parse(HOME)).apply {
        setPackage(PACKAGE)
        addCategory(Intent.CATEGORY_BROWSABLE)
    }
    fun recordProvider(context: Context) {
        val info=runCatching {context.packageManager.getPackageInfo(PACKAGE,PackageManager.PackageInfoFlags.of(0))}.getOrNull()
        Diagnostics.event("browser_provider",mapOf("package" to PACKAGE,"installed" to (info!=null),
            "version" to info?.versionName?.takeIf{Regex("[0-9.]{1,64}").matches(it)},
            "version_code" to info?.longVersionCode,"mode" to "external_chrome",
            "auth_status" to "not_observable","webview_used" to false))
    }
    fun open(context: Context): Boolean {
        Diagnostics.event("chrome_launch_requested",mapOf("package" to PACKAGE,"destination" to "vk_home"))
        return try {
            context.startActivity(loginIntent())
            // Dispatch is not evidence of page load, successful login, or a transferred session.
            Diagnostics.event("chrome_launch_dispatched",mapOf("auth_status" to "not_observable"))
            true
        } catch (_: ActivityNotFoundException) {
            Diagnostics.event("chrome_launch_failed",mapOf("reason" to "not_available"));false
        } catch (_: SecurityException) {
            Diagnostics.event("chrome_launch_failed",mapOf("reason" to "launch_denied"));false
        }
    }
}
