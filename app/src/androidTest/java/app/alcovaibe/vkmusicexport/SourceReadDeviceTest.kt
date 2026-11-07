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
class SourceReadDeviceTest {
    private class Api: MusicApi {
        val song=NativeTrack("34_988001","Observed CI","CI",1)
        val initial=listOf(song,song.copy(id="34_988002"),song)
        val own=NativePlaylist(12,988003,"Own observed CI",3,null,null,null,0,PlaylistKind.PLAYLIST)
        val friend=NativePlaylist(-55,988004,"Friend observed CI",3,null,null,null,1,PlaylistKind.ALBUM)
        @Volatile var rows=initial
        @Volatile var accessible=true
        @Volatile var allowCatalog=true
        @Volatile var allowPlaylist=true
        val libraries=AtomicInteger();val playlists=AtomicInteger();val catalogs=AtomicInteger();val proofs=AtomicInteger()
        var beforeLibrary: (suspend(Int)->Unit)?=null
        var beforePlaylist: (suspend(Int)->Unit)?=null
        var beforeProof: (suspend()->Unit)?=null
        override suspend fun accountOwner(access: MusicAccess): Long {proofs.incrementAndGet();beforeProof?.invoke();return 12}
        override suspend fun library(access: MusicAccess)=NativeProbe(12,NativePage(initial,initial.size))
        override suspend fun libraryPage(access: MusicAccess,owner: Long,offset: Int): NativePage {
            libraries.incrementAndGet();beforeLibrary?.invoke(offset)
            return NativePage(rows.drop(offset).take(100),rows.size)
        }
        override suspend fun catalog(access: MusicAccess,owner: Long): NativeCatalog {
            catalogs.incrementAndGet();check(allowCatalog){"No unrelated catalog scan"}
            return NativeCatalog(listOf(if(owner==12L)own else friend),1)
        }
        override suspend fun friendProfile(access: MusicAccess,id: Long)=MusicFriend(id,"Observed","Friend",accessible=accessible)
        override suspend fun playlist(access: MusicAccess,list: NativePlaylist)=playlistPage(access,list,0)
        override suspend fun playlistPage(access: MusicAccess,list: NativePlaylist,offset: Int): NativePage {
            playlists.incrementAndGet();check(allowPlaylist){"Unexpected repeated source read"};beforePlaylist?.invoke(offset)
            return NativePage(rows.drop(offset).take(100),rows.size)
        }
        override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("No transfer in queue admission")
    }
    private data class Fixture(val model: NativeMusicViewModel,val friends: FriendMusicController,val api: Api,val vault: MusicAccessStore,
        val starts: AtomicInteger,val clock: AtomicLong,val app: Application)
    private suspend fun main(block:()->Unit)=withContext(Dispatchers.Main.immediate){block();yield()}
    private suspend fun until(check:()->Boolean){withTimeout(20_000){while(!check())delay(20)}}
    private fun fixture(block:suspend(Fixture)->Unit)=runBlocking<Unit> {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val vault=MusicAccessStore(app);val previous=withContext(Dispatchers.IO){vault.read()}
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate);val holder=ViewModelStore()
        val api=Api();val starts=AtomicInteger();val clock=AtomicLong(1000)
        lateinit var model: NativeMusicViewModel;lateinit var friends: FriendMusicController
        try {
            withContext(Dispatchers.IO){AudioExportRuntime.initialize(app);AudioExportRuntime.clear(app)}
            main {
                model=ViewModelProvider(holder,object: ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")override fun <T: androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T=
                        NativeMusicViewModel(app,api,authClock={clock.get()},queueStart={starts.incrementAndGet();Unit}) as T
                })[NativeMusicViewModel::class.java]
                friends=FriendMusicController(app,api,scope,queueStart={starts.incrementAndGet();Unit},clock={clock.get()})
            }
            until{model.state.value.initialized}
            main{model.setForeground(true);model.configure("vk1.a."+"SYNTHETIC".repeat(12),"CI/1")}
            until{!model.state.value.busy&&model.state.value.probe!=null&&!model.state.value.catalogVerifying}
            block(Fixture(model,friends,api,vault,starts,clock,app))
        }finally {
            main{friends.reset();holder.clear()};scope.coroutineContext[Job]!!.cancelAndJoin()
            withContext(Dispatchers.IO){AudioExportRuntime.clear(app);if(previous==null)vault.delete()else vault.save(previous)}
        }
    }
    private suspend fun open(f: Fixture){main{f.friends.open(MusicFriend(34,"Observed","Friend"))};until{!f.friends.state.value.busy&&f.friends.state.value.tracksComplete}}
    private suspend fun playlist(f: Fixture){main{f.friends.playlist(f.api.friend)};until{!f.friends.state.value.busy&&f.friends.state.value.playlistComplete}}
    private suspend fun queued(f: Fixture,count: Int): List<AudioExportTask> {
        until{f.starts.get()==count&&!f.friends.state.value.busy&&!f.model.state.value.busy}
        return withContext(Dispatchers.IO){AudioExportStore(f.app).use{it.taskRows().map{row->row.first}}}
    }
    private suspend fun completedQueue(f: Fixture): Pair<AudioExportTask,String> = withContext(Dispatchers.IO) {
        val task=ExportLayout.task(f.api.song,listOf("CI preserved queue"),1,ExportSource(12,"library","my"))
        AudioExportRuntime.replace(f.app,listOf(task),account=12)
        AudioExportStore(f.app).use{store->
            store.writableDatabase.execSQL("UPDATE tasks SET state='DONE'")
            store.status("FINISHED")
        }
        AudioExportRuntime.refresh()
        task to AudioExportRuntime.state.value.queueId
    }
    private suspend fun assertQueue(f: Fixture,before: Pair<AudioExportTask,String>)=withContext(Dispatchers.IO) {
        AudioExportStore(f.app).use{store->
            assertEquals(listOf(before.first),store.taskRows().map{it.first});assertEquals(before.second,store.snapshot().queueId)
        }
    }
    @Test fun ownPlaylistOpensAndReopensWithoutGlobalCatalogOrRepeatedPages()=fixture{f->
        val catalogs=f.api.catalogs.get();f.api.allowCatalog=false
        main{f.model.select(f.api.own)};until{!f.model.state.value.busy&&f.model.state.value.selected!=null}
        assertEquals(1,f.api.playlists.get());assertEquals(catalogs,f.api.catalogs.get())
        f.api.allowPlaylist=false;main{f.model.select(null);f.model.select(f.api.own)}
        until{!f.model.state.value.busy};assertNull(f.model.state.value.error)
        assertEquals(f.api.initial,f.model.state.value.page!!.tracks);assertEquals(1,f.api.playlists.get())
    }
    @Test fun expiredOwnPlaylistQueuesOnlyItsPagesWithoutReloadingAnyCatalog()=fixture{f->
        main{f.model.select(f.api.own)};until{!f.model.state.value.busy&&f.model.state.value.selected!=null}
        f.clock.addAndGet(60_000);f.api.rows=listOf(f.api.initial[1],f.api.initial[0],f.api.song.copy(id="34_988009"));val catalogs=f.api.catalogs.get();f.api.allowCatalog=false
        main{f.model.prepare()};val tasks=queued(f,1)
        assertEquals(f.api.rows,tasks.map{it.track});assertEquals(2,f.api.playlists.get());assertEquals(catalogs,f.api.catalogs.get())
    }
    @Test fun friendSingleTrackPreemptsAnIncompleteReadAndPreservesTheDisplayedOccurrence()=fixture{f->
        val rows=List(101){i->f.api.song.copy(id=if(i==99)f.api.song.id else "34_${988001+i}")};f.api.rows=rows
        val entered=CompletableDeferred<Unit>();f.api.beforeLibrary={if(it==100){entered.complete(Unit);awaitCancellation()}}
        main{f.friends.open(MusicFriend(34,"Observed","Friend"))};withTimeout(10_000){entered.await()}
        val shown=f.friends.state.value;assertFalse(shown.complete);assertEquals(100,shown.page!!.tracks.size)
        main{f.friends.download(setOf(100))};val tasks=queued(f,1)
        assertEquals(FriendMusicExport.tasks(shown.page!!,shown.user!!,12).last(),tasks.single())
        assertEquals(100,tasks.single().position);assertEquals(2,tasks.single().occurrence)
        assertEquals(2,f.api.libraries.get());assertFalse(f.friends.state.value.complete)
    }
    @Test fun friendPlaylistSelectedAndBulkQueuesReuseTheirSnapshotWithoutCatalogReads()=fixture{f->
        open(f);playlist(f);val catalogs=f.api.catalogs.get();val pages=f.api.playlists.get()
        f.api.allowCatalog=false;f.api.allowPlaylist=false
        val before=f.friends.state.value
        main{f.friends.download(setOf(3))}
        assertEquals(FriendMusicExport.tasks(before.page!!,before.user!!,12,before.playlist).last(),queued(f,1).single())
        withContext(Dispatchers.IO){AudioExportRuntime.clear(f.app)}
        main{f.friends.download()};assertEquals(3,queued(f,2).size)
        assertEquals(catalogs,f.api.catalogs.get());assertEquals(pages,f.api.playlists.get())
    }
    @Test fun expiredFriendBulkSourceUpdatesItsCountWithoutRecheckingOtherSources()=fixture{f->
        open(f);playlist(f);val catalogs=f.api.catalogs.get();val pages=f.api.playlists.get()
        f.clock.addAndGet(60_000);f.api.rows=f.api.initial+f.api.song.copy(id="34_988008");f.api.allowCatalog=false
        main{f.friends.download()};assertEquals(f.api.rows,queued(f,1).map{it.track})
        assertEquals(4,f.friends.state.value.playlist!!.count);assertEquals(4,f.friends.state.value.page!!.count)
        assertEquals(pages+1,f.api.playlists.get());assertEquals(catalogs,f.api.catalogs.get())
    }
    @Test fun newLoginAndLogoutDuringOwnerProofCannotReplaceTheQueue()=fixture{f->
        open(f);val existing=completedQueue(f)
        until{!f.model.state.value.busy&&!f.model.state.value.catalogVerifying}
        val saved=withContext(Dispatchers.IO){f.vault.read()!!}
        withContext(Dispatchers.IO){f.vault.save(MusicAccess("vk1.a."+"ANOTHER".repeat(12),"CI/1",accountId=12))}
        main{f.friends.download(setOf(3))};until{!f.friends.state.value.busy}
        assertEquals(0,f.starts.get());assertEquals(MusicError.ACCOUNT_CHANGED,f.friends.state.value.listProblem!!.code)
        assertQueue(f,existing);withContext(Dispatchers.IO){f.vault.save(saved)}
        main{f.friends.reset()};open(f)
        f.api.beforeProof={withContext(Dispatchers.IO){f.vault.delete()}}
        main{f.friends.download(setOf(3))};until{!f.friends.state.value.busy}
        assertEquals(0,f.starts.get());assertQueue(f,existing)
    }
    @Test fun cancellationOfABulkReadNeverCommitsItsPartialQueue()=fixture{f->
        open(f);playlist(f);f.clock.addAndGet(60_000)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val readJob=CompletableDeferred<Job>()
        f.api.beforePlaylist={readJob.complete(currentCoroutineContext().job);entered.complete(Unit);withContext(NonCancellable){release.await()}}
        try {
            main{f.friends.download()};withTimeout(10_000){entered.await()}
            main{f.friends.pauseRead()};release.complete(Unit)
            withTimeout(10_000){readJob.await().join()}
            until{!f.friends.state.value.busy}
            withContext(Dispatchers.IO){assertTrue(AudioExportStore(f.app).use{it.taskRows().isEmpty()})};assertEquals(0,f.starts.get())
        }finally{release.complete(Unit)}
    }
    @Test fun passiveRefreshReusesMetadataExplicitRefreshFetchesAndKeepsOwnLibraryCache()=fixture{f->
        val count=f.api.catalogs.get();f.api.allowCatalog=false
        main{f.model.refreshCatalog(force=false)};until{!f.model.state.value.busy&&!f.model.state.value.catalogVerifying}
        assertNull(f.model.state.value.catalogError);assertEquals(count,f.api.catalogs.get())
        f.api.allowCatalog=true;main{f.model.refreshCatalog()};until{!f.model.state.value.busy&&!f.model.state.value.catalogVerifying}
        assertEquals(count+1,f.api.catalogs.get())
        main{f.model.prepare()};assertEquals(3,queued(f,1).size);assertEquals(0,f.api.libraries.get())
    }
    @Test fun inaccessibleFreshProfileCannotExposeCachedFriendMusic()=fixture{f->
        open(f);val pages=f.api.libraries.get();f.api.accessible=false
        main{f.friends.open(MusicFriend(34,"Observed","Friend"))};until{!f.friends.state.value.busy}
        assertNull(f.friends.state.value.tracks);assertNull(f.friends.state.value.catalog)
        assertEquals(MusicError.DENIED,f.friends.state.value.problems[FriendMusicTab.TRACKS]!!.code)
        assertEquals(pages,f.api.libraries.get());assertEquals(0,f.starts.get())
    }
    @Test fun anEmptyRefreshedSourceDoesNotEraseThePreviousQueueOrStartAJob()=fixture{f->
        open(f);playlist(f);val existing=completedQueue(f)
        until{!f.model.state.value.busy&&!f.model.state.value.catalogVerifying}
        f.clock.addAndGet(60_000);f.api.rows=emptyList()
        main{f.friends.download()};until{!f.friends.state.value.busy}
        assertEquals(0,f.friends.state.value.page!!.count);assertEquals(0,f.starts.get())
        assertQueue(f,existing)
    }

}
