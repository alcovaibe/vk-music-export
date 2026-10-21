package app.alcovaibe.vkmusicexport

import android.app.*
import android.content.Context
import android.os.Bundle
import android.net.*
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics

class DiagnosticApplication : Application(), Application.ActivityLifecycleCallbacks {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        Diagnostics.start(this)
    }
    override fun onCreate() {
        super.onCreate()
        Diagnostics.event("application_create", mapOf("version_name" to BuildConfig.VERSION_NAME,
            "version_code" to BuildConfig.VERSION_CODE, "package" to packageName))
        registerActivityLifecycleCallbacks(this)
        val manager=getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { Diagnostics.event("network_available") }
            override fun onLost(network: Network) { Diagnostics.event("network_lost") }
            override fun onCapabilitiesChanged(network: Network, c: NetworkCapabilities) {
                Diagnostics.event("network_capabilities",mapOf("vpn" to c.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                    "wifi" to c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),"cellular" to c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                    "internet" to c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                    "validated" to c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)))
            }
            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) { Diagnostics.event("network_blocked",mapOf("blocked" to blocked)) }
        })
        // Historical platform reasons only, never description/trace (may contain page data).
        Thread({
            app.alcovaibe.vkmusicexport.diagnostics.ChromeDiagnostics.recordProvider(this)
            runCatching { (getSystemService(ACTIVITY_SERVICE) as ActivityManager)
                .getHistoricalProcessExitReasons(packageName, 0, 5).forEach {
                    Diagnostics.event("historical_exit", mapOf("reason" to it.reason, "status" to it.status,
                        "timestamp" to it.timestamp, "importance" to it.importance, "pid" to it.pid))
                } }
        }, "diagnostics-exit-reasons").start()
    }
    private var started = 0
    private fun life(name: String, activity: Activity) = Diagnostics.event("activity_$name", mapOf("instance" to System.identityHashCode(activity)))
    override fun onActivityCreated(a: Activity, b: Bundle?) { life("created",a); Diagnostics.event("activity_saved_state", mapOf("present" to (b != null))) }
    override fun onActivityStarted(a: Activity) { started++; Diagnostics.foreground(true); life("started",a) }
    override fun onActivityResumed(a: Activity) = life("resumed",a)
    override fun onActivityPaused(a: Activity) = life("paused",a)
    override fun onActivityStopped(a: Activity) { started--; Diagnostics.foreground(started > 0); life("stopped",a) }
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = life("save_state",a)
    override fun onActivityDestroyed(a: Activity) = life("destroyed",a)
    override fun onTrimMemory(level: Int) { Diagnostics.event("trim_memory", mapOf("level" to level)); super.onTrimMemory(level) }
}
