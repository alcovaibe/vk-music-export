package app.alcovaibe.vkmusicexport

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.alcovaibe.vkmusicexport.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeCollectionDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun savingNativePageRequiresConfirmationKeepsOtherListsAndCancelPreservesDisk() {
        val track=Track("12_34","Native page fixture","CI")
        val old=MusicSource("my-music","Old library","https://vk.ru/audio",listOf(Entry("pos:0",track,1)))
        val other=old.copy(id="playlist:12:8",title="Other list")
        val original=CollectionSnapshot(sources=listOf(old,other))
        runBlocking{CollectionStore(ui.activity).save(original)}
        ui.runOnUiThread{ui.activity.viewModelStore.clear()};ui.activityRule.scenario.recreate()
        val model=ViewModelProvider(ui.activity)[MusicViewModel::class.java]
        ui.waitUntil(10000){model.ready.value}
        val before=ui.activity.getFileStreamPath("collection.json").readBytes()
        val page=old.copy(title="New page",entries=listOf(Entry("pos:100",track,101),Entry("pos:101",track,102)),expectedCount=4449,status=ScanStatus.PARTIAL)
        ui.runOnUiThread{model.stageNativePage(page)}
        ui.onNodeWithText("Сохранение страницы ВК").assertExists()
        assertArrayEquals(before,ui.activity.getFileStreamPath("collection.json").readBytes())
        ui.onNodeWithText("Отмена").performClick();assertNull(model.pendingCollection.value)
        assertArrayEquals(before,ui.activity.getFileStreamPath("collection.json").readBytes())
        ui.runOnUiThread{model.stageNativePage(page)}
        ui.onNodeWithText("Заменить список").performClick()
        ui.waitUntil(10000){!model.busy.value&&model.pendingCollection.value==null}
        val saved=runBlocking{CollectionStore(ui.activity).read()}
        assertEquals(listOf(page,other),saved.sources)
        assertEquals(listOf(101,102),saved.sources.first().entries.map{it.position})
        runBlocking{CollectionStore(ui.activity).save(CollectionSnapshot())}
    }
}
