package app.alcovaibe.vkmusicexport

import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
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

@RunWith(AndroidJUnit4::class)
class LibraryRemovalUiDeviceTest {
    @get:Rule val ui=createComposeRule()
    @Test fun basketExistsOnlyInLibraryAndConfirmationRequiresAnExplicitClick() {
        val track=NativeTrack("12_99553","CI","Artist",1)
        val row=PublishedAudio("key",track,"content://media/external_primary/audio/media/1","CI.mp3",ExportLayout.ROOT+"Моя музыка/","mp3","audio/mpeg",1000,10,"a".repeat(64))
        var library by mutableStateOf(false);var state by mutableStateOf(LibraryRemovalState())
        var deletes=0
        ui.setContent{MusicTheme {
            LibraryRemoveButton(library,true){state=LibraryRemovalState(true,LibraryRemovalPhase.CONFIRM,12,track,row)}
            LibraryRemovalSheet(state,onConfirm={deletes++;state=state.copy(phase=LibraryRemovalPhase.DONE,message="Файл сохранён")},onDismiss={state=LibraryRemovalState()})
        }}
        ui.onNodeWithTag("remove_from_vk").assertDoesNotExist()
        ui.runOnIdle{library=true}
        ui.onNodeWithTag("remove_from_vk").performClick()
        ui.onNodeWithTag("confirm_remove_from_vk").assertIsDisplayed()
        ui.onNodeWithText("Отмена").performClick();ui.runOnIdle{assertEquals(0,deletes)}
        ui.onNodeWithTag("remove_from_vk").performClick()
        ui.onNodeWithTag("confirm_remove_from_vk").performClick()
        ui.onNodeWithText("Файл сохранён").assertIsDisplayed();ui.runOnIdle{assertEquals(1,deletes)}
    }
}

@RunWith(AndroidJUnit4::class)
class LibraryRemovalIntegrationDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun cancelledAndAlbumRequestsCannotDeleteAndConfirmedLibraryRemovalRetainsActualMp3()=runBlocking<Unit> {
        val app=ui.activity.application;val t=NativeTrack("12_99553","CI preserved MP3","CI",1)
        val playlist=NativePlaylist(12,998,"CI album",1,null,null,null,null,PlaylistKind.ALBUM)
        var removed=false;var deletes=0
        val api=object: MusicApi {
            override suspend fun library(access: MusicAccess)=NativeProbe(12,NativePage(if(removed)emptyList()else listOf(t),if(removed)0 else 1))
            override suspend fun catalog(access: MusicAccess,owner: Long)=NativeCatalog(listOf(playlist),1)
            override suspend fun playlist(access: MusicAccess,list: NativePlaylist)=NativePage(listOf(t),1)
            override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("No real music network")
            override suspend fun removeLibraryTrack(access: MusicAccess,owner: Long,track: NativeTrack){assertEquals(12L,owner);assertEquals(t,track);deletes++;removed=true}
        }
        lateinit var model: NativeMusicViewModel
        var row: PublishedAudio?=null
        val task=ExportLayout.task(t,listOf("Моя музыка"),1)
        try {
            withContext(Dispatchers.IO) {
                AudioExportRuntime.initialize(app);AudioExportRuntime.clear(app)
                val input=File(app.cacheDir,"removal-fixture.mp3").apply{InstrumentationRegistry.getInstrumentation().context.assets.open("native-audio/tone.mp3").use{writeBytes(it.readBytes())}}
                AudioExportStore(app).use{store->store.replace(listOf(task));row=SharedAudioStore(app).publish(task,TransferResult(input,ProcessedAudio("mp3","audio/mpeg",1200))){store.prepared(task,it)};store.done(task,row!!)}
                input.delete();AudioExportRuntime.refresh()
            }
            ui.runOnUiThread {
                model=ViewModelProvider(ui.activity,object: ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T: androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T=NativeMusicViewModel(app,api) as T
                })["removal-test",NativeMusicViewModel::class.java]
                ui.activity.setContent{MusicTheme{NativeMusicPanel(model,true,{})}}
            }
            ui.waitUntil(10000){model.state.value.initialized}
            ui.runOnUiThread{model.configure("vk1.a."+"SYNTHETIC".repeat(12),"CI/1")}
            ui.waitUntil(10000){!model.state.value.busy&&model.state.value.probe!=null}
            ui.onNodeWithTag("remove_from_vk").performScrollTo().performClick()
            ui.waitUntil(10000){model.removal.value.phase==LibraryRemovalPhase.CONFIRM&&!model.state.value.busy}
            ui.onNodeWithText("Отмена").performClick();assertEquals(0,deletes)
            ui.runOnUiThread{model.select(playlist)}
            ui.waitUntil(10000){!model.state.value.busy&&model.state.value.selected!=null}
            ui.onNodeWithTag("remove_from_vk").assertDoesNotExist()
            ui.runOnUiThread{model.inspectRemoval(t)};assertEquals(0,deletes)
            ui.runOnUiThread{model.section(CatalogSection.LIBRARY)}
            ui.onNodeWithTag("remove_from_vk").performScrollTo().performClick()
            ui.waitUntil(10000){model.removal.value.phase==LibraryRemovalPhase.CONFIRM&&!model.state.value.busy}
            ui.onNodeWithTag("confirm_remove_from_vk").performClick()
            ui.waitUntil(10000){model.removal.value.phase==LibraryRemovalPhase.DONE&&!model.state.value.busy}
            assertEquals(1,deletes);assertEquals(0,model.state.value.pageOffset);assertEquals(0,model.state.value.probe!!.library.count)
            withContext(Dispatchers.IO){assertTrue(SharedAudioStore(app).valid(row!!));AudioExportStore(app).use{assertEquals(row,it.existing(task.key))}}
        }finally {
            withContext(Dispatchers.IO){row?.let{app.contentResolver.delete(Uri.parse(it.uri),null,null)};AudioExportRuntime.clear(app);AudioExportRuntime.clearRecentFiles(app);MusicAccessStore(app).delete()}
        }
    }
}

