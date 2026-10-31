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
        ui.onNodeWithText("v ${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        ui.onNodeWithTag("check_updates").assertIsDisplayed()
        val updateBounds=ui.onNodeWithTag("check_updates").fetchSemanticsNode().boundsInRoot
        val settingsBounds=ui.onNodeWithContentDescription("Меню").fetchSemanticsNode().boundsInRoot
        val versionBounds=ui.onNodeWithText("v ${BuildConfig.VERSION_NAME}").fetchSemanticsNode().boundsInRoot
        assertTrue(updateBounds.top>settingsBounds.bottom)
        assertTrue(updateBounds.left>versionBounds.left)
        assertTrue(updateBounds.top<=versionBounds.center.y&&updateBounds.bottom>=versionBounds.center.y)
        ui.onNodeWithText("VK Music").performScrollTo().assertIsDisplayed()
        for(label in listOf("Открыть ВК в Chrome","Сбор из Chrome","Импорт JSON","Экспорт"))ui.onNodeWithText(label).assertDoesNotExist()
        val pixels=ui.onNodeWithTag("home_screen").captureToImage().toPixelMap()
        assertEquals(androidx.compose.ui.graphics.Color.Black,pixels[1,pixels.height/2])
        ui.onNodeWithText("Музыка и плейлисты ВК").assertDoesNotExist()
        ui.onNodeWithText("Открой музыкальный раздел, подключи ВК и сохраняй треки в Music/VK Music Export.").assertDoesNotExist()
        ui.onNodeWithText("Данные и доступ").assertDoesNotExist()
        ui.onNodeWithText("Лицензия").assertDoesNotExist()
        capture("01-home")
        ui.onNodeWithText("VK Music").performClick()
        ui.onNodeWithTag("native_music_screen").assertExists()
        ui.onNodeWithText("Файлы из предыдущей версии").assertDoesNotExist()
        ui.onNodeWithText("Данные и доступ").assertDoesNotExist()
        ui.onNodeWithText("Лицензии").assertDoesNotExist()
        ui.onNodeWithText("Ручной доступ").assertDoesNotExist()
        ui.onNodeWithText("Изменить доступ ВК").assertDoesNotExist()
        capture("02-native-music")
        ui.onNodeWithContentDescription("Назад").performClick()
        ui.onNodeWithText("VK Music").assertExists()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("VK Music").assertExists()
        capture("03-home-recreated")
        fun noEmbeddedBrowser(view: android.view.View) {
            assertFalse(view.javaClass.name.startsWith("android.webkit."))
            if(view is android.view.ViewGroup)repeat(view.childCount){noEmbeddedBrowser(view.getChildAt(it))}
        }
        ui.runOnUiThread{noEmbeddedBrowser(ui.activity.window.decorView)}
        ui.onNodeWithContentDescription("Меню").performClick()
        ui.onNodeWithText("Поделиться JSON-отчётом").assertIsDisplayed()
        for(label in listOf("Настройки","Диагностика","Копировать лог","Другие логи","Поделиться предыдущим JSON","Музыка ВК","Открыть мою музыку"))
            ui.onNodeWithText(label).assertDoesNotExist()
        ui.onNodeWithTag("settings_screen").assertDoesNotExist()
        ui.onNodeWithTag("check_updates").assertExists()
        capture("05-home-report-menu")
        ui.onNodeWithTag("open_privacy").performClick()
        ui.onNodeWithTag("legal_sheet").assertIsDisplayed()
        ui.onNodeWithText("Данные и доступ к ВК").assertIsDisplayed()
        ui.onNodeWithText("Закрыть").performClick()
        ui.onNodeWithTag("legal_sheet").assertDoesNotExist()
        ui.onNodeWithContentDescription("Меню").performClick()
        ui.onNodeWithTag("open_licenses").performClick()
        ui.onNodeWithText("Лицензии компонентов").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        ui.onNodeWithTag("legal_sheet").assertDoesNotExist()
        ui.runOnUiThread{(context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).setApplicationNightMode(UiModeManager.MODE_NIGHT_YES)}
        ui.waitUntil(10_000){ui.activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK==android.content.res.Configuration.UI_MODE_NIGHT_YES&&
            ui.activity.window.insetsController?.systemBarsAppearance?.and(android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)==0}
        ui.onNodeWithText("VK Music").assertExists()
        capture("04-dark-home")
        ui.runOnUiThread{(context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).setApplicationNightMode(UiModeManager.MODE_NIGHT_NO)}
    }
}
