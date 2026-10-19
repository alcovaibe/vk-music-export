package app.alcovaibe.vkmusicexport

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.data.*
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ImportDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun nativeFilePickerPreviewCancelCommitAndInvalidInput() {
        val context=ui.activity
        val old=MusicSource("my-music","Моя музыка","https://vk.ru/audio",listOf(Entry("old",Track(title="Old",artist="Artist"),1)))
        val untouched=MusicSource("playlist:9:9","Keep","https://vk.ru/music/playlist/9_9",listOf(Entry("k",Track(title="Keep",artist="Artist"),1)))
        runBlocking {CollectionStore(context).save(CollectionSnapshot(sources=listOf(old,untouched)))}
        ui.runOnUiThread {ui.activity.viewModelStore.clear()};ui.activityRule.scenario.recreate()
        val model=ViewModelProvider(ui.activity)[MusicViewModel::class.java]
        ui.waitUntil(10000){model.ready.value}
        val saved=ui.activity.getFileStreamPath("collection.json").readBytes()
        ui.onNodeWithText("Сбор из Chrome").performScrollTo().performClick()
        ui.onNodeWithText("Скопировать закладку").performScrollTo().performClick()
        ui.waitUntil(10000) {
            var copied=false
            ui.runOnUiThread {
                val clip=(ui.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                copied=clip.startsWith("javascript:") && android.net.Uri.decode(clip).contains("vkm-export-panel")
            }
            copied
        }
        ui.onNodeWithText("Закрыть").performClick()
        val file=File(ui.activity.cacheDir,"diagnostic-export/import-fixture.json").apply{parentFile!!.mkdirs()}
        val raw=InstrumentationRegistry.getInstrumentation().context.assets.open("chrome-capture.json").bufferedReader().use{it.readText()}
        file.writeText(raw)
        val uri=FileProvider.getUriForFile(ui.activity,"${ui.activity.packageName}.diagnostics",file)
        Intents.init()
        try {
            Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().setData(uri)))
            fun choose() {ui.onNodeWithText("Импорт JSON").performScrollTo().performClick();ui.waitUntil(10000){!model.busy.value}}
            choose();ui.waitUntil(10000){model.pendingImport.value!=null}
            ui.onNodeWithText("Заменить список").assertIsDisplayed()
            assertArrayEquals(saved,ui.activity.getFileStreamPath("collection.json").readBytes())
            ui.onNodeWithText("Отмена").performClick();assertArrayEquals(saved,ui.activity.getFileStreamPath("collection.json").readBytes())
            choose();ui.waitUntil(10000){model.pendingImport.value!=null};ui.onNodeWithText("Заменить список").performClick()
            ui.waitUntil(10000){!model.busy.value && model.snapshot.value.entriesCount==3}
            assertEquals(2,model.snapshot.value.sources.first{it.id=="my-music"}.entries.size)
            assertEquals(untouched,model.snapshot.value.sources.first{it.id==untouched.id})
            assertEquals("NOT_SCANNED",model.snapshot.value.sources.first{it.id=="playlist:123:5"}.reason)
            val after=ui.activity.getFileStreamPath("collection.json").readBytes()
            file.writeText(raw.replace("\"kind\":","\"token\":\"PRIVATE_IMPORT_SECRET\",\"kind\":"))
            choose();ui.waitUntil(10000){model.message.value?.contains("Не удалось прочитать") == true}
            assertNull(model.pendingImport.value);assertArrayEquals(after,ui.activity.getFileStreamPath("collection.json").readBytes())
            val worker=Executors.newSingleThreadExecutor()
            try {val log=worker.submit<String>{Diagnostics.export()}.get(15,TimeUnit.SECONDS);assertFalse(log.contains("PRIVATE_IMPORT_SECRET"));assertFalse(log.contains("Новая песня"))}finally{worker.shutdown()}
            ui.runOnUiThread{ui.activity.viewModelStore.clear()};ui.activityRule.scenario.recreate()
            val restored=ViewModelProvider(ui.activity)[MusicViewModel::class.java];ui.waitUntil(10000){restored.ready.value}
            assertEquals(3,restored.snapshot.value.entriesCount)
        }finally {Intents.release();runBlocking{CollectionStore(ui.activity).save(CollectionSnapshot())}}
    }
}
