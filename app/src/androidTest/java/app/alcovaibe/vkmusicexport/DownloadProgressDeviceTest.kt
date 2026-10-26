package app.alcovaibe.vkmusicexport

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

private fun captureDownloads(name: String) {
    val instrumentation=InstrumentationRegistry.getInstrumentation()
    instrumentation.waitForIdleSync();instrumentation.uiAutomation.waitForIdle(200,5000)
    val image=instrumentation.uiAutomation.takeScreenshot()
    val file=File(instrumentation.targetContext.getExternalFilesDir(null),"screenshots/$name.png").apply{parentFile!!.mkdirs()}
    file.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    for(command in listOf("mkdir -p /data/local/tmp/vk-music-export-screenshots",
        "cp ${file.absolutePath} /data/local/tmp/vk-music-export-screenshots/$name.png")) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use{it.readBytes()}
    }
}

@RunWith(AndroidJUnit4::class)
class DownloadProgressUiDeviceTest {
    @get:Rule val ui=createComposeRule()
    @Test fun liveProgressCanBeDismissedReopenedPausedResumedRetriedAndCleared() {
        val task=ExportLayout.task(NativeTrack("12_99998","Синтетическая песня","CI",1),listOf("Моя музыка"),4)
        var snapshot by mutableStateOf(ExportSnapshot(status="RUNNING",total=10,done=3,pending=6,failed=1,
            currentTask=task,progress=AudioProgress("segments",32768,2,4)))
        var open by mutableStateOf(true)
        var pauses=0;var resumes=0;var retries=0;var clears=0
        ui.setContent {MusicTheme {Surface(Modifier.fillMaxSize()) {
            Column(Modifier.statusBarsPadding().padding(24.dp)){DownloadQueueButton(snapshot){open=true}}
            if(open)DownloadProgressSheet(snapshot,
                onPause={pauses++;snapshot=snapshot.copy(status="PAUSED")},
                onResume={resumes++;snapshot=snapshot.copy(status="RUNNING")},
                onRetry={retries++;snapshot=snapshot.copy(status="QUEUED",pending=7,failed=0)},
                onClear={clears++;snapshot=ExportSnapshot()},onDismiss={open=false})
        }}}
        ui.onNodeWithText("Сохранено: 3 из 10").assertIsDisplayed()
        ui.onNodeWithText("Обработано: 4/10 · 40%").assertIsDisplayed()
        ui.onNodeWithTag("downloads_current_track").assertTextEquals("CI — Синтетическая песня")
        ui.onNodeWithTag("downloads_current_progress").assertTextEquals("Сегменты: 2/4 · получено 32 КБ")
        ui.waitForIdle();captureDownloads("downloads-running")
        ui.onNodeWithTag("downloads_dismiss").performClick()
        ui.onNodeWithTag("downloads_sheet").assertDoesNotExist()
        ui.runOnIdle{assertEquals("RUNNING",snapshot.status);assertEquals(0,pauses)}
        ui.onNodeWithTag("open_home_downloads").performClick()
        ui.onNodeWithTag("downloads_pause").performScrollTo().performClick()
        ui.onNodeWithTag("downloads_resume").performScrollTo().assertIsDisplayed()
        ui.onNodeWithTag("downloads_current_progress").assertDoesNotExist()
        ui.onNodeWithTag("downloads_resume").performClick()
        ui.onNodeWithTag("downloads_pause").performScrollTo().performClick()
        ui.onNodeWithTag("downloads_retry").performScrollTo().performClick()
        ui.onNodeWithTag("downloads_status").assertTextEquals("Ожидание сети и запуска системой")
        ui.runOnIdle{snapshot=snapshot.copy(status="PAUSED")}
        ui.onNodeWithTag("downloads_clear").performScrollTo().performClick()
        ui.onNodeWithText("Очередь скачивания пуста").assertIsDisplayed()
        ui.runOnIdle{assertEquals(2,pauses);assertEquals(1,resumes);assertEquals(1,retries);assertEquals(1,clears)}
    }
}

@RunWith(AndroidJUnit4::class)
class DownloadProgressEntryDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun bothEntriesSurviveRecreationAndStartingAQueueAutomaticallyShowsProgress()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        lateinit var model: NativeMusicViewModel
        ui.runOnUiThread{model=ViewModelProvider(ui.activity)[NativeMusicViewModel::class.java]}
        ui.waitUntil(10000){model.state.value.initialized}
        withContext(Dispatchers.IO){AudioExportRuntime.initialize(context);AudioExportRuntime.clear(context)}
        try {
            ui.onNodeWithTag("open_home_downloads").performScrollTo().performClick()
            ui.onNodeWithTag("downloads_sheet").assertIsDisplayed()
            ui.activityRule.scenario.recreate()
            ui.onNodeWithTag("downloads_sheet").assertIsDisplayed()
            ui.runOnUiThread{model=ViewModelProvider(ui.activity)[NativeMusicViewModel::class.java]}
            ui.onNodeWithTag("downloads_dismiss").performClick()
            ui.onNodeWithText("Открыть мою музыку").performScrollTo().performClick()
            ui.onNodeWithTag("open_music_downloads").assertIsDisplayed().performClick()
            ui.onNodeWithTag("downloads_sheet").assertIsDisplayed()
            androidx.test.espresso.Espresso.pressBack()
            ui.onNodeWithTag("downloads_sheet").assertDoesNotExist()
            ui.onNodeWithTag("native_music_screen").assertExists()
            // A synthetic unavailable item exercises the real UIDT launch without a VK account or music API request.
            ui.runOnUiThread{model.download(NativeTrack("12_99999","CI unavailable","CI",1,restricted=true),1)}
            ui.waitUntil(10000){model.downloadsOpen.value&&!model.state.value.busy}
            ui.onNodeWithTag("downloads_sheet").assertIsDisplayed()
            ui.onNodeWithTag("downloads_dismiss").performClick()
            withTimeout(20000){while(AudioExportRuntime.state.value.unfinished||AudioExportRuntime.active.get())delay(50)}
            assertEquals("FINISHED_WITH_ERRORS",AudioExportRuntime.state.value.status)
            assertEquals(1,AudioExportRuntime.state.value.failed)
            assertFalse(model.downloadsOpen.value)
            ui.onNodeWithTag("open_music_downloads").performClick()
            ui.onNodeWithTag("downloads_status").assertTextEquals("Готово с пропусками")
            ui.onNodeWithTag("downloads_dismiss").performClick()
        }finally {
            ui.runOnUiThread{model.dismissDownloads()}
            withContext(Dispatchers.IO){AudioExportRuntime.pause(context);withTimeout(20000){while(AudioExportRuntime.active.get())delay(50)};AudioExportRuntime.clear(context)}
        }
        Unit
    }
}
