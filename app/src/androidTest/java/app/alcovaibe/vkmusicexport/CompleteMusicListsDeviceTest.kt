package app.alcovaibe.vkmusicexport

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
            // A failed second page must not retain the previous selection or expose a partial snapshot.
            ui.runOnUiThread{failNextPlaylist=true;model.select(lists[0])}
            ui.waitUntil(15000){!model.state.value.busy}
            assertEquals(MusicError.NETWORK,model.state.value.error)
            assertNull(model.state.value.page)
            ui.runOnUiThread{failNextPlaylist=false;model.select(lists[0])}
            ui.waitUntil(15000){!model.state.value.busy}
            assertEquals(tracks,model.state.value.page!!.tracks)
        }finally {
            ui.runOnUiThread{model.cancel()}
            vault.delete()
        }
    }
}
