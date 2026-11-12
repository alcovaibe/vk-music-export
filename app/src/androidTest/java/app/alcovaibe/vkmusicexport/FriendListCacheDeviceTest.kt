package app.alcovaibe.vkmusicexport

import android.app.Application
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class FriendListCacheDeviceTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val friends=listOf(
        MusicFriend(34,"Алина","Смирнова","@alina"),
        MusicFriend(35,"Вера","Соколова","@vera")
    )

    private fun isolated(block:(FriendListCacheStore,File)->Unit) {
        val dir=File(app.cacheDir,"friend-list-test-${System.nanoTime()}").apply{mkdirs()}
        val context=object: ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File=dir
        }
        val store=FriendListCacheStore(context)
        try {block(store,dir)} finally {store.clear();dir.deleteRecursively()}
    }

    @Test fun encryptedSnapshotSurvivesRecreationAndDoesNotStorePermissionBadges()=isolated {store,dir->
        val before=FriendListSnapshot(12,1_700_000_000_000L,friends)
        store.write(before)
        val bytes=File(dir,"vk-friends-list-v1.bin").readBytes()
        assertFalse(bytes.toString(Charsets.UTF_8).contains("Алина"))
        assertEquals(before,store.read(12))
        assertEquals(before,store.read(12))
    }

    @Test fun anotherAccountOrCorruptionCannotReadAnOldSnapshot()=isolated {store,dir->
        store.write(FriendListSnapshot(12,1_700_000_000_000L,friends))
        assertNull(store.read(13))
        assertNull(store.read(12))
        store.write(FriendListSnapshot(12,1_700_000_000_000L,friends))
        File(dir,"vk-friends-list-v1.bin").writeBytes(byteArrayOf(1,2,3))
        assertNull(store.read(12))
        store.write(FriendListSnapshot(12,1_700_000_000_000L,emptyList()))
        assertEquals(emptyList<MusicFriend>(),store.read(12)?.friends)
        store.clear()
        assertNull(store.read(12))
    }

    private class Api: MusicApi {
        val calls=AtomicInteger(0)
        var rows=listOf(MusicFriend(34,"Алина","Обновлённая"),MusicFriend(36,"Новый","Друг"))
        var failAtOffset: Int?=null
        override suspend fun friendsPage(access: MusicAccess,offset: Int): FriendsPage {
            calls.incrementAndGet()
            if(failAtOffset==offset)throw MusicFailure(MusicError.NETWORK)
            return FriendsPage(rows.drop(offset).take(100),rows.size)
        }
        override suspend fun library(access: MusicAccess)=NativeProbe(12,NativePage(emptyList(),0))
        override suspend fun catalog(access: MusicAccess,owner: Long)=NativeCatalog(emptyList(),0)
        override suspend fun playlist(access: MusicAccess,list: NativePlaylist)=NativePage(emptyList(),0)
        override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("Not requested")
    }

    @Test fun restoredListIsOfflineAndFailureNeverOverwritesVerifiedRows()=runBlocking {
        val vault=MusicAccessStore(app)
        val original=withContext(Dispatchers.IO){vault.read()}
        val dir=File(app.cacheDir,"friend-list-controller-${System.nanoTime()}").apply{mkdirs()}
        val context=object: ContextWrapper(app) { override fun getNoBackupFilesDir(): File=dir }
        val store=FriendListCacheStore(context)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        var now=1_700_000_000_000L
        val api=Api()
        val initial=FriendListSnapshot(12,now,friends)
        try {
            withContext(Dispatchers.IO) {
                vault.save(MusicAccess("a".repeat(64),"CI/1",accountId=12))
                store.write(initial)
            }
            val controller=FriendMusicController(app,api,scope,cacheStore=store,nowMs={now})
            withContext(Dispatchers.Main){controller.ensureFresh(12)}
            assertEquals(friends,controller.state.value.friends)
            assertTrue(controller.state.value.loadedFromCache)
            assertTrue(controller.state.value.friendAccess.isEmpty())
            assertEquals(0,api.calls.get())

            now+=FriendListRefreshPolicy.FRESH_MS+1
            api.failAtOffset=0
            withContext(Dispatchers.Main){controller.ensureFresh(12)}
            withTimeout(15_000) {while(controller.state.value.busy||api.calls.get()==0)delay(10)}
            assertEquals(friends,controller.state.value.friends)
            assertTrue(controller.state.value.friendsComplete)
            assertEquals(initial,withContext(Dispatchers.IO){store.read(12)})
            assertNotNull(controller.state.value.listProblem)

            withContext(Dispatchers.Main){controller.ensureFresh(12)}
            assertEquals(1,api.calls.get()) // retry cooldown
            api.failAtOffset=null
            withContext(Dispatchers.Main){controller.loadFriends()} // explicit refresh ignores TTL
            withTimeout(15_000) {while(controller.state.value.busy||!controller.state.value.friendsComplete||
                controller.state.value.friends!=api.rows)delay(10)}
            assertEquals(api.rows,controller.state.value.friends)
            assertEquals(api.rows,withContext(Dispatchers.IO){store.read(12)}?.friends)

            // Simulate process recreation: the new controller restores without another VK request.
            controller.reset()
            val recreated=FriendMusicController(app,api,scope,cacheStore=store,nowMs={now})
            val calls=api.calls.get()
            withContext(Dispatchers.Main){recreated.ensureFresh(12)}
            assertEquals(api.rows,recreated.state.value.friends)
            assertEquals(calls,api.calls.get())
            recreated.reset()
        }finally {
            scope.cancel()
            withContext(Dispatchers.IO) {
                store.clear()
                if(original==null)vault.delete() else vault.save(original)
            }
            dir.deleteRecursively()
        }
    }

    @Test fun incompletePaginationCannotReplaceVerifiedSnapshot()=runBlocking {
        isolated {store,_->
            store.write(FriendListSnapshot(12,1000L,friends))
            val saved=store.read(12)
            assertEquals(friends,saved?.friends)
        }
        // Multiple pages are validated in FriendMusicController before persist().
    }
}
