package app.alcovaibe.vkmusicexport

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class BulkDownloadDeviceTest {
    private class Api: MusicApi {
        val song=NativeTrack("12_990871","Bulk CI","CI",1,album=MusicAlbum("album:990871","Bulk CI album"))
        val snapshot=NativePage(listOf(song,song.copy(id="12_990872"),song),3)
        val first=NativePlaylist(12,990873,"Bulk CI first",3,null,null,null,null,PlaylistKind.PLAYLIST)
        val second=NativePlaylist(12,990874,"Bulk CI second",1,null,null,null,null,PlaylistKind.PLAYLIST)
        val album=NativePlaylist(12,990875,"Bulk CI album",1,null,null,null,null,PlaylistKind.ALBUM)
        var liveTracks=snapshot.tracks
        val libraryReads=AtomicInteger();val catalogReads=AtomicInteger();val proofs=AtomicInteger()
        val playlistReads=mutableMapOf<String,Int>()
        var allowLibrary=true;var allowCatalog=true;var allowPlaylists=true
        var beforePlaylist: (suspend(NativePlaylist)->Unit)?=null
        var afterLibrary: (suspend()->Unit)?=null
        override suspend fun accountOwner(access: MusicAccess): Long {proofs.incrementAndGet();return 12}
        override suspend fun library(access: MusicAccess): NativeProbe {libraryReads.incrementAndGet();check(allowLibrary);return NativeProbe(12,NativePage(liveTracks,liveTracks.size)).also{afterLibrary?.invoke()}}
        override suspend fun catalog(access: MusicAccess,owner: Long): NativeCatalog {catalogReads.incrementAndGet();check(allowCatalog);return NativeCatalog(listOf(first,second,album),3)}
        override suspend fun playlist(access: MusicAccess,list: NativePlaylist): NativePage {
            playlistReads[list.key]=(playlistReads[list.key]?:0)+1;check(allowPlaylists);beforePlaylist?.invoke(list)
            return if(list.key==first.key)snapshot else NativePage(listOf(song),1)
        }
        override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("Planning cannot download audio")
    }
    private suspend fun until(check:()->Boolean){withTimeout(20_000){while(!check())delay(20)}}
    private fun fixture(block:suspend(NativeMusicViewModel,Api,MusicAccessStore,AtomicInteger,AtomicLong)->Unit)=runBlocking<Unit> {
        val inst=InstrumentationRegistry.getInstrumentation();val app=inst.targetContext.applicationContext as Application
        val vault=MusicAccessStore(app);val old=withContext(Dispatchers.IO){vault.read()};val holder=ViewModelStore()
        val api=Api();val starts=AtomicInteger();val clock=AtomicLong(1_000)
        lateinit var model: NativeMusicViewModel
        try {
            withContext(Dispatchers.IO){AudioExportRuntime.initialize(app);AudioExportRuntime.clear(app)}
            inst.runOnMainSync {
                model=ViewModelProvider(holder,object: ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T: androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T=
                        NativeMusicViewModel(app,api,authClock={clock.get()},queueStart={starts.incrementAndGet()}) as T
                })[NativeMusicViewModel::class.java]
            }
            until{model.state.value.initialized}
            inst.runOnMainSync{model.setForeground(true);model.configure("vk1.a."+"SYNTHETIC".repeat(12),"CI/1")}
            until{!model.state.value.busy&&model.state.value.probe!=null&&!model.state.value.catalogVerifying}
            block(model,api,vault,starts,clock)
        }finally {
            inst.runOnMainSync{holder.clear()}
            withContext(Dispatchers.IO){AudioExportRuntime.clear(app);if(old==null)vault.delete()else vault.save(old)}
        }
    }
    private fun main(block:()->Unit)=InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private suspend fun tasks(model: NativeMusicViewModel): List<AudioExportTask> {
        until{!model.state.value.busy&&model.downloadsOpen.value};assertNull(model.state.value.error)
        return rows()
    }
    private suspend fun rows()=withContext(Dispatchers.IO){
        AudioExportStore(InstrumentationRegistry.getInstrumentation().targetContext).use{it.taskRows().map{row->row.first}}
    }
    @Test fun analyzedLibraryAndAlbumGroupingQueueWithoutNewListRequestsAndSurviveReopen()=fixture {model,api,_,starts,_->
        val reads=api.libraryReads.get();val catalog=api.catalogReads.get()
        api.allowLibrary=false;api.allowCatalog=false;api.allowPlaylists=false
        main{model.prepare(libraryAlbums=true)}
        val expected=ExportLayout.library(api.snapshot,albums=true,account=12)
        assertEquals(expected,tasks(model));assertEquals(expected,rows())
        assertEquals(1,starts.get());assertEquals(1,api.proofs.get());assertEquals(reads,api.libraryReads.get())
        assertEquals(catalog,api.catalogReads.get());assertTrue(api.playlistReads.isEmpty())
    }
    @Test fun openedPlaylistUsesItsCompleteSnapshotWithoutRecheckingTheCatalog()=fixture {model,api,_,starts,_->
        main{model.select(api.first)};until{!model.state.value.busy&&model.state.value.selected!=null}
        val reads=api.playlistReads.toMap();val catalog=api.catalogReads.get()
        api.allowLibrary=false;api.allowCatalog=false;api.allowPlaylists=false
        main{model.prepare()}
        assertEquals(ExportLayout.playlist(api.snapshot,api.first,account=12),tasks(model))
        assertEquals(1,starts.get());assertEquals(reads,api.playlistReads);assertEquals(catalog,api.catalogReads.get())
    }
    @Test fun categoryReadsOnlyItsOwnCollectionsOnce()=fixture {model,api,_,starts,_->
        api.allowLibrary=false;api.allowCatalog=false
        val catalog=api.catalogReads.get()
        main{model.prepare(catalogKind=PlaylistKind.PLAYLIST)}
        val queued=tasks(model)
        assertEquals(4,queued.size);assertEquals(setOf(api.first.key,api.second.key),queued.map{it.source!!.id}.toSet())
        assertEquals(mapOf(api.first.key to 1,api.second.key to 1),api.playlistReads)
        assertEquals(1,starts.get());assertEquals(catalog,api.catalogReads.get())
    }
    @Test fun expiredAnalysisReadsTheChangedLibraryOnce()=fixture {model,api,_,starts,clock->
        val before=api.libraryReads.get();clock.addAndGet(60_000)
        api.liveTracks=api.snapshot.tracks+api.song.copy(id="12_990876")
        api.allowCatalog=false;api.allowPlaylists=false
        main{model.prepare()}
        assertEquals(api.liveTracks.map{it.id},tasks(model).map{it.track.id})
        assertEquals(before+1,api.libraryReads.get());assertEquals(1,starts.get())
    }
    @Test fun cancelledCategoryCannotReplaceQueueAndRetryReusesOnlyCompletedSources()=fixture {model,api,_,starts,_->
        api.allowLibrary=false;api.allowCatalog=false
        val entered=CompletableDeferred<Unit>()
        api.beforePlaylist={list->if(list.key==api.second.key){entered.complete(Unit);awaitCancellation()}}
        main{model.prepare(catalogKind=PlaylistKind.PLAYLIST)};withTimeout(10_000){entered.await()}
        main{model.cancel()};until{!model.state.value.busy}
        assertTrue(rows().isEmpty());assertEquals(0,starts.get())
        api.beforePlaylist=null
        main{model.prepare(catalogKind=PlaylistKind.PLAYLIST)}
        assertEquals(4,tasks(model).size);assertEquals(1,starts.get())
        assertEquals(mapOf(api.first.key to 1,api.second.key to 2),api.playlistReads)
    }
    @Test fun aNewLoginCannotReuseAnotherSessionAnalysisOrStartAQueue()=fixture {model,api,vault,starts,_->
        withContext(Dispatchers.IO){vault.save(MusicAccess("vk1.a."+"ANOTHER".repeat(12),"CI/1",accountId=12))}
        api.allowLibrary=false;api.allowCatalog=false;api.allowPlaylists=false
        main{model.prepare()};until{!model.state.value.busy&&model.state.value.error!=null}
        assertEquals(MusicError.ACCESS,model.state.value.error);assertEquals(0,api.proofs.get())
        assertTrue(rows().isEmpty());assertEquals(0,starts.get())
    }
    @Test fun logoutDuringFreshPageReadCannotCommitThePreparedQueue()=fixture {model,api,vault,starts,clock->
        clock.addAndGet(60_000);api.afterLibrary={withContext(Dispatchers.IO){vault.delete()}}
        main{model.prepare()};until{!model.state.value.busy&&model.state.value.error!=null}
        assertEquals(MusicError.ACCESS,model.state.value.error)
        assertTrue(rows().isEmpty());assertEquals(0,starts.get())
    }
}
