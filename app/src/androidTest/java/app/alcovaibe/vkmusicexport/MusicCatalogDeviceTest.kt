package app.alcovaibe.vkmusicexport

import android.app.Application
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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

private fun collection(id: Long,kind: PlaylistKind)=NativePlaylist(12,id,"CI $id",1,"SENSITIVE_KEY",null,null,null,kind)

@RunWith(AndroidJUnit4::class)
class MusicCatalogStoreDeviceTest {
    @Test fun manualTypesSurviveReopenAndRemainScopedToAccountWithoutChangingQueue() {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val base=File(target.cacheDir,"catalog-types").apply{deleteRecursively();mkdirs()}
        val context=object: ContextWrapper(target){override fun getNoBackupFilesDir()=base}
        val list=collection(17,PlaylistKind.UNKNOWN)
        try {
            AudioExportStore(context).use {store->
                store.replace(listOf(ExportLayout.task(NativeTrack("12_1","CI","CI",1),listOf("Моя музыка"),1)))
                val queue=store.snapshot();store.catalogKind(12,list,PlaylistKind.ALBUM)
                assertEquals(queue,store.snapshot());assertTrue(store.catalogKinds(13).isEmpty())
            }
            AudioExportStore(context).use {store->
                assertEquals(mapOf(list.key to PlaylistKind.ALBUM),store.catalogKinds(12))
                store.catalogKind(13,list,PlaylistKind.PLAYLIST)
                assertEquals(PlaylistKind.ALBUM,store.catalogKinds(12)[list.key])
                store.catalogKind(12,list,PlaylistKind.UNKNOWN)
                assertTrue(store.catalogKinds(12).isEmpty());assertEquals(PlaylistKind.PLAYLIST,store.catalogKinds(13)[list.key])
                assertEquals(1,store.snapshot().pending);assertEquals(3,store.readableDatabase.version)
                store.readableDatabase.rawQuery("SELECT name,value FROM meta",null).use{c->while(c.moveToNext())assertFalse((c.getString(0)+c.getString(1)).contains("SENSITIVE_KEY"))}
            }
        }finally{base.deleteRecursively()}
    }
}

@RunWith(AndroidJUnit4::class)
class MusicCatalogUiDeviceTest {
    @get:Rule val ui=createComposeRule()
    @Test fun tabsSeparateListsAndManualChoiceMovesUnknownCollectionToAlbum() {
        val playlist=collection(1,PlaylistKind.PLAYLIST);val album=collection(2,PlaylistKind.ALBUM);val unknown=collection(3,PlaylistKind.UNKNOWN)
        var state by mutableStateOf(NativeMusicState(initialized=true,probe=NativeProbe(12,NativePage(emptyList(),0)),
            catalog=NativeCatalog(listOf(playlist,album,unknown),3),catalogSection=CatalogSection.PLAYLISTS))
        var requested: PlaylistKind?=null
        ui.setContent{MusicTheme{Surface(Modifier.fillMaxSize()){
            LazyColumn(Modifier.statusBarsPadding().padding(24.dp).testTag("test_catalog"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item { Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                MusicCatalogPanel(state,true,onSection={state=state.copy(catalogSection=it,selected=null)},
                    onRefresh={},onLibrary={},onCategory={requested=it},
                    onClassify={list,kind->state=state.copy(catalogKinds=mapOf(list.key to kind),catalogSection=CatalogSection.forKind(kind))})
                }}
                musicCatalogEntries(state){state=state.copy(selected=it)}
            }
        }}}
        ui.onNodeWithTag("catalog_list_12_1").assertIsDisplayed()
        ui.onNodeWithTag("catalog_list_12_2").assertDoesNotExist()
        ui.onNodeWithTag("catalog_download_category").performClick();ui.runOnIdle{assertEquals(PlaylistKind.PLAYLIST,requested)}
        ui.onNodeWithTag("catalog_tab_ALBUMS").performClick()
        ui.onNodeWithTag("catalog_list_12_2").assertIsDisplayed()
        ui.onNodeWithTag("catalog_list_12_1").assertDoesNotExist()
        ui.onNodeWithTag("catalog_download_category").performClick();ui.runOnIdle{assertEquals(PlaylistKind.ALBUM,requested)}
        ui.onNodeWithTag("catalog_tab_UNKNOWN").performScrollTo().performClick()
        ui.onNodeWithTag("test_catalog").performScrollToNode(hasTestTag("catalog_list_12_3"));ui.onNodeWithTag("catalog_list_12_3").performClick()
        ui.onNodeWithTag("classify_album").performScrollTo().performClick()
        ui.onNodeWithTag("test_catalog").performScrollToNode(hasTestTag("catalog_list_12_3"));ui.onNodeWithTag("catalog_list_12_3").assertIsDisplayed()
        ui.onNodeWithText("Тип подборки: Альбом").performScrollTo().assertIsDisplayed()
        ui.onNodeWithTag("catalog_list_12_1").assertDoesNotExist()
        ui.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation();val bitmap=instrumentation.uiAutomation.takeScreenshot()
        val file=File(instrumentation.targetContext.getExternalFilesDir(null),"screenshots/catalog-albums.png").apply{parentFile!!.mkdirs()}
        file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        for(cmd in listOf("mkdir -p /data/local/tmp/vk-music-export-screenshots","cp ${file.absolutePath} /data/local/tmp/vk-music-export-screenshots/catalog-albums.png"))
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(cmd)).use{it.readBytes()}
    }
}

