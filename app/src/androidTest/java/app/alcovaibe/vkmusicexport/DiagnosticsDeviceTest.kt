package app.alcovaibe.vkmusicexport

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.alcovaibe.vkmusicexport.diagnostics.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
@RunWith(AndroidJUnit4::class)
class DiagnosticsDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun exported(previous: Boolean=false): String {
        val worker=Executors.newSingleThreadExecutor()
        return try { worker.submit<String>{Diagnostics.export(previous)}.get(15,TimeUnit.SECONDS) } finally {worker.shutdown()}
    }
    private fun dismissFrameworkDialog() {
        val root=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return
        val nodes=mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
        val queue=java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>();queue.add(root)
        while(queue.isNotEmpty()) {val node=queue.removeFirst();nodes.add(node);repeat(node.childCount){node.getChild(it)?.let(queue::add)}}
        val allowed=listOf("System UI isn't responding","Process system isn't responding","Quickstep isn't responding")
        if(nodes.none{it.text?.toString() in allowed}) return // App ANRs always fail, never dismiss them.
        var button=nodes.firstOrNull{it.text?.toString()=="Wait"} ?: return
        while(!button.isClickable) button=button.parent ?: return
        button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
    }
    @Test fun browserProfileRetryReplacesAttachedViewAndPreservesSession() {
        val model=androidx.lifecycle.ViewModelProvider(ui.activity)[MusicViewModel::class.java]
        lateinit var original: android.webkit.WebView
        val cookieReady=java.util.concurrent.atomic.AtomicBoolean(false)
        ui.runOnUiThread {
            original=model.activeWeb()!!
            android.webkit.CookieManager.getInstance().setCookie("https://vk.ru/","profile_fixture=retained; Secure; Path=/") {cookieReady.set(it)}
        }
        ui.waitUntil(5_000) {cookieReady.get()}
        ui.onNodeWithContentDescription("Настройки").performClick()
        ui.onNodeWithText("Другие логи").performClick()
        ui.onNodeWithText("Профиль входа").performClick()
        ui.onNodeWithText("Открыть: Штатный WebView").performClick()
        try {
            ui.waitUntil(10_000) {model.activeWeb()!==original && model.activeWeb()?.isAttachedToWindow==true}
            ui.runOnUiThread {
                val active=model.activeWeb()!!
                assertEquals(android.webkit.WebSettings.getDefaultUserAgent(ui.activity),active.settings.userAgentString)
                assertEquals(app.alcovaibe.vkmusicexport.vk.BrowserProfile.Mode.SYSTEM_WEBVIEW,model.browserProfile.value)
                assertTrue(android.webkit.CookieManager.getInstance().getCookie("https://vk.ru/").orEmpty().contains("profile_fixture=retained"))
                active.stopLoading()
                active.loadDataWithBaseURL("https://id.vk.com/auth","<body><button>Fixture</button><input type='password' value='PRIVATE_RENDER_SECRET'></body>","text/html","UTF-8",null)
            }
            ui.waitUntil(10_000) {model.currentPage.value=="https://id.vk.com/auth" && !model.pageLoading.value}
            var drawsBefore=0L
            ui.runOnUiThread {drawsBefore=(model.activeWeb() as DiagnosticWebView).diagnosticDrawCount;model.markWhiteScreen()}
            var json=""
            ui.waitUntil(15_000) {
                json=exported()
                val events=Json.parseToJsonElement(json).jsonObject["events"]!!.jsonArray.map{it.jsonObject}
                events.any{it["type"]?.jsonPrimitive?.content=="white_screen_reported"} &&
                    events.any{it["type"]?.jsonPrimitive?.content=="dom_structure" && it["data"]?.jsonObject?.get("inputs")?.jsonPrimitive?.int==1} &&
                    events.any{it["type"]?.jsonPrimitive?.content=="visual_state_ready"}
            }
            assertFalse(json.contains("PRIVATE_RENDER_SECRET"))
            ui.waitUntil(10_000) {
                var redrawn=false
                ui.runOnUiThread {redrawn=(model.activeWeb() as DiagnosticWebView).diagnosticDrawCount>drawsBefore}
                redrawn
            }
        } finally {
            ui.runOnUiThread {model.retryWithProfile(app.alcovaibe.vkmusicexport.vk.BrowserProfile.Mode.CHROME_COMPAT)}
        }
    }
    @Test fun nativeCopyAndPrivateFileProviderExport() {
        ui.onNodeWithContentDescription("Настройки").performClick()
        ui.onNodeWithText("Копировать лог").performClick()
        ui.waitUntil(25_000) {
            dismissFrameworkDialog()
            var available=false
            ui.runOnUiThread {
                val clipboard=ui.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                available=runCatching { Json.parseToJsonElement(clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()).jsonObject["schema"]?.jsonPrimitive?.int==1 }.getOrDefault(false)
            }
            available
        }
        var copied=""
        ui.runOnUiThread {
            copied=(ui.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!.getItemAt(0).text.toString()
        }
        assertTrue(Json.parseToJsonElement(copied).jsonObject["events"]!!.jsonArray.isNotEmpty())
        assertFalse(fitsClipboard("x".repeat(120_001)))
        assertTrue(fitsClipboard("x".repeat(120_000)))
        val worker=Executors.newSingleThreadExecutor()
        try {
            val file=worker.submit<java.io.File> { Diagnostics.shareFile(exported()) }.get(20,TimeUnit.SECONDS)
            val uri=FileProvider.getUriForFile(ui.activity,"${ui.activity.packageName}.diagnostics",file)
            val text=ui.activity.contentResolver.openInputStream(uri)!!.bufferedReader().use{it.readText()}
            assertEquals(1,Json.parseToJsonElement(text).jsonObject["schema"]!!.jsonPrimitive.int)
        } finally {worker.shutdown()}
    }
    @Test fun privacyOnActualWebViewConsoleAndErrorCallbacks() {
        val secret="SYNTHETIC_PRIVATE_79991234567_password_token_123456"
        val done=java.util.concurrent.atomic.AtomicBoolean(false)
        ui.runOnUiThread {
            val view=androidx.lifecycle.ViewModelProvider(ui.activity)[MusicViewModel::class.java].activeWeb()!!
            view.loadDataWithBaseURL("https://id.vk.com/auth?token=$secret", "<input name='phone' value='$secret'><input type='password' value='$secret'>", "text/html", "UTF-8", null)
        }
        ui.waitUntil(10_000) {
            val model=androidx.lifecycle.ViewModelProvider(ui.activity)[MusicViewModel::class.java]
            model.currentPage.value=="https://id.vk.com/auth" && !model.pageLoading.value
        }
        ui.runOnUiThread {
            val view=androidx.lifecycle.ViewModelProvider(ui.activity)[MusicViewModel::class.java].activeWeb()!!
            view.evaluateJavascript("console.error('TypeError $secret');setTimeout(()=>{throw new Error('$secret')},0);true") {done.set(true)}
        }
        ui.waitUntil(10_000) {done.get()}
        var json=""
        ui.waitUntil(10_000) {
            json=exported()
            Json.parseToJsonElement(json).jsonObject["events"]!!.jsonArray.any {
                val o=it.jsonObject
                o["type"]?.jsonPrimitive?.content=="javascript_error"
            }
        }
        assertFalse("Synthetic credential escaped through WebView diagnostics",json.contains(secret))
    }
    @Test fun queuePressureReportsLossAndProducesValidJson() {
        val producers=(1..4).map { Thread {repeat(4000){Diagnostics.event("synthetic_pressure",mapOf("index" to it))}}.apply{start()} }
        producers.forEach {it.join()}
        val result=Json.parseToJsonElement(exported()).jsonObject
        assertTrue(result["events"]!!.jsonArray.isNotEmpty())
        val health=result["writer_health"]!!.jsonObject
        assertEquals(0,health["current_process_io_failures"]!!.jsonPrimitive.int)
        if(health["current_process_total_dropped"]!!.jsonPrimitive.long>0) {
            assertTrue(health["pending_loss"]!!.jsonPrimitive.long>0 || result["events"]!!.jsonArray.any{it.jsonObject["type"]!!.jsonPrimitive.content=="queue_loss"})
        }
    }
}