@RunWith(AndroidJUnit4::class)
class LibraryRemovalCopyDeviceTest {
    @Test fun aModifiedPublishedFileBlocksMutationEvenAfterAnEarlierSuccessfulVerification()=runBlocking<Unit> {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val track=NativeTrack("12_99554","CI modified","CI",1);val task=ExportLayout.task(track,listOf("Моя музыка"),1)
        val input=File(app.cacheDir,"removal-modified.mp3").apply{writeBytes(ByteArray(4096){7})}
        var row: PublishedAudio?=null;var deletes=0
        val api=object: MusicApi {
            override suspend fun library(access: MusicAccess): NativeProbe=error("not needed")
            override suspend fun catalog(access: MusicAccess,owner: Long): NativeCatalog=error("not needed")
            override suspend fun playlist(access: MusicAccess,list: NativePlaylist): NativePage=error("not needed")
            override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("not needed")
            override suspend fun removeLibraryTrack(access: MusicAccess,owner: Long,track: NativeTrack){deletes++}
        }
        try {withContext(Dispatchers.IO) {
            val shared=SharedAudioStore(app)
            val saved=shared.publish(task,TransferResult(input,ProcessedAudio("mp3","audio/mpeg",1000))){};row=saved
            assertTrue(shared.valid(saved))
            app.contentResolver.openOutputStream(Uri.parse(saved.uri),"wt")!!.use{it.write(byteArrayOf(1,2,3))}
            try{LibraryTrackRemoval(api){shared.valid(it)}.remove(MusicAccess("vk1.a."+"SYNTHETIC".repeat(12),"CI/1"),12,track,saved);fail("modified copy")}
            catch(e: MusicFailure){assertEquals(MusicError.INCOMPLETE,e.code)}
            assertEquals(0,deletes)
        }}finally{row?.let{app.contentResolver.delete(Uri.parse(it.uri),null,null)};input.delete()}
    }
}
