package app.alcovaibe.vkmusicexport

import android.graphics.Bitmap
import android.app.UiModeManager
import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

    @Test fun nativeMusicEntryAndHomeSurviveRecreationWithoutRetiredScreens() {
        awaitTestWindowFocus(ui.activity)
        ui.onNodeWithTag("native_music_screen").assertExists()
        ui.onNodeWithTag("library_category_TRACKS").assertIsDisplayed()
        ui.onNodeWithTag("library_category_ARTISTS").assertIsDisplayed()
        ui.onNodeWithTag("music_nav_SAVED").assertIsDisplayed()
        val pixels=ui.onNodeWithTag("native_music_screen").captureToImage().toPixelMap()
        assertEquals(androidx.compose.ui.graphics.Color(0xFFF7F4EE),pixels[1,pixels.height/2])
        for(label in listOf("Музыка и плейлисты ВК","Ручной доступ","Изменить доступ ВК","Данные и доступ","Лицензия"))
            ui.onNodeWithText(label).assertDoesNotExist()
        capture("01-library")
        ui.onNodeWithTag("music_connection").performClick()
        ui.onNodeWithText("Доступ к музыке").assertIsDisplayed()
        ui.onNodeWithText("Прямой доступ ВК").assertDoesNotExist()
        capture("02-music-access")
        ui.onNodeWithContentDescription("Назад").performClick()
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("native_music_screen").assertExists()
        capture("03-library-recreated")
        fun noEmbeddedBrowser(view: android.view.View) {
            assertFalse(view.javaClass.name.startsWith("android.webkit."))
            if(view is android.view.ViewGroup)repeat(view.childCount){noEmbeddedBrowser(view.getChildAt(it))}
        }
        ui.runOnUiThread{noEmbeddedBrowser(ui.activity.window.decorView)}
        ui.onNodeWithContentDescription("Меню").performClick()
        ui.onNodeWithText("Поделиться JSON-отчётом").assertIsDisplayed()
        capture("05-library-menu")
        ui.onNodeWithText("Лицензия").assertDoesNotExist()
        ui.onNodeWithText("Данные и доступ").assertDoesNotExist()
        ui.onNodeWithTag("app_version").assertDoesNotExist()
        ui.onNodeWithTag("open_about").performClick()
        ui.onNodeWithTag("about_app_screen").assertIsDisplayed()
        ui.onNodeWithTag("app_version").assertTextEquals("Версия ${BuildConfig.VERSION_NAME}")
        capture("05-about-app")
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("about_app_screen").assertIsDisplayed()
        ui.onNodeWithTag("open_privacy").performClick()
        ui.onNodeWithTag("legal_sheet").assertIsDisplayed()
        ui.onNodeWithText("Данные и доступ к ВК").assertIsDisplayed()
        ui.onNodeWithText("Закрыть").performClick()
        ui.onNodeWithTag("open_licenses").performClick()
        ui.onNodeWithText("Лицензии компонентов").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        ui.onNodeWithTag("about_app_screen").assertIsDisplayed()
        ui.onNodeWithContentDescription("Назад").performClick()
        ui.onNodeWithTag("app_version").assertDoesNotExist()
        ui.onNodeWithTag("music_nav_SAVED").performClick()
        ui.onNodeWithTag("saved_artwork").assertIsDisplayed()
        ui.onNodeWithTag("saved_track_metadata").assertIsDisplayed()
        ui.onNodeWithTag("saved_file_recovery").assertIsDisplayed()
        capture("06-saved-tools")
        ui.onNodeWithTag("music_nav_DOWNLOADS").performClick()
        ui.onNodeWithTag("downloads_status").assertIsDisplayed()
        capture("07-downloads")
        ui.onNodeWithTag("music_nav_LIBRARY").performClick()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        ui.runOnUiThread{(context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).setApplicationNightMode(UiModeManager.MODE_NIGHT_YES)}
        ui.waitUntil(10_000){ui.activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK==android.content.res.Configuration.UI_MODE_NIGHT_YES&&
            ui.activity.window.insetsController?.systemBarsAppearance?.and(android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)!=0}
        ui.onNodeWithTag("native_music_screen").assertExists()
        capture("04-library-system-dark")
        ui.runOnUiThread{(context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).setApplicationNightMode(UiModeManager.MODE_NIGHT_NO)}
    }
}
