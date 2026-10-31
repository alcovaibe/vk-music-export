package app.alcovaibe.vkmusicexport

import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
class CatalogRefreshDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun downloadedAlbumsDeletedSourcesChangedContentsAndMissingFilesRefreshWithoutGhostsOrNewQueues()=runBlocking<Unit> {
        val app=ui.activity.application
        val track=NativeTrack("12_99771","CI original","CI",1)
        val album=NativePlaylist(12,501,"I AM",1,null,null,null,1)
        val playlist=NativePlaylist(12,502,"Плейлист 28.10.2026",1,null,null,null,0)
        val deleted=NativePlaylist(12,503,"Removed from VK",1,null,null,null,1)
        var sources=listOf(album,playlist);var changed=false;var failCatalog=false
        val api=object: MusicApi {
            override suspend fun library(access: MusicAccess)=NativeProbe(12,NativePage(emptyList(),0))
            override suspend fun catalog(access: MusicAccess,owner: Long): NativeCatalog {
                if(failCatalog)throw MusicFailure(MusicError.NETWORK)
                return NativeCatalog(sources,sources.size)
            }
            override suspend fun albumCatalogPage(access: MusicAccess,owner: Long,offset: Int)=NativeCatalog(sources.filter{it.id==501L},sources.count{it.id==501L})
            override suspend fun playlist(access: MusicAccess,list: NativePlaylist)=NativePage(listOf(
                if(list.id==502L||changed)track.copy(id="12_99772")else track),1)
            override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("No music network allowed")
        }
        lateinit var model: NativeMusicViewModel
        var published: PublishedAudio?=null;var localDeleted=false
        val task=ExportLayout.playlist(NativePage(listOf(track),1),album,PlaylistKind.ALBUM,12).single()
        try {
            withContext(Dispatchers.IO) {
                AudioExportRuntime.initialize(app);AudioExportRuntime.clear(app)
                val input=File(app.cacheDir,"catalog-fixture.mp3").apply{
                    InstrumentationRegistry.getInstrumentation().context.assets.open("native-audio/tone.mp3").use{writeBytes(it.readBytes())}
                }
                AudioExportStore(app).use{store->
                    store.replace(listOf(task),account=12)
                    published=SharedAudioStore(app).publish(task,TransferResult(input,ProcessedAudio("mp3","audio/mpeg",1200))){store.prepared(task,it)}
                    store.done(task,published!!)
                    store.imported(store.link(ExportLayout.playlist(NativePage(listOf(track),1),deleted,PlaylistKind.ALBUM,12).single(),published!!))
                    SharedPlaylistStore(app,store).publishAll()
                    // An old mistaken manual choice must never override the current server filters.
                    store.catalogKind(12,playlist,PlaylistKind.ALBUM)
                }
                input.delete();AudioExportRuntime.clear(app)
            }
            ui.runOnUiThread {
                model=ViewModelProvider(ui.activity,object: ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T: androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = NativeMusicViewModel(app,api) as T
                })["catalog-refresh-test",NativeMusicViewModel::class.java]
            }
            ui.waitUntil(10000){model.state.value.initialized}
            ui.runOnUiThread{model.configure("vk1.a."+"SYNTHETIC".repeat(12),"CI/1")}
            ui.waitUntil(15000){!model.state.value.busy}
            assertEquals(setOf(album.key),model.state.value.hiddenCatalog)
            assertTrue(MusicCatalog.visible(model.state.value,CatalogSection.ALBUMS).isEmpty())
            assertEquals(listOf(playlist.key),MusicCatalog.visible(model.state.value,CatalogSection.PLAYLISTS).map{it.key})
            assertFalse(model.state.value.catalog!!.lists.any{it.key==deleted.key})
            ui.runOnUiThread{model.prepare(catalogKind=PlaylistKind.ALBUM)}
            ui.waitUntil(15000){!model.state.value.busy}
            assertEquals(0,AudioExportRuntime.state.value.total);assertFalse(model.downloadsOpen.value)
            ui.runOnUiThread{model.select(playlist)}
            ui.waitUntil(10000){!model.state.value.busy}
            assertEquals(playlist.key,model.state.value.selected!!.key)
            ui.runOnUiThread{sources=listOf(album);model.refreshCatalog()}
            ui.waitUntil(15000){!model.state.value.busy}
            assertNull(model.state.value.selected);assertNull(model.state.value.page)
            assertTrue(MusicCatalog.visible(model.state.value,CatalogSection.PLAYLISTS).isEmpty())
            ui.runOnUiThread{changed=true;model.refreshCatalog()}
            ui.waitUntil(15000){!model.state.value.busy}
            assertTrue(model.state.value.hiddenCatalog.isEmpty()) // Same count, different current ID.
            ui.runOnUiThread{changed=false;model.refreshCatalog()}
            ui.waitUntil(15000){!model.state.value.busy}
            assertEquals(setOf(album.key),model.state.value.hiddenCatalog)
            withContext(Dispatchers.IO){assertTrue(SharedAudioStore(app).valid(published!!));app.contentResolver.delete(Uri.parse(published!!.uri),null,null);localDeleted=true}
            ui.runOnUiThread{model.refreshCatalog()}
            ui.waitUntil(15000){!model.state.value.busy}
            assertTrue(model.state.value.hiddenCatalog.isEmpty())
            assertEquals(listOf(album.key),MusicCatalog.visible(model.state.value,CatalogSection.ALBUMS).map{it.key})
            ui.runOnUiThread{failCatalog=true;model.refreshCatalog()}
            ui.waitUntil(10000){!model.state.value.busy}
            assertNull(model.state.value.catalog);assertNull(model.state.value.selected);assertNull(model.state.value.page)
            assertEquals(MusicError.NETWORK,model.state.value.catalogError)
        }finally {
            withContext(Dispatchers.IO){
                if(!localDeleted)published?.let{app.contentResolver.delete(Uri.parse(it.uri),null,null)}
                AudioExportRuntime.clear(app);AudioExportRuntime.clearRecentFiles(app)
                AudioExportStore(app).use{store->store.catalogKind(12,playlist,PlaylistKind.UNKNOWN);store.savedFiles().filter{it.source?.id in setOf(album.key,deleted.key)}.forEach{store.forgetFile(it)}}
                MusicAccessStore(app).delete()
            }
        }
    }
}
