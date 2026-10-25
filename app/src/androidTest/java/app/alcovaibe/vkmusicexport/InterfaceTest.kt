package app.alcovaibe.vkmusicexport

import android.graphics.Bitmap
import android.app.UiModeManager
import android.content.Context
import androidx.compose.ui.test.*
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
        ui.onNodeWithText("v ${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        ui.onNodeWithText("Открыть мою музыку").performScrollTo().assertIsDisplayed()
        for(label in listOf("Открыть ВК в Chrome","Сбор из Chrome","Импорт JSON","Экспорт"))ui.onNodeWithText(label).assertDoesNotExist()
        capture("01-home")
        ui.onNodeWithText("Открыть мою музыку").performClick()
        ui.onNodeWithTag("native_music_screen").assertExists()
        ui.onNodeWithText("Файлы из предыдущей версии").assertDoesNotExist()
        capture("02-native-music")
        ui.onNodeWithContentDescription("Назад").performClick()
        ui.onNodeWithText("Музыка ВК").assertExists()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("Музыка ВК").assertExists()
        capture("03-home-recreated")
        fun noEmbeddedBrowser(view: android.view.View) {
            assertFalse(view.javaClass.name.startsWith("android.webkit."))
            if(view is android.view.ViewGroup)repeat(view.childCount){noEmbeddedBrowser(view.getChildAt(it))}
        }
        ui.runOnUiThread{noEmbeddedBrowser(ui.activity.window.decorView)}
        ui.onNodeWithContentDescription("Настройки").performClick()
        ui.onNodeWithText("Открыть ВК в Chrome").assertDoesNotExist()
        ui.onNodeWithText("ВКонтакте").assertDoesNotExist()
        ui.onNodeWithText("Очистить коллекцию").assertDoesNotExist()
        ui.onNodeWithText("Прямой доступ ВК · эксперимент").assertDoesNotExist()
        ui.onNodeWithText("v ${BuildConfig.VERSION_NAME}").assertDoesNotExist()
        ui.onNodeWithText("VK Music Export · ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})").assertDoesNotExist()
        ui.onNodeWithText("Обновления приложения").performScrollTo().assertExists()
        capture("settings-without-duplicate-entry")
        ui.onNodeWithContentDescription("Назад").performClick()
        ui.runOnUiThread{(context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).setApplicationNightMode(UiModeManager.MODE_NIGHT_YES)}
        ui.waitUntil(10_000){ui.activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK==android.content.res.Configuration.UI_MODE_NIGHT_YES&&
            ui.activity.window.insetsController?.systemBarsAppearance?.and(android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)==0}
        ui.onNodeWithText("Музыка ВК").assertExists()
        capture("04-dark-home")
        ui.runOnUiThread{(context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).setApplicationNightMode(UiModeManager.MODE_NIGHT_NO)}
    }
}
