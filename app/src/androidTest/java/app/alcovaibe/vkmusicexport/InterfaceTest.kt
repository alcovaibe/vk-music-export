package app.alcovaibe.vkmusicexport

import android.graphics.Bitmap
import android.app.UiModeManager
import android.content.Context
import app.alcovaibe.vkmusicexport.data.*
import kotlinx.coroutines.runBlocking
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

@androidx.test.filters.SdkSuppress(minSdkVersion = 36)
@RunWith(AndroidJUnit4::class)
class InterfaceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun dismissEmulatorDialog() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val root = automation.rootInActiveWindow ?: return
        val queue = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        val nodes = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst(); nodes.add(node)
            repeat(node.childCount) { node.getChild(it)?.let(queue::add) }
        }
        val caption = nodes.mapNotNull { it.text?.toString() }.firstOrNull {
            it.contains("responding") && (it.contains("Quickstep") || it.contains("System UI"))
        } ?: return
        val action = if (caption.contains("System UI")) "Wait" else "Close app"
        nodes.firstOrNull { it.text?.toString() == action }?.let { label ->
            val target = if (label.isClickable) label else label.parent
            check(target?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true)
            android.util.Log.i("VKMusicTest", "Dismissed known emulator system dialog. App-under-test ANRs are never dismissed.")
        }
    }

    private fun capture(name: String) {
        dismissEmulatorDialog()
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

    @Test fun chromeDispatchPreservesCollectionAndNativeExport() {
        val model = ViewModelProvider(ui.activity)[MusicViewModel::class.java]
        ui.waitUntil(10_000) { model.ready.value }
        ui.onNodeWithText("Открыть ВК в Chrome").performScrollTo().assertIsDisplayed()
        capture("01-collection")
        ui.onNodeWithText("Экспорт").performClick()
        ui.onNodeWithText("Забрать с собой").assertIsDisplayed()
        capture("02-export")
        ui.onNodeWithText("Коллекция").performClick()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val track = Track("123_1", "Демо-трек", "Демо-исполнитель")
        val demo = CollectionSnapshot(sources = listOf(
            MusicSource("my-music", "Моя музыка", "https://vk.ru/audio", listOf(Entry("0", track, 1),Entry("1",track,2))),
            MusicSource("playlist:123:1", "Для дороги", "https://vk.ru/audio", listOf(Entry("0", track, 1)), status = ScanStatus.SCANNING)
        ))
        runBlocking { CollectionStore(context).save(demo) }
        val before=context.getFileStreamPath("collection.json").readBytes()
        ui.runOnUiThread { ui.activity.viewModelStore.clear() }
        ui.activityRule.scenario.recreate()
        ui.waitUntil(10_000) { ViewModelProvider(ui.activity)[MusicViewModel::class.java].ready.value }
        ui.onNodeWithText("Для дороги").performScrollTo().assertIsDisplayed()
        capture("03-library-fixture")
        androidx.test.espresso.intent.Intents.init()
        try {
            val chrome=org.hamcrest.Matchers.allOf(
                androidx.test.espresso.intent.matcher.IntentMatchers.hasAction(android.content.Intent.ACTION_VIEW),
                androidx.test.espresso.intent.matcher.IntentMatchers.hasPackage("com.android.chrome"),
                androidx.test.espresso.intent.matcher.IntentMatchers.hasData("https://vk.ru/"))
            // Intercept dispatch: this tests our real native button/intent, not Chrome or VK login.
            androidx.test.espresso.intent.Intents.intending(chrome).respondWith(android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED,null))
            ui.onNodeWithText("Открыть ВК в Chrome").performScrollTo().performClick()
            awaitTestWindowFocus(ui.activity)
            androidx.test.espresso.intent.Intents.intended(chrome)
            assertArrayEquals(before,context.getFileStreamPath("collection.json").readBytes())
            ui.onNodeWithText("ВК подключён",substring=true).assertDoesNotExist()
            ui.onNodeWithText("Собрать всё").assertDoesNotExist()
            fun noEmbeddedBrowser(view: android.view.View) {
                assertFalse("Embedded browser must not be created",view.javaClass.name.startsWith("android.webkit."))
                if(view is android.view.ViewGroup) repeat(view.childCount){noEmbeddedBrowser(view.getChildAt(it))}
            }
            ui.runOnUiThread {noEmbeddedBrowser(ui.activity.window.decorView)}
            val file=File(context.cacheDir,"diagnostic-export/music-export-test.json").apply {parentFile!!.mkdirs()}
            val uri=androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.diagnostics",file)
            androidx.test.espresso.intent.Intents.intending(androidx.test.espresso.intent.matcher.IntentMatchers.hasAction(android.content.Intent.ACTION_CREATE_DOCUMENT))
                .respondWith(android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK,android.content.Intent().setData(uri)))
            ui.onNodeWithText("Экспорт").performClick()
            ui.onNodeWithText("JSON").performScrollTo().performClick()
            ui.onNodeWithText("Сохранить JSON").performScrollTo().performClick()
            ui.waitUntil(10_000) {runCatching {kotlinx.serialization.json.Json.decodeFromString<CollectionSnapshot>(file.readText()).entriesCount==3}.getOrDefault(false)}
            val exported=kotlinx.serialization.json.Json.decodeFromString<CollectionSnapshot>(file.readText())
            assertEquals(demo.sources.map { if(it.status==ScanStatus.SCANNING) it.copy(status=ScanStatus.INTERRUPTED,reason="PROCESS_STOPPED") else it },exported.sources)
            assertArrayEquals(before,context.getFileStreamPath("collection.json").readBytes())
        } finally { androidx.test.espresso.intent.Intents.release() }
        ui.onNodeWithText("Коллекция").performClick()
        ui.runOnUiThread {
            (context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).setApplicationNightMode(UiModeManager.MODE_NIGHT_YES)
        }
        ui.waitUntil(10_000) { ui.activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES &&
            ui.activity.window.insetsController?.systemBarsAppearance?.and(android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS) == 0 }
        ui.onNodeWithText("Для дороги").performScrollTo().assertIsDisplayed()
        capture("04-dark-library-fixture")
        ui.runOnUiThread {
            (context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).setApplicationNightMode(UiModeManager.MODE_NIGHT_NO)
        }
        runBlocking { CollectionStore(context).save(CollectionSnapshot()) }
    }
    @Test fun unavailableChromeIsReportedWithoutAnotherBrowser() {
        var attempts=0
        val wrapper=object: android.content.ContextWrapper(ui.activity) {
            override fun startActivity(intent: android.content.Intent) {
                attempts++
                assertEquals("com.android.chrome",intent.`package`)
                assertEquals("https://vk.ru/",intent.dataString)
                throw android.content.ActivityNotFoundException("Synthetic unavailable Chrome")
            }
        }
        ui.runOnUiThread { assertFalse(app.alcovaibe.vkmusicexport.vk.ChromeBrowser.open(wrapper)) }
        assertEquals(1,attempts)
    }
}
