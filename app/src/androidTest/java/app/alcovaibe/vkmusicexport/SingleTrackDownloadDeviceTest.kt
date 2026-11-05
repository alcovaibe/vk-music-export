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

@RunWith(AndroidJUnit4::class)
class SingleTrackDownloadDeviceTest {
    private class Api: MusicApi {
        val song=NativeTrack("12_990801","Snapshot CI","CI",1)
        val snapshot=NativePage(listOf(song,song.copy(id="12_990802"),song),3)
        val list=NativePlaylist(12,990803,"Snapshot CI playlist",3,null,null,null,null,PlaylistKind.PLAYLIST)
        val reads=AtomicInteger();val proofs=AtomicInteger()
        var allowLists=true;var owner=12L;var liveTracks=snapshot.tracks
        var beforeProofReturns: (suspend()->Unit)?=null
        private fun read(){reads.incrementAndGet();check(allowLists){"No list reads after analysis"}}
        override suspend fun accountOwner(access: MusicAccess): Long {
            proofs.incrementAndGet();beforeProofReturns?.invoke();return owner
        }
        override suspend fun library(access: MusicAccess): NativeProbe {read();return NativeProbe(12,NativePage(liveTracks,liveTracks.size))}
        override suspend fun catalog(access: MusicAccess,owner: Long): NativeCatalog {read();return NativeCatalog(listOf(list),1)}
        override suspend fun playlist(access: MusicAccess,list: NativePlaylist): NativePage {read();return NativePage(liveTracks,liveTracks.size)}
        override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("Queue admission must not download audio")
    }
    private suspend fun until(check:()->Boolean){withTimeout(15_000){while(!check())delay(20)}}
    private fun fixture(block: suspend(NativeMusicViewModel,Api,MusicAccessStore,AtomicInteger)->Unit)=runBlocking<Unit> {
        val inst=InstrumentationRegistry.getInstrumentation()
        val app=inst.targetContext.applicationContext as Application
        val vault=MusicAccessStore(app);val old=withContext(Dispatchers.IO){vault.read()}
        val holder=ViewModelStore();val api=Api();val starts=AtomicInteger()
        lateinit var model: NativeMusicViewModel
        try {
            withContext(Dispatchers.IO){AudioExportRuntime.initialize(app);AudioExportRuntime.clear(app)}
            inst.runOnMainSync {
                model=ViewModelProvider(holder,object: ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T: androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T=
                        NativeMusicViewModel(app,api,queueStart={starts.incrementAndGet();Unit}) as T
                })[NativeMusicViewModel::class.java]
            }
            until{model.state.value.initialized}
            inst.runOnMainSync{model.setForeground(true);model.configure("vk1.a."+"SYNTHETIC".repeat(12),"CI/1")}
            until{!model.state.value.busy&&model.state.value.probe!=null&&!model.state.value.catalogVerifying}
            block(model,api,vault,starts)
        }finally {
            inst.runOnMainSync{holder.clear()}
            withContext(Dispatchers.IO){
                AudioExportRuntime.clear(app)
                if(old==null)vault.delete()else vault.save(old)
            }
        }
    }
    private fun click(model: NativeMusicViewModel,api: Api)=
        InstrumentationRegistry.getInstrumentation().runOnMainSync{model.download(api.song,3)}
    private suspend fun queued(model: NativeMusicViewModel): AudioExportTask {
        until{!model.state.value.busy&&model.downloadsOpen.value}
        assertNull(model.state.value.error)
        return withContext(Dispatchers.IO){AudioExportStore(InstrumentationRegistry.getInstrumentation().targetContext).use{it.taskRows().single().first}}
    }
    @Test fun libraryUsesDisplayedOccurrenceWithoutAnyListRequest()=fixture {model,api,_,starts->
        val reads=api.reads.get();api.allowLists=false
        api.liveTracks=listOf(api.snapshot.tracks[1],api.song) // Position/occurrence changed remotely.
        click(model,api)
        assertEquals(ExportLayout.library(api.snapshot,albums=false,account=12).last(),queued(model))
        assertEquals(1,starts.get());assertEquals(1,api.proofs.get());assertEquals(reads,api.reads.get())
    }
    @Test fun playlistUsesSnapshotWithoutRereadingCatalogOrCurrentMembership()=fixture {model,api,_,starts->
        InstrumentationRegistry.getInstrumentation().runOnMainSync{model.select(api.list)}
        until{!model.state.value.busy&&model.state.value.selected!=null}
        val reads=api.reads.get();api.allowLists=false
        api.liveTracks=emptyList() // Exact-ID download does not assert current playlist membership.
        click(model,api)
        assertEquals(ExportLayout.playlist(api.snapshot,api.list,account=12).last(),queued(model))
        assertEquals(1,starts.get());assertEquals(1,api.proofs.get());assertEquals(reads,api.reads.get())
    }
    @Test fun foreignAccountProofCannotReplaceQueue()=fixture {model,api,_,starts->
        api.allowLists=false;api.owner=99
        click(model,api);until{!model.state.value.busy&&model.state.value.error!=null}
        assertEquals(MusicError.ACCOUNT_CHANGED,model.state.value.error)
        assertEquals(0,starts.get());assertEquals(0,AudioExportRuntime.state.value.total)
    }
    @Test fun newLoginOfSameOwnerCannotUseOldSnapshot()=fixture {model,api,vault,starts->
        withContext(Dispatchers.IO){vault.save(MusicAccess("vk1.a."+"ANOTHER".repeat(12),"CI/1",accountId=12))}
        api.allowLists=false
        click(model,api);until{!model.state.value.busy&&model.state.value.error!=null}
        assertEquals(MusicError.ACCESS,model.state.value.error)
        assertEquals(0,api.proofs.get());assertEquals(0,starts.get());assertEquals(0,AudioExportRuntime.state.value.total)
    }
    @Test fun logoutWhileAccountProofRunsCannotPersistOrStartTask()=fixture {model,api,vault,starts->
        api.allowLists=false;api.beforeProofReturns={withContext(Dispatchers.IO){vault.delete()}}
        click(model,api);until{!model.state.value.busy&&model.state.value.error!=null}
        assertEquals(MusicError.ACCESS,model.state.value.error)
        assertEquals(0,starts.get());assertEquals(0,AudioExportRuntime.state.value.total)
    }
    @Test fun cancellationDuringAccountProofCannotPersistOrStartTask()=fixture {model,api,_,starts->
        val entered=CompletableDeferred<Unit>()
        api.allowLists=false;api.beforeProofReturns={entered.complete(Unit);awaitCancellation()}
        click(model,api);withTimeout(10_000){entered.await()}
        InstrumentationRegistry.getInstrumentation().runOnMainSync{model.cancel()}
        until{!model.state.value.busy}
        assertEquals(0,starts.get());assertEquals(0,AudioExportRuntime.state.value.total)
    }
    @Test fun unfinishedQueueIsPreservedWithoutStartingAccountProof()=fixture {model,api,_,starts->
        val existing=ExportLayout.single(api.snapshot,api.song,1,12)
        withContext(Dispatchers.IO){AudioExportRuntime.replace(InstrumentationRegistry.getInstrumentation().targetContext,listOf(existing),account=12)}
        api.allowLists=false
        click(model,api);until{!model.state.value.busy&&model.state.value.error!=null}
        assertEquals(MusicError.BUSY,model.state.value.error)
        assertEquals(0,api.proofs.get());assertEquals(0,starts.get())
        val rows=withContext(Dispatchers.IO){AudioExportStore(InstrumentationRegistry.getInstrumentation().targetContext).use{it.taskRows()}}
        assertEquals(listOf(existing),rows.map{it.first})
    }
}
