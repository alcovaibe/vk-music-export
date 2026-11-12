package app.alcovaibe.vkmusicexport

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class FriendPreviewLifecycleDeviceTest {
    private class Api: MusicApi {
        val calls=Collections.synchronizedList(mutableListOf<Long>())
        val cancelled=AtomicInteger()
        var probe: suspend(Long)->Unit={}
        val rows=(34L..40L).map{MusicFriend(it,"CI","Friend $it")}
        override suspend fun friendsPage(access: MusicAccess,offset: Int)=FriendsPage(rows,rows.size)
        override suspend fun friendProfile(access: MusicAccess,id: Long)=rows.first{it.id==id}
        override suspend fun probeFriendAudio(access: MusicAccess,owner: Long) {
            calls.add(owner)
            try{probe(owner)}catch(e: CancellationException){cancelled.incrementAndGet();throw e}
        }
        override suspend fun library(access: MusicAccess)=NativeProbe(12,NativePage(emptyList(),0))
        override suspend fun libraryPage(access: MusicAccess,owner: Long,offset: Int)=NativePage(emptyList(),0)
        override suspend fun catalog(access: MusicAccess,owner: Long)=NativeCatalog(emptyList(),0)
        override suspend fun playlist(access: MusicAccess,list: NativePlaylist)=NativePage(emptyList(),0)
        override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("No transfer")
    }
    private suspend fun main(block:()->Unit)=withContext(Dispatchers.Main.immediate){block();yield()}
    private suspend fun until(check:()->Boolean){withTimeout(15_000){while(!check())delay(10)}}
    private fun fixture(block:suspend(FriendMusicController,Api,MusicAccessStore)->Unit)=runBlocking<Unit> {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val vault=MusicAccessStore(app);val previous=withContext(Dispatchers.IO){vault.read()}
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate);val api=Api()
        val controller=FriendMusicController(app,api,scope)
        try {
            withContext(Dispatchers.IO){FriendListCacheStore(app).clear();vault.save(MusicAccess("vk1.a."+"SYNTHETIC".repeat(12),"CI",accountId=12))}
            main{controller.loadFriends()};until{controller.state.value.friendsComplete&&!controller.state.value.busy}
            block(controller,api,vault)
        }finally {
            main{controller.reset();scope.cancel()}
            withContext(Dispatchers.IO){FriendListCacheStore(app).clear();if(previous==null)vault.delete()else vault.save(previous)}
        }
    }
    @Test fun pauseCancelsBothProbesAndReturnReusesOnlyConfirmedVisibleResults()=fixture {c,api,_->
        main{c.setForeground(true);c.prioritizeVisible(listOf(34));c.setPreviewVisible(true)}
        until{c.state.value.friendAccess[34L]==FriendAudioAccess.AVAILABLE}
        val release=CompletableDeferred<Unit>()
        api.probe={release.await()}
        main{c.prioritizeVisible(listOf(34,35,36,37))}
        until{api.calls.size==3}
        main{c.setForeground(false);c.pauseRead()}
        until{api.cancelled.get()==2}
        assertEquals(34L,api.calls.first())
        assertEquals(setOf(35L,36L),api.calls.drop(1).toSet())
        assertFalse(c.state.value.friendAccess.containsKey(35L))
        api.probe={};release.complete(Unit)
        main{c.setForeground(true)}
        until{listOf(35L,36L,37L).all{c.state.value.friendAccess[it]==FriendAudioAccess.AVAILABLE}}
        assertEquals(1,api.calls.count{it==34L})
        assertEquals(2,api.calls.count{it==35L});assertEquals(2,api.calls.count{it==36L})
        assertFalse(api.calls.contains(38L))
    }
    @Test fun profileAndHiddenDestinationStopProbesAndBackRestartsTheVisibleList()=fixture {c,api,_->
        api.probe={awaitCancellation()}
        main{c.setForeground(true);c.prioritizeVisible(listOf(34,35,36));c.setPreviewVisible(true)}
        until{api.calls.size==2}
        main{c.open(api.rows.first())}
        until{api.cancelled.get()==2&&!c.state.value.busy}
        assertEquals(2,api.calls.size)
        main{c.setPreviewVisible(false);c.back()}
        assertEquals(2,api.calls.size)
        api.probe={}
        main{c.setPreviewVisible(true)}
        until{c.state.value.friendAccess[36L]==FriendAudioAccess.AVAILABLE}
        assertFalse(api.calls.contains(37L))
    }
    @Test fun logoutAndNewLoginCannotPublishAnOldDenialOrReuseItsCache()=fixture {c,api,vault->
        val release=CompletableDeferred<Unit>()
        api.probe={release.await();throw MusicFailure(MusicError.DENIED,201)}
        main{c.setForeground(true);c.prioritizeVisible(listOf(34));c.setPreviewVisible(true)}
        until{api.calls.size==1}
        withContext(Dispatchers.IO){vault.delete()}
        release.complete(Unit)
        until{c.state.value.listProblem?.code==MusicError.ACCOUNT_CHANGED}
        assertTrue(c.state.value.friendAccess.isEmpty())
        withContext(Dispatchers.IO){vault.save(MusicAccess("vk1.a."+"OTHER-CI".repeat(12),"CI",accountId=99))}
        api.probe={}
        main{c.reset();c.loadFriends()}
        until{c.state.value.friendsComplete&&!c.state.value.busy}
        main{c.prioritizeVisible(listOf(34))}
        until{c.state.value.friendAccess[34L]==FriendAudioAccess.AVAILABLE}
        assertEquals(2,api.calls.size)
    }
}
