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

@RunWith(AndroidJUnit4::class)
class DiagnosticsDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun exported(previous: Boolean=false): String {
        val worker=Executors.newSingleThreadExecutor()
        return try { worker.submit<String>{Diagnostics.export(previous)}.get(15,TimeUnit.SECONDS) } finally {worker.shutdown()}
    }
    @Test fun nativeCopyAndPrivateFileProviderExport() {
        ui.onNodeWithContentDescription("Настройки").performClick()
        ui.onNodeWithText("Копировать лог").performClick()
        ui.waitUntil(15_000) {
            var available=false
            ui.runOnUiThread {
                val clipboard=ui.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                available=clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.startsWith("{\"schema\"")==true
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
