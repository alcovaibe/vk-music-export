package app.alcovaibe.vkmusicexport

import android.graphics.Bitmap
import android.webkit.WebView
import android.os.SystemClock
import android.view.MotionEvent
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class InterfaceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun capture(name: String) {
        ui.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun navigationPreservesBrowserAndPopupRunsJavascript() {
        val model = ViewModelProvider(ui.activity)[MusicViewModel::class.java]
        ui.waitUntil(10_000) { model.ready.value }
        ui.onNodeWithText("Моя коллекция").assertIsDisplayed()
        capture("01-collection")
        ui.onNodeWithText("Экспорт").performClick()
        ui.onNodeWithText("Забрать с собой").assertIsDisplayed()
        capture("02-export")
        ui.onNodeWithText("Коллекция").performClick()
        ui.onNodeWithText("Подключить ВКонтакте").performClick()
        lateinit var original: WebView
        ui.runOnUiThread {
            original = model.activeWeb()!!
            // Synthetic VK-origin page; no real account or network response is used.
            original.stopLoading()
            original.loadDataWithBaseURL("https://vk.ru/audio", """
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <script>window.vk={id:123};</script>
                <div id="audio_list"><div class="audio_row" data-full-id="123_1" data-index="0">
                <b class="audio_row__title">Test track</b><span class="audio_row__performer">Test artist</span></div></div>
                <a id="auth" target="_blank" href="https://id.vk.com/">Test authorization</a>
            """.trimIndent(), "text/html", "UTF-8", null)
        }
        ui.waitUntil(15_000) { model.connected.value }
        ui.onNodeWithContentDescription("К коллекции").performClick()
        ui.onNodeWithText("Экспорт").performClick()
        ui.onNodeWithText("Коллекция").performClick()
        ui.onNodeWithText("Открыть музыку и собрать").performClick()
        ui.runOnUiThread { assertSame(original, model.activeWeb()) }
        // Inject an actual touch: window creation must require a user gesture.
        val position = AtomicReference<Pair<Float, Float>?>(null)
        ui.runOnUiThread {
            original.evaluateJavascript("JSON.stringify((function(){var r=document.getElementById('auth').getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2}})())") { raw ->
                val point = JSONObject(org.json.JSONTokener(raw).nextValue() as String)
                val offset = IntArray(2); original.getLocationOnScreen(offset)
                @Suppress("DEPRECATION") val scale = original.scale
                position.set(Pair(offset[0] + point.getDouble("x").toFloat() * scale, offset[1] + point.getDouble("y").toFloat() * scale))
            }
        }
        ui.waitUntil(5_000) { position.get() != null }
        val (x, y) = position.get()!!
        val now = SystemClock.uptimeMillis()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0).also { instrumentation.sendPointerSync(it); it.recycle() }
        MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_UP, x, y, 0).also { instrumentation.sendPointerSync(it); it.recycle() }
        ui.waitUntil(10_000) { model.popup.value != null }
        lateinit var child: WebView
        ui.runOnUiThread {
            child = model.popup.value!!
            assertNotSame(original, child)
            assertTrue(child.settings.javaScriptEnabled)
            assertTrue(child.settings.domStorageEnabled)
            assertTrue(android.webkit.CookieManager.getInstance().acceptThirdPartyCookies(child))
            child.stopLoading()
            child.loadDataWithBaseURL("https://id.vk.com/", "<h1>Authorization test fixture</h1>", "text/html", "UTF-8", null)
        }
        ui.onNodeWithContentDescription("Закрыть окно входа").assertIsDisplayed()
        val javascript = AtomicReference<String?>(null)
        ui.waitUntil(10_000) { !model.pageLoading.value }
        ui.runOnUiThread { child.evaluateJavascript("1 + 1") { javascript.set(it) } }
        ui.waitUntil(5_000) { javascript.get() != null }
        assertEquals("2", javascript.get())
        ui.onNodeWithContentDescription("Закрыть окно входа").performClick()
        ui.waitUntil(5_000) { model.popup.value == null }
        ui.runOnUiThread { assertSame(original, model.activeWeb()) }
        capture("03-connected-fixture")
    }
}
