package app.alcovaibe.vkmusicexport.music

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import androidx.browser.customtabs.CustomTabsIntent
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics

/** Only the user's default full HTTPS browser can receive a one-time login link.
 * A VK-only deep-link handler or a Custom Tabs service alone is insufficient.
 */
internal data class AuthBrowserCandidate(val packageName: String,val enabled: Boolean,val exported: Boolean,
    val customTabs: Boolean)
internal object AuthBrowserPolicy {
    fun select(defaultPackage: String?,candidates: List<AuthBrowserCandidate>): String? =
        candidates.singleOrNull{it.packageName==defaultPackage&&it.enabled&&it.exported&&it.customTabs}?.packageName
}
internal fun authBrowserPackage(context: Context): String? {
    val pm=context.packageManager
    // Use a generic HTTPS URL, not the VK login URL, when checking a full browser.
    val browse=Intent(Intent.ACTION_VIEW,Uri.parse("https://example.org/"))
        .addCategory(Intent.CATEGORY_BROWSABLE)
    val defaultPackage=pm.resolveActivity(browse,PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    @Suppress("DEPRECATION")
    val candidates=pm.queryIntentActivities(browse,PackageManager.MATCH_DEFAULT_ONLY).mapNotNull{resolved->
        val activity=resolved.activityInfo?:return@mapNotNull null
        val service=pm.resolveService(Intent("android.support.customtabs.action.CustomTabsService")
            .setPackage(activity.packageName),0)?.serviceInfo
        AuthBrowserCandidate(activity.packageName,activity.enabled&&activity.applicationInfo.enabled,activity.exported,
            service?.let{it.enabled&&it.exported&&it.applicationInfo.enabled}==true)
    }.distinctBy{it.packageName}
    return AuthBrowserPolicy.select(defaultPackage,candidates)
}
internal fun openDeviceAuthBrowser(context: Context,url: String): Boolean = try {
    val safe=DeviceAuthProtocol.browserUrl(url)
    val provider=authBrowserPackage(context)
    if(provider==null)false else {
        val tab=CustomTabsIntent.Builder().setShowTitle(true).setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .setSendToExternalDefaultHandlerEnabled(false).build()
        tab.intent.setPackage(provider)
        if(context !is android.app.Activity)tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        tab.launchUrl(context,Uri.parse(safe.toString()))
        Diagnostics.event("native_auth_browser_provider",mapOf("package" to provider,
            "mode" to "default_browser_custom_tab","entry_host" to safe.host,"auth_status" to "not_proven"))
        true
    }
}catch(_: Exception){false}