@RunWith(AndroidJUnit4::class)
class MusicCatalogViewModelDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun fullCatalogManualChoiceAndBulkAlbumQueueUseOnlyMatchingSources()=runBlocking<Unit> {
        val context=ui.activity.application;val lists=(1L..100L).map{collection(it,PlaylistKind.PLAYLIST)}+
            collection(101,PlaylistKind.ALBUM)+collection(102,PlaylistKind.UNKNOWN)
        val offsets=mutableListOf<Int>();val fetched=mutableListOf<Long>()
        val track=NativeTrack("12_99997","CI unavailable","CI",1,restricted=true)
        val api=object: MusicApi {
            override suspend fun library(access: MusicAccess)=NativeProbe(12,NativePage(listOf(track),1))
            override suspend fun catalog(access: MusicAccess,owner: Long)=catalogPage(access,owner,0)
            override suspend fun catalogPage(access: MusicAccess,owner: Long,offset: Int): NativeCatalog {
                offsets.add(offset);return NativeCatalog(lists.drop(offset).take(100),lists.size)
            }
            override suspend fun playlist(access: MusicAccess,list: NativePlaylist)=playlistPage(access,list,0)
            override suspend fun playlistPage(access: MusicAccess,list: NativePlaylist,offset: Int): NativePage {
                fetched.add(list.id)
                if(list.id==102L) {
                    // Metadata uses the injected API. Make the synthetic credential unusable
                    // before handing the real UIDT queue over, so it cannot contact VK.
                    val store=MusicAccessStore(context);val old=store.read()!!
                    assertTrue(store.replace(old,old.withSession(rejected=true)))
                }
                return NativePage(listOf(track),1)
            }
            override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("No music network allowed")
        }
        lateinit var model: NativeMusicViewModel
        val vault=MusicAccessStore(context)
        withContext(Dispatchers.IO){AudioExportRuntime.initialize(context);AudioExportRuntime.clear(context)}
        ui.runOnUiThread {
            model=ViewModelProvider(ui.activity,object: ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T: androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = NativeMusicViewModel(context,api) as T
            })["catalog-test",NativeMusicViewModel::class.java]
        }
        try {
            ui.waitUntil(10000){model.state.value.initialized}
            ui.runOnUiThread{model.configure("vk1.a."+"SYNTHETIC".repeat(12),"CI/1")}
            ui.waitUntil(10000){!model.state.value.busy&&model.state.value.catalog!=null}
            assertEquals(listOf(0,100),offsets);assertEquals(102,model.state.value.catalog!!.lists.size)
            ui.runOnUiThread{model.classify(lists.last(),PlaylistKind.ALBUM)}
            ui.waitUntil(10000){!model.state.value.busy&&model.state.value.catalogKinds[lists.last().key]==PlaylistKind.ALBUM}
            ui.runOnUiThread{model.refreshCatalog()}
            ui.waitUntil(10000){!model.state.value.busy}
            assertEquals(2,MusicCatalog.lists(model.state.value.catalog,CatalogSection.ALBUMS,model.state.value.catalogKinds).size)
            ui.runOnUiThread{model.prepare(catalogKind=PlaylistKind.ALBUM)}
            ui.waitUntil(10000){!model.state.value.busy&&model.downloadsOpen.value}
            withTimeout(20000){while(AudioExportRuntime.state.value.status!="PAUSED"||AudioExportRuntime.active.get())delay(50)}
            assertEquals(listOf(101L,102L),fetched);assertEquals(2,AudioExportRuntime.state.value.total)
            assertEquals(MusicError.ACCESS,AudioExportRuntime.state.value.error)
            assertEquals(0,AudioExportRuntime.state.value.failed);assertEquals(2,AudioExportRuntime.state.value.pending)
            withContext(Dispatchers.IO){AudioExportStore(context).use{store->
                store.readableDatabase.rawQuery("SELECT payload FROM tasks",null).use{c->while(c.moveToNext())assertTrue(c.getString(0).contains("Альбомы"))}
            }}
        }finally {
            ui.runOnUiThread{model.dismissDownloads();model.cancel()}
            withContext(Dispatchers.IO){AudioExportRuntime.pause(context);withTimeout(20000){while(AudioExportRuntime.active.get())delay(50)};AudioExportRuntime.clear(context)
                AudioExportStore(context).use{it.catalogKind(12,lists.last(),PlaylistKind.UNKNOWN)};vault.delete()}
        }
    }
}
