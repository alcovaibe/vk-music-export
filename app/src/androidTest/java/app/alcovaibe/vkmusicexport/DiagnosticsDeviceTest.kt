package app.alcovaibe.vkmusicexport

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.net.Uri
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
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
    @Test fun menuSharesFreshCurrentRunJsonWithReadGrantAndNoClipboardOrPreviousSelector() {
        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(ActivityResult(Activity.RESULT_OK,null))
            Diagnostics.event("latest_report_probe")
            ui.onNodeWithContentDescription("Меню").performClick()
            ui.onNodeWithTag("share_latest_report").performClick()
            ui.waitUntil(25_000) {
                dismissFrameworkDialog()
                Intents.getIntents().any{it.action==Intent.ACTION_CHOOSER}
            }
            val chooser=Intents.getIntents().last{it.action==Intent.ACTION_CHOOSER}
            @Suppress("DEPRECATION")
            val send=if(android.os.Build.VERSION.SDK_INT>=33)
                chooser.getParcelableExtra(Intent.EXTRA_INTENT,Intent::class.java)
            else chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            assertNotNull(send)
            val shared=send!!
            assertEquals(Intent.ACTION_SEND,shared.action);assertEquals("application/json",shared.type)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)
            @Suppress("DEPRECATION")
            val uri=if(android.os.Build.VERSION.SDK_INT>=33)
                shared.getParcelableExtra(Intent.EXTRA_STREAM,Uri::class.java)
            else shared.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            assertNotNull(uri)
            val exportedUri=uri!!
            assertEquals("${ui.activity.packageName}.diagnostics",uri.authority)
            assertEquals(uri,send.clipData!!.getItemAt(0).uri)
            val report=Json.parseToJsonElement(ui.activity.contentResolver.openInputStream(uri)!!.bufferedReader().use{it.readText()}).jsonObject
            assertEquals(1,report.getValue("schema").jsonPrimitive.int)
            val run=report.getValue("run_id").jsonPrimitive.content
            assertTrue(report.getValue("events").jsonArray.isNotEmpty())
            assertTrue(report.getValue("events").jsonArray.all{it.jsonObject["run_id"]?.jsonPrimitive?.content==run})
            assertTrue(report.getValue("events").jsonArray.any{it.jsonObject["type"]?.jsonPrimitive?.content=="latest_report_probe"})
            assertEquals(run,Json.parseToJsonElement(exported()).jsonObject.getValue("run_id").jsonPrimitive.content)
            for(label in listOf("Копировать лог","Другие логи","Поделиться предыдущим JSON"))ui.onNodeWithText(label).assertDoesNotExist()
            val secret=java.io.File(ui.activity.cacheDir,"unrelated-report-secret.txt").apply{writeText("synthetic private data")}
            try{assertThrows(IllegalArgumentException::class.java){FileProvider.getUriForFile(ui.activity,"${ui.activity.packageName}.diagnostics",secret)}}finally{secret.delete()}
        }finally{Intents.release()}
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
