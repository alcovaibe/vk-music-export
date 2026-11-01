package app.alcovaibe.vkmusicexport.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Provider metadata for the retained Chrome authorization, without a generic website launcher. */
internal object ChromeDiagnostics {
    fun recordProvider(context: Context) {
        val info=runCatching {
            if(Build.VERSION.SDK_INT>=33)context.packageManager.getPackageInfo(
                "com.android.chrome",PackageManager.PackageInfoFlags.of(0))
            else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo("com.android.chrome",0)
            }
        }.getOrNull()
        Diagnostics.event("browser_provider",mapOf("package" to "com.android.chrome","installed" to (info!=null),
            "version" to info?.versionName?.takeIf{Regex("[0-9.]{1,64}").matches(it)},
            "version_code" to info?.longVersionCode,"mode" to "chrome_custom_tab",
            "auth_status" to "not_observable","webview_used" to false))
    }
}
