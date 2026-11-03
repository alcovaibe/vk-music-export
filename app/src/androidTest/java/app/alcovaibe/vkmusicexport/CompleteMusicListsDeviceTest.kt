package app.alcovaibe.vkmusicexport

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import app.alcovaibe.vkmusicexport.music.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompleteMusicListsDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()

    @Test fun libraryAndPlaylistLoadAllPagesAndLastCollectionNeedsOnlyScrolling() {
        val tracks=(1..205).map{NativeTrack("12_$it","Track $it","CI",1,restricted=true)}.toMutableList()
        tracks[100]=tracks[0] // A repeated song is still a separate ordered position.
        val lists=(1L..205L).map{NativePlaylist(12,it,"List $it",205,null,null,null,null,PlaylistKind.PLAYLIST)}
        val libraryOffsets=mutableListOf<Int>();val catalogOffsets=mutableListOf<Int>();val playlistOffsets=mutableListOf<Int>()
        var failNextPlaylist=false
        val api=object: MusicApi {
            override suspend fun library(access: MusicAccess): NativeProbe {
                libraryOffsets.add(0);return NativeProbe(12,NativePage(tracks.take(100),tracks.size))
            }
            override suspend fun libraryPage(access: MusicAccess,owner: Long,offset: Int): NativePage {
                libraryOffsets.add(offset);return NativePage(tracks.drop(offset).take(100),tracks.size)
            }
            override suspend fun catalog(access: MusicAccess,owner: Long)=catalogPage(access,owner,0)
            override suspend fun catalogPage(access: MusicAccess,owner: Long,offset: Int): NativeCatalog {
                catalogOffsets.add(offset);return NativeCatalog(lists.drop(offset).take(100),lists.size)
            }
            override suspend fun playlist(access: MusicAccess,list: NativePlaylist)=playlistPage(access,list,0)
            override suspend fun playlistPage(access: MusicAccess,list: NativePlaylist,offset: Int): NativePage {
                playlistOffsets.add(offset)
                if(failNextPlaylist&&offset==100)throw MusicFailure(MusicError.NETWORK)
                return NativePage(tracks.drop(offset).take(100),tracks.size)
            }
            override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("No audio network allowed")
        }
        lateinit var model: NativeMusicViewModel
        ui.runOnUiThread {
            model=ViewModelProvider(ui.activity,object: ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T: androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    NativeMusicViewModel(ui.activity.application,api) as T
            })["complete-lists-test",NativeMusicViewModel::class.java]
        }
        val vault=MusicAccessStore(ui.activity.application)
        try {
            ui.waitUntil(10000){model.state.value.initialized}
            ui.runOnUiThread{model.configure("vk1.a."+"SYNTHETIC".repeat(12),"CI/1")}
            ui.waitUntil(15000){!model.state.value.busy&&model.state.value.catalog!=null}
            assertEquals(listOf(0,100,200),libraryOffsets)
            assertEquals(listOf(0,100,200),catalogOffsets)
            assertEquals(tracks,model.state.value.page!!.tracks)
            assertEquals(tracks,model.state.value.probe!!.library.tracks)
            ui.runOnUiThread{ui.activity.setContent{MusicTheme{NativeMusicPanel(model,true,{})}}}
            ui.onNodeWithTag("native_music_screen_list").performScrollToNode(hasTestTag("music_track_205_12_205"))
            ui.onNodeWithTag("music_track_205_12_205").assertIsDisplayed()
            ui.runOnUiThread{model.section(CatalogSection.PLAYLISTS)}
            ui.onNodeWithTag("native_music_screen_list").performScrollToNode(hasTestTag("catalog_list_12_205"))
            ui.onNodeWithTag("catalog_list_12_205").assertIsDisplayed().performClick()
            ui.waitUntil(15000){!model.state.value.busy&&model.state.value.selected?.id==205L}
            assertEquals(listOf(0,100,200),playlistOffsets)
            assertEquals(tracks,model.state.value.page!!.tracks)
            ui.onNodeWithTag("native_music_screen_list").performScrollToNode(hasTestTag("music_track_205_12_205"))
            ui.onNodeWithTag("music_track_205_12_205").assertIsDisplayed()
            for(label in listOf("Ещё подборки","Следующие треки","Предыдущие треки","Изменить доступ ВК"))
                ui.onNodeWithText(label).assertDoesNotExist()
            ui.runOnUiThread{model.section(CatalogSection.LIBRARY)}
            assertEquals(tracks,model.state.value.page!!.tracks)
            assertEquals(listOf(0,100,200),libraryOffsets)
            // Keep verified early positions visible if a later page fails, but never
            // claim that the playlist is complete or permit playback of a partial page.
            ui.runOnUiThread{failNextPlaylist=true;model.select(lists[0])}
            ui.waitUntil(15000){!model.state.value.busy}
            assertEquals(MusicError.NETWORK,model.state.value.error)
            assertEquals(100,model.state.value.page?.tracks?.size)
            assertEquals(205,model.state.value.page?.count)
            ui.runOnUiThread{failNextPlaylist=false;model.select(lists[0])}
            ui.waitUntil(15000){!model.state.value.busy}
            assertEquals(tracks,model.state.value.page!!.tracks)
        }finally {
            ui.runOnUiThread{model.cancel()}
            vault.delete()
        }
    }
    @Test fun tracksAppearBeforeLastPageAndRefreshDoesNotEraseLastConfirmedSnapshot() {
        val first=NativeTrack("12_1","Track 1","CI",120)
        val second=NativeTrack("12_2","Track 2","CI",120)
        val lastPage=CompletableDeferred<Unit>()
        val nextRefresh=CompletableDeferred<Unit>()
        var holdRefresh=false
        val api=object: MusicApi {
            override suspend fun library(access: MusicAccess): NativeProbe {
                if(holdRefresh)nextRefresh.await()
                return NativeProbe(12,NativePage(listOf(first),2))
            }
            override suspend fun libraryPage(access: MusicAccess,owner: Long,offset: Int): NativePage {
                assertEquals(1,offset)
                lastPage.await()
                return NativePage(listOf(second),2)
            }
            override suspend fun catalog(access: MusicAccess,owner: Long)=NativeCatalog(emptyList(),0)
            override suspend fun playlist(access: MusicAccess,list: NativePlaylist)=NativePage(emptyList(),0)
            override suspend fun media(access: MusicAccess,track: NativeTrack)=""
        }
        lateinit var model: NativeMusicViewModel
        val vault=MusicAccessStore(ui.activity.application)
        try {
            ui.runOnUiThread {
                model=ViewModelProvider(ui.activity,object: ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T: androidx.lifecycle.ViewModel>
                        create(modelClass: Class<T>): T =
                        NativeMusicViewModel(ui.activity.application,api) as T
                })["progressive-library-test",NativeMusicViewModel::class.java]
            }
            ui.waitUntil(10000){model.state.value.initialized}
            ui.runOnUiThread{model.configure("a".repeat(64),"CI/1")}
            ui.waitUntil(15000){model.state.value.busy&&model.state.value.page?.tracks?.size==1}
            assertEquals(2,model.state.value.page?.count)
            assertTrue(model.state.value.libraryRefreshing)
            lastPage.complete(Unit)
            ui.waitUntil(15000){!model.state.value.busy&&model.state.value.page?.tracks?.size==2}
            assertFalse(model.state.value.libraryStale)
            holdRefresh=true
            ui.runOnUiThread{model.checkAccess()}
            ui.waitUntil(10000){model.state.value.busy&&model.state.value.libraryStale}
            assertEquals(listOf(first,second),model.state.value.page?.tracks)
            nextRefresh.complete(Unit)
            ui.waitUntil(15000){!model.state.value.busy}
            assertEquals(listOf(first,second),model.state.value.page?.tracks)
            assertFalse(model.state.value.libraryStale)
        }finally {
            lastPage.complete(Unit);nextRefresh.complete(Unit)
            if(::model.isInitialized)ui.runOnUiThread{model.cancel()}
            vault.delete()
        }
    }

}
