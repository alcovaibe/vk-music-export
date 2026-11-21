package app.alcovaibe.vkmusicexport

import android.app.Application
import androidx.lifecycle.ViewModel
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
class SearchAddWhileLoadingDeviceTest {
    @Test fun addsTrackBeforeBlockedLibraryPageReturnsAndRefreshesAfterward() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val store = ViewModelStore()
        val vault = MusicAccessStore(app)
        val receipt = LibraryAddReceipts(app)
        val owner = 12L
        val old = NativeTrack("12_71", "Первый", "Исполнитель", 180)
        val second = NativeTrack("12_72", "Второй", "Исполнитель", 180)
        val found = NativeTrack("-34_73", "Найденный", "Исполнитель", 180)
        val added = found.copy(id = "12_73")
        val entered = CompletableDeferred<Unit>()
        val resumeFirstRead = CompletableDeferred<Unit>()
        val posted = CompletableDeferred<Unit>()
        val api = object : MusicApi {
            override val capabilities = ApiCapabilities(search = CapabilityStatus.SUPPORTED)
            override suspend fun accountOwner(access: MusicAccess) = owner
            override suspend fun library(access: MusicAccess) =
                NativeProbe(owner, if (posted.isCompleted) NativePage(listOf(added, old), 3) else NativePage(listOf(old), 2))
            override suspend fun libraryPage(access: MusicAccess, owner: Long, offset: Int): NativePage {
                if (offset == 1) {
                    entered.complete(Unit)
                    resumeFirstRead.await()
                    return NativePage(listOf(second), 2)
                }
                assertEquals(2, offset)
                return NativePage(listOf(second), 3)
            }
            override suspend fun catalog(access: MusicAccess, owner: Long) = NativeCatalog(emptyList(), 0)
            override suspend fun playlist(access: MusicAccess, list: NativePlaylist): NativePage = error("No playlist")
            override suspend fun media(access: MusicAccess, track: NativeTrack): String = error("No audio")
            override suspend fun searchMusic(access: MusicAccess, query: String, offset: Int) =
                MetadataMatches(listOf(found), 1, offset)
            override suspend fun addLibraryTrack(access: MusicAccess, owner: Long, track: NativeTrack,
                beforeSend: suspend () -> Unit): Long {
                assertEquals(found.id, track.id)
                beforeSend()
                posted.complete(Unit)
                return 73
            }
        }
        lateinit var model: NativeMusicViewModel
        suspend fun until(condition: () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(20) }
        try {
            receipt.clear(owner, found.id)
            withContext(Dispatchers.IO) { vault.save(MusicAccess("a".repeat(64), "CI", accountId = owner)) }
            instrumentation.runOnMainSync {
                model = ViewModelProvider(store, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = NativeMusicViewModel(app, api) as T
                })[NativeMusicViewModel::class.java]
            }
            until { model.state.value.initialized }
            instrumentation.runOnMainSync {
                model.setForeground(true)
                model.checkAccess()
                model.setSearchActive(true)
                model.searchMusic("Найденный")
            }
            withTimeout(10_000) { entered.await() }
            until { model.musicSearch.value.phase == MusicSearchPhase.RESULTS }

            instrumentation.runOnMainSync { model.addSearchTrack(model.musicSearch.value, 0) }
            withTimeout(10_000) { posted.await() }
            until { !model.searchAdding.value && model.addedTracks.value[found.id] == added.id }
            assertTrue(model.state.value.libraryRefreshing)
            assertFalse(resumeFirstRead.isCompleted)

            resumeFirstRead.complete(Unit)
            until {
                !model.state.value.busy && model.state.value.probe?.library?.count == 3 &&
                    model.state.value.page?.tracks?.firstOrNull()?.id == added.id
            }
            assertFalse(model.state.value.libraryStale)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            receipt.clear(owner, found.id)
            withContext(Dispatchers.IO) { vault.delete() }
        }
    }
}
