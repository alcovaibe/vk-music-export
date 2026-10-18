package app.alcovaibe.vkmusicexport

import android.graphics.Bitmap
import android.app.UiModeManager
import android.content.Context
import app.alcovaibe.vkmusicexport.data.*
import kotlinx.coroutines.runBlocking
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
import android.os.ParcelFileDescriptor

@androidx.test.filters.SdkSuppress(minSdkVersion = 31)
@RunWith(AndroidJUnit4::class)
class InterfaceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun capture(name: String) {
        ui.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(200, 5_000)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        val file = File(dir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        // AGP uninstalls the app after instrumentation; keep test images outside app data.
        listOf("mkdir -p /data/local/tmp/vk-music-export-screenshots", "cp ${file.absolutePath} /data/local/tmp/vk-music-export-screenshots/$name.png").forEach { command ->
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
    }

    @Test fun navigationPreservesBrowserAndPopupRunsJavascript() {
        val model = ViewModelProvider(ui.activity)[MusicViewModel::class.java]
        ui.waitUntil(10_000) { model.ready.value }
        ui.waitUntil(10_000) { ui.onAllNodesWithText("Подключить ВКонтакте").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Подключить ВКонтакте").assertIsDisplayed()
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
            """.trimIndent(), "text/html", "UTF-8", "https://vk.ru/audio")
        }
        try { ui.waitUntil(15_000) { model.connected.value } }
        catch (error: Throwable) {
            capture("connection-failure")
            throw AssertionError("Fixture connection failed: ${model.lastCode.value}; ${model.message.value}; ${model.currentPage.value}", error)
        }
        ui.onNodeWithContentDescription("К коллекции").performClick()
        ui.onNodeWithText("Экспорт").performClick()
        ui.runOnUiThread {
            assertTrue("Hidden browser must keep a usable viewport", original.width > 100)
            assertEquals(android.view.View.INVISIBLE, original.visibility)
        }
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
            child.loadDataWithBaseURL("https://id.vk.com/", "<h1>Authorization test fixture</h1>", "text/html", "UTF-8", "https://id.vk.com/")
        }
        ui.onNodeWithContentDescription("Закрыть окно входа").assertIsDisplayed()
        val javascript = AtomicReference<String?>(null)
        ui.waitUntil(10_000) { !model.pageLoading.value }
        ui.runOnUiThread { child.evaluateJavascript("1 + 1") { javascript.set(it) } }
        ui.waitUntil(5_000) { javascript.get() != null }
        assertEquals("2", javascript.get())
        ui.runOnUiThread { child.evaluateJavascript("window.close()", null) }
        ui.waitUntil(5_000) { model.popup.value == null }
        ui.runOnUiThread { assertSame(original, model.activeWeb()) }
        capture("03-connected-fixture")

        ui.onNodeWithContentDescription("К коллекции").performClick()
        // Seed a synthetic collection through the real persistence layer and relaunch.
        val context = instrumentation.targetContext
        val track = Track("123_1", "Демо-трек", "Демо-исполнитель")
        val demo = CollectionSnapshot(sources = listOf(
            MusicSource("my-music", "Моя музыка", "https://vk.ru/audio", listOf(Entry("0", track, 1))),
            MusicSource("playlist:123:1", "Для дороги", "https://vk.ru/audio", listOf(Entry("0", track, 1)), status = ScanStatus.PARTIAL)
        ))
        runBlocking { CollectionStore(context).save(demo) }
        ui.runOnUiThread { ui.activity.viewModelStore.clear() }
        ui.activityRule.scenario.recreate()
        ui.waitUntil(10_000) { ViewModelProvider(ui.activity)[MusicViewModel::class.java].ready.value }
        ui.onNodeWithText("Для дороги").performScrollTo().assertIsDisplayed()
        capture("04-library-fixture")
        ui.runOnUiThread {
            (context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).setApplicationNightMode(UiModeManager.MODE_NIGHT_YES)
        }
        ui.waitUntil(10_000) { ui.activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES &&
            !androidx.core.view.WindowCompat.getInsetsController(ui.activity.window, ui.activity.window.decorView).isAppearanceLightStatusBars }
        ui.onNodeWithText("Для дороги").performScrollTo().assertIsDisplayed()
        capture("05-dark-library-fixture")
        ui.runOnUiThread {
            (context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).setApplicationNightMode(UiModeManager.MODE_NIGHT_NO)
        }
        runBlocking { CollectionStore(context).save(CollectionSnapshot()) }
    }
}
