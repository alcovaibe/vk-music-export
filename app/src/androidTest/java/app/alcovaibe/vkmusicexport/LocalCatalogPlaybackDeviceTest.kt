package app.alcovaibe.vkmusicexport

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Actual NativeMusicPanel hosted by MainActivity, with isolated durable data and a failing remote API. */
@RunWith(AndroidJUnit4::class)
class LocalCatalogPlaybackDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private class LocalApplication(base: Context,private val root: File): Application() {
        init{attachBaseContext(base)}
        override fun getNoBackupFilesDir()=root
    }
    @Test fun coldModelRestoresCachedAlbumAndPlaysRepeatedLocalFilesWithoutRemotePlaylist(): Unit=runBlocking {
        val real=ui.activity.application
        val root=File(real.cacheDir,"local-catalog-ui-"+UUID.randomUUID()).apply{check(mkdirs())}
        val app=LocalApplication(real,root)
        val owner=object: ViewModelStoreOwner{override val viewModelStore=ViewModelStore()}
        val playlistCalls=AtomicInteger();val libraryCalls=AtomicInteger()
        val api=object: MusicApi {
            override suspend fun library(access: MusicAccess): NativeProbe {
                libraryCalls.incrementAndGet();throw MusicFailure(MusicError.NETWORK)
            }
            override suspend fun catalog(access: MusicAccess,owner: Long): NativeCatalog=throw MusicFailure(MusicError.NETWORK)
            override suspend fun playlist(access: MusicAccess,list: NativePlaylist): NativePage {
                playlistCalls.incrementAndGet();error("Local metadata is not online authority")
            }
            override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("Local playback must not resolve a VK stream")
        }
        val album=NativePlaylist(12,990888,"CI retained offline album",2,"MUST_NOT_BE_SAVED",null,null,null,PlaylistKind.ALBUM)
        val song=NativeTrack("12_990888","CI local occurrence","Synthetic",1)
        val page=NativePage(listOf(song,song),2);val tasks=ExportLayout.playlist(page,album,account=12)
        var published: PublishedAudio?=null
        lateinit var model: NativeMusicViewModel
        try {
            withContext(Dispatchers.IO) {
                // Shared production services always keep their real application context.
                MaintenanceRuntime.initialize(real);DuplicateCheckRuntime.initialize(real);AudioExportRuntime.initialize(real)
                val input=File(root,"tone.mp3").apply{
                    InstrumentationRegistry.getInstrumentation().context.assets.open("native-audio/tone.mp3").use{writeBytes(it.readBytes())}
                }
                val first=SharedAudioStore(app).publish(tasks.first(),TransferResult(input,ProcessedAudio("mp3","audio/mpeg",1200))){}
                published=first
                AudioExportStore(app).use{store->
                    store.imported(first)
                    store.imported(first.copy(taskKey=tasks.last().key,source=tasks.last().source,occurrence=2))
                    LocalCatalogSnapshots(store).remember(12,album,page)
                }
                MusicAccessStore(app).save(MusicAccess("vk1.a."+"SYNTHETIC".repeat(12),"CI/1",accountId=12))
            }
            ui.runOnUiThread {
                model=ViewModelProvider(owner,object: ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T: androidx.lifecycle.ViewModel> create(type: Class<T>): T =
                        NativeMusicViewModel(app,api) as T
                })[NativeMusicViewModel::class.java]
                ui.activity.setContent{MusicTheme{NativeMusicPanel(model,true,onDismiss={})}}
            }
            ui.waitUntil(15000){model.state.value.initialized&&!model.state.value.busy&&model.state.value.error==MusicError.NETWORK}
            assertTrue(libraryCalls.get()>0);assertNull(model.state.value.readSessionId);assertNull(model.state.value.probe)
            ui.onNodeWithTag("library_category_ALBUMS").performScrollTo().performClick()
            ui.onNodeWithTag("catalog_list_12_990888").performScrollTo().performClick()
            ui.waitUntil(10000){!model.state.value.busy&&model.state.value.localPlayback?.rows?.size==2}
            assertEquals(0,playlistCalls.get())
            ui.onNodeWithTag("local_collection_notice").performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("Скачать альбом").assertIsNotEnabled()
            ui.onNodeWithTag("library_play").performScrollTo().performClick()
            ui.waitUntil(15000){PlaybackRuntime.state.value.playing&&PlaybackRuntime.state.value.entries.size==2}
            val entries=PlaybackRuntime.state.value.entries
            assertTrue(entries.all{it.offline&&it.account==null&&it.readSessionId==null&&it.download==null})
            assertEquals(listOf(1,2),entries.map{it.saved!!.occurrence})
            assertEquals(0,playlistCalls.get())
        }finally {
            ui.runOnUiThread{PlaybackRuntime.stop();owner.viewModelStore.clear()}
            PlaybackRuntime.flushHistory()
            published?.let{app.contentResolver.delete(Uri.parse(it.uri),null,null)}
            root.deleteRecursively()
        }
    }
}
