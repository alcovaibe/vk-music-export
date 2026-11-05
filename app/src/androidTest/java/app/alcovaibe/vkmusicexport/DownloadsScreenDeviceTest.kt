package app.alcovaibe.vkmusicexport

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadsScreenDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun fixture(block:suspend(NativeMusicViewModel,Context)->Unit)=runBlocking<Unit> {
        val context=ui.activity.applicationContext
        lateinit var model: NativeMusicViewModel
        ui.runOnUiThread{model=ViewModelProvider(ui.activity)[NativeMusicViewModel::class.java]}
        ui.waitUntil(20_000){model.state.value.initialized}
        withContext(Dispatchers.IO){AudioExportRuntime.initialize(context);AudioExportRuntime.clear(context)}
        try{block(model,context)}finally {
            ui.runOnUiThread{model.dismissDownloads();PlaybackRuntime.stop()}
            withContext(Dispatchers.IO){AudioExportRuntime.pause(context);withTimeout(20_000){while(AudioExportRuntime.active.get())delay(20)};AudioExportRuntime.clear(context)}
        }
    }
    private fun open() {
        ui.onNodeWithTag("open_music_downloads").performClick()
        ui.waitUntil(10_000){ui.onAllNodesWithTag("downloads_screen").fetchSemanticsNodes().isNotEmpty()}
    }
    @Test fun downloadsReturnToSavedAndFriendsAndNotificationOpensTheSameQueue()=fixture{model,context->
        ui.runOnUiThread{model.openMaintenanceNotification("saved_files_background")}
        ui.waitUntil(10_000){ui.onAllNodesWithTag("saved_file_recovery").fetchSemanticsNodes().isNotEmpty()}
        ui.runOnUiThread{model.dismissSavedFiles()}
        ui.onNodeWithTag("music_nav_SAVED").performClick();open()
        ui.onNodeWithTag("downloads_sheet").assertDoesNotExist();ui.onNodeWithTag("music_nav_DOWNLOADS").assertDoesNotExist()
        androidx.test.espresso.Espresso.pressBack();ui.onNodeWithTag("music_nav_SAVED").assertIsSelected()
        ui.onNodeWithTag("music_nav_FRIENDS").performClick();open()
        ui.activityRule.scenario.recreate();ui.onNodeWithTag("downloads_screen").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack();ui.onNodeWithTag("music_nav_FRIENDS").assertIsSelected()
        ui.onNodeWithTag("music_nav_SAVED").performClick()
        val openedActivity=ui.activity;val launchAction=openedActivity.intent.action;val launchCategories=openedActivity.intent.categories
        val notification=withContext(Dispatchers.IO){AudioExportRuntime.notification(context,AudioExportRuntime.state.value)}
        ui.runOnUiThread{notification.contentIntent.send()}
        ui.waitUntil(10_000){ui.onAllNodesWithTag("downloads_screen").fetchSemanticsNodes().isNotEmpty()}
        assertSame(openedActivity,ui.activity)
        assertEquals(launchAction,ui.activity.intent.action);assertEquals(launchCategories,ui.activity.intent.categories)
        assertFalse(ui.activity.intent.hasExtra(AudioExportRuntime.OPEN_EXTRA))
        androidx.test.espresso.Espresso.pressBack();ui.onNodeWithTag("music_nav_SAVED").assertIsSelected()
        ui.activityRule.scenario.recreate();ui.onNodeWithTag("music_nav_SAVED").assertIsSelected()
        ui.onNodeWithTag("downloads_screen").assertDoesNotExist()
    }
    @Test fun errorsAreReadByPageAndLargeTextKeepsTabsBackAndClearUsable()=fixture{model,context->
        val tasks=(1..120).map{AudioExportTask("downloads-screen-$it",NativeTrack("12_${970000+it}","Трек $it","Проверка",180),listOf("CI downloads"),it,ExportSource(12,"library","my"))}
        val before=withContext(Dispatchers.IO){AudioExportStore(context).use{it.savedFiles().map{row->row.taskKey to row.uri}.toSet()}}
        withContext(Dispatchers.IO) {
            AudioExportStore(context).use{store->
                store.replace(tasks,account=12,sources=listOf(ExportSourceFailure(ExportSource(12,"catalog","ci"),"Плейлист с ошибкой",MusicError.NETWORK,PlaylistKind.PLAYLIST)))
                val db=store.writableDatabase;db.beginTransaction()
                try {
                    tasks.take(3).forEach{db.execSQL("UPDATE tasks SET state='DONE' WHERE task_key=?",arrayOf(it.key))}
                    tasks.drop(3).take(60).forEach{store.failed(it,MusicError.HTTP,ExportFailureDetails(httpStatus=503))}
                    store.claim();store.status("RUNNING");db.setTransactionSuccessful()
                }finally{db.endTransaction()}
            }
            AudioExportRuntime.refresh()
        }
        open()
        ui.waitUntil(10_000){model.downloadsPage.value.page!=null&&!model.downloadsPage.value.loading}
        ui.waitForIdle();captureDownloads("downloads-fullscreen-running")
        androidx.test.espresso.Espresso.pressBack()
        ui.runOnUiThread{ui.activity.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f)){MusicTheme{NativeMusicPanel(model,true,{})}}
        }}
        open()
        ui.onNodeWithTag("downloads_screen_list").performScrollToNode(hasTestTag("downloads_tab_ERRORS"))
        ui.onNodeWithTag("downloads_tab_ERRORS").performClick()
        ui.waitUntil(10_000){model.downloadsPage.value.page?.tab==DownloadsTab.ERRORS&&!model.downloadsPage.value.loading}
        assertEquals(61,model.downloadsPage.value.page!!.total);assertEquals(49,model.downloadsPage.value.page!!.rows.size)
        ui.onNodeWithTag("downloads_screen_list").performScrollToNode(hasTestTag("downloads_next_page"))
        ui.onNodeWithTag("downloads_next_page").performClick()
        ui.waitUntil(10_000){model.downloadsPage.value.page?.offset==50&&!model.downloadsPage.value.loading}
        assertEquals(11,model.downloadsPage.value.page!!.rows.size)
        ui.onNodeWithTag("downloads_screen_list").performScrollToNode(hasTestTag("download_task_${tasks[62].key}"))
        ui.onNodeWithTag("download_task_${tasks[62].key}").assertIsDisplayed()
        ui.onNodeWithTag("downloads_screen_list").performScrollToNode(hasTestTag("downloads_tab_DONE"))
        ui.onNodeWithTag("downloads_tab_DONE").performClick()
        ui.waitUntil(10_000){model.downloadsPage.value.page?.tab==DownloadsTab.DONE&&!model.downloadsPage.value.loading}
        assertEquals(3,model.downloadsPage.value.page!!.rows.size)
        ui.waitForIdle();captureDownloads("downloads-fullscreen-large-text")
        ui.onNodeWithTag("downloads_screen_list").performScrollToNode(hasTestTag("downloads_pause"))
        ui.onNodeWithTag("downloads_pause").performClick()
        ui.waitUntil(10_000){AudioExportRuntime.state.value.status=="PAUSED"}
        ui.onNodeWithTag("downloads_screen_list").performScrollToNode(hasTestTag("downloads_clear"))
        ui.onNodeWithTag("downloads_clear").performClick();ui.waitUntil(10_000){AudioExportRuntime.state.value.total==0}
        val after=withContext(Dispatchers.IO){AudioExportStore(context).use{it.savedFiles().map{row->row.taskKey to row.uri}.toSet()}}
        assertEquals(before,after)
        androidx.test.espresso.Espresso.pressBack();ui.onNodeWithTag("native_music_screen").assertExists()
    }
}
