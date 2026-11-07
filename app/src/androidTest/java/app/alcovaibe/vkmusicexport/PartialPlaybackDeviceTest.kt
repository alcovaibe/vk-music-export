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

@RunWith(AndroidJUnit4::class)
class PartialPlaybackDeviceTest {
    private class Api: MusicApi {
        val first=NativeTrack("12_771","Zulu","CI",180)
        val second=first.copy(id="12_772",title="Alpha")
        val gate=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>()
        var blockOwn=true;var failRest=false;var holdInitial=false
        val initialEntered=CompletableDeferred<Unit>();val initialRelease=CompletableDeferred<Unit>()
        override suspend fun accountOwner(access: MusicAccess)=12L
        override suspend fun library(access: MusicAccess): NativeProbe {
            if(holdInitial){initialEntered.complete(Unit);initialRelease.await()}
            return NativeProbe(12,NativePage(listOf(first,second,first),if(blockOwn)4 else 3))
        }
        override suspend fun libraryPage(access: MusicAccess,owner: Long,offset: Int): NativePage {
            if(owner==34L&&offset==0)return NativePage(listOf(first,second,first),4)
            entered.complete(Unit);gate.await()
            if(failRest)throw MusicFailure(MusicError.DENIED,201)
            return NativePage(listOf(first.copy(id="12_773")),4)
        }
        override suspend fun friendProfile(access: MusicAccess,id: Long)=MusicFriend(id,"Friend","CI")
        override suspend fun catalog(access: MusicAccess,owner: Long)=NativeCatalog(emptyList(),0)
        override suspend fun playlist(access: MusicAccess,list: NativePlaylist): NativePage=error("No playlist read")
        override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("No remote media request in queue contract test")
    }
    private suspend fun until(check:()->Boolean){withTimeout(20_000){while(!check())delay(20)}}
    private fun fixture(blockOwn: Boolean,block:suspend(NativeMusicViewModel,Api)->Unit)=runBlocking<Unit> {
        val inst=InstrumentationRegistry.getInstrumentation();val app=inst.targetContext.applicationContext as Application
        val vault=MusicAccessStore(app);val old=withContext(Dispatchers.IO){vault.read()};val holder=ViewModelStore();val api=Api().apply{this.blockOwn=blockOwn}
        lateinit var model: NativeMusicViewModel
        try {
            inst.runOnMainSync{model=ViewModelProvider(holder,object: ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T: androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T=NativeMusicViewModel(app,api) as T
            })[NativeMusicViewModel::class.java]}
            until{model.state.value.initialized}
            inst.runOnMainSync{model.setForeground(true);model.configure("vk1.a."+"SYNTHETIC".repeat(12),"CI/1")}
            block(model,api)
        }finally {
            api.gate.complete(Unit);api.initialRelease.complete(Unit);inst.runOnMainSync{holder.clear()}
            withContext(Dispatchers.IO){if(old==null)vault.delete()else vault.save(old)}
        }
    }
    @Test fun ownSecondPageBarrierDoesNotBlockClickedDuplicateAndLaterFailureDoesNotMutateQueue()=fixture(true){model,api->
        withTimeout(20_000){api.entered.await()}
        until{model.state.value.page?.tracks?.size==3}
        val state=model.state.value;assertTrue(state.busy);assertFalse(api.gate.isCompleted)
        val selected=PlaybackLists.select(PlaybackLists.library(state,MusicLibrarySort.TITLE),2)!!
        assertEquals(api.first,selected.entries[2].track);assertEquals(3,selected.entries[2].download!!.position)
        assertEquals(2,selected.entries[2].download!!.occurrence);assertEquals(state.readSessionId,selected.entries[2].readSessionId)
        api.failRest=true;api.gate.complete(Unit);until{!model.state.value.busy}
        assertEquals(3,selected.entries.size);assertEquals(2,selected.start)
    }
    @Test fun cachedOwnPageRemainsPlayableWhileRefreshWaitsForFirstResponse()=fixture(false){model,api->
        until{!model.state.value.busy&&model.state.value.probe!=null}
        val before=model.state.value;api.holdInitial=true
        InstrumentationRegistry.getInstrumentation().runOnMainSync{model.checkAccess()}
        withTimeout(20_000){api.initialEntered.await()}
        val during=model.state.value
        assertTrue(during.busy);assertTrue(during.libraryStale);assertEquals(before.readSessionId,during.readSessionId)
        val selected=PlaybackLists.select(PlaybackLists.library(during,MusicLibrarySort.DEFAULT),2)!!
        assertEquals(3,selected.entries[2].download!!.position);assertFalse(api.initialRelease.isCompleted)
        assertEquals(before.page!!.tracks[2],selected.entries[2].track)
    }
    @Test fun friendSecondPageBarrierAllowsSearchResultAndKeepsViewerDifferentFromFriendOwner()=fixture(false){model,api->
        until{!model.state.value.busy&&model.state.value.probe!=null}
        InstrumentationRegistry.getInstrumentation().runOnMainSync{model.friends.open(MusicFriend(34,"Friend","CI"))}
        withTimeout(20_000){api.entered.await()};until{model.friends.state.value.page?.tracks?.size==3}
        val state=model.friends.state.value;assertTrue(state.busy);assertFalse(state.complete)
        val rows=PlaybackLists.friends(state.copy(musicQuery="Zulu"));val selected=PlaybackLists.select(rows,1)!!
        assertEquals(3,selected.entries[1].download!!.position);assertEquals(2,selected.entries[1].download!!.occurrence)
        assertEquals(12L,selected.entries[1].account);assertEquals(34L,ExportLayout.friendId(selected.entries[1].download!!.source!!))
        assertEquals(state.readSessionId,selected.entries[1].readSessionId);assertFalse(api.gate.isCompleted)
    }
}
