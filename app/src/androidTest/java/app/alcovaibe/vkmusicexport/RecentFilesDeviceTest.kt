package app.alcovaibe.vkmusicexport

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.alcovaibe.vkmusicexport.music.RecentFilesHeading
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecentFilesDeviceTest {
    @get:Rule val ui=createComposeRule()

    @Test fun recentHistoryRequiresConfirmationAndCancelKeepsTheList() {
        var clears=0
        ui.setContent{MusicTheme{Surface{RecentFilesHeading{clears++}}}}
        ui.onNodeWithTag("clear_recent_files").assertIsDisplayed().performClick()
        ui.onNodeWithTag("clear_recent_files_sheet").assertIsDisplayed()
        ui.onNodeWithText("Сами файлы останутся на устройстве.").assertIsDisplayed()
        ui.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val screenshot=instrumentation.uiAutomation.takeScreenshot()
        val file=File(instrumentation.targetContext.getExternalFilesDir(null),"screenshots/recent-files-clear.png").apply{parentFile!!.mkdirs()}
        file.outputStream().use{screenshot.compress(Bitmap.CompressFormat.PNG,100,it)};screenshot.recycle()
        for(command in listOf("mkdir -p /data/local/tmp/vk-music-export-screenshots",
            "cp ${file.absolutePath} /data/local/tmp/vk-music-export-screenshots/recent-files-clear.png")) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use{it.readBytes()}
        }
        ui.onNodeWithText("Отмена").performClick()
        ui.onNodeWithTag("clear_recent_files_sheet").assertDoesNotExist()
        ui.runOnIdle{assertEquals(0,clears)}
        ui.onNodeWithTag("clear_recent_files").performClick()
        ui.onNodeWithTag("confirm_clear_recent_files").assertIsDisplayed().performClick()
        ui.onNodeWithTag("clear_recent_files_sheet").assertDoesNotExist()
        ui.runOnIdle{assertEquals(1,clears)}
    }
}
