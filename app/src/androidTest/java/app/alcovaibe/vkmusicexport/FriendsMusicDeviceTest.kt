package app.alcovaibe.vkmusicexport

import android.app.Application
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FriendsMusicDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val songs=listOf(NativeTrack("90_1","Полуночный трамвай","Ольга Мартынова",293,subtitle="Live"),
        NativeTrack("90_2","Стеклянный город","Сигнал",208,subtitle="Remix"),NativeTrack("90_3","Север внутри","Кирилл Зотов",226,subtitle="Cover"))
    private val playlist=NativePlaylist(-55,7,"В дорогу",3,null,null,null,0,PlaylistKind.PLAYLIST,following=false)
    private val album=NativePlaylist(-66,9,"Северные огни",3,null,null,null,1,PlaylistKind.ALBUM,following=false)
    private inner class Api: MusicApi {
        var extra: NativePlaylist?=null
        var rows=listOf(MusicFriend(34,"Алина","Смирнова","@old"),MusicFriend(35,"Ирина","Лебедева","@irina"),
            MusicFriend(36,"Максим","Ковалёв"),MusicFriend(37,"Денис","Орлов"),MusicFriend(38,"Софья","Белова"),
            MusicFriend(39,"Артём","Миронов"),MusicFriend(40,"Полина","Жукова"),MusicFriend(41,"Егор","Рябов"))
        val offsets=mutableListOf<Int>();var entered: CompletableDeferred<Unit>?=null;var hold: CompletableDeferred<Unit>?=null
        override suspend fun friendsPage(access: MusicAccess,offset: Int)=FriendsPage(rows.drop(offset).take(100),rows.size).also{offsets.add(offset)}
        override suspend fun friendProfile(access: MusicAccess,id: Long)=rows.first{it.id==id}.let{if(id==34L)it.copy(handle="@ivl609")else it}
        override suspend fun library(access: MusicAccess)=NativeProbe(12,NativePage(emptyList(),0))
        override suspend fun libraryPage(access: MusicAccess,owner: Long,offset: Int): NativePage {
            if(owner==35L)throw MusicFailure(MusicError.DENIED,201)
            if(owner==34L){entered?.complete(Unit);hold?.await();return NativePage(songs.drop(offset).take(100),songs.size)}
            return library(access).library
        }
        override suspend fun catalog(access: MusicAccess,owner: Long): NativeCatalog {
            if(owner==35L)throw MusicFailure(MusicError.DENIED,201)
            return if(owner==34L)NativeCatalog(listOfNotNull(playlist,album,extra),if(extra==null)2 else 3)else NativeCatalog(emptyList(),0)
        }
        override suspend fun albumCatalogPage(access: MusicAccess,owner: Long,offset: Int)=if(owner==34L)NativeCatalog(listOf(album),1)else NativeCatalog(emptyList(),0)
        override suspend fun playlist(access: MusicAccess,list: NativePlaylist)=NativePage(songs,3)
        override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("No audio network request in this fixture")
    }
    private fun fixture(block:(NativeMusicViewModel,Api)->Unit) {
        val app=ui.activity.application;val vault=MusicAccessStore(app);val holder=ViewModelStore();val api=Api()
        lateinit var model: NativeMusicViewModel
        vault.delete()
        try {
            ui.runOnUiThread{model=ViewModelProvider(holder,object: ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")override fun <T: androidx.lifecycle.ViewModel> create(c: Class<T>): T=NativeMusicViewModel(app,api) as T
            })[NativeMusicViewModel::class.java]}
            ui.waitUntil(15000){model.state.value.initialized}
            ui.runOnUiThread{model.configure("a".repeat(64),"CI/1")}
            ui.waitUntil(15000){!model.state.value.busy&&model.state.value.page!=null}
            ui.runOnUiThread{ui.activity.setContent{MusicTheme{NativeMusicPanel(model,true,{})}}}
            block(model,api)
        }finally{ui.runOnUiThread{holder.clear()};vault.delete()}
    }
    private fun shot(name: String) {
        ui.waitForIdle();inst.waitForIdleSync();inst.uiAutomation.waitForIdle(200,5_000)
        val bitmap=inst.uiAutomation.takeScreenshot()
        val file=File(inst.targetContext.getExternalFilesDir(null),"screenshots/$name.png").apply{parentFile!!.mkdirs()}
        file.outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        for(cmd in listOf("mkdir -p /data/local/tmp/vk-music-export-screenshots","cp ${file.absolutePath} /data/local/tmp/vk-music-export-screenshots/$name.png"))
            ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(cmd)).use{it.readBytes()}
    }
    private fun top(){ui.onNodeWithTag("native_music_screen_list").performScrollToIndex(0)}
    @Test fun searchProfilesSelectionAndPlaylistTabsFollowTheConceptAndKeepAlbumsSeparate()=fixture {model,_->
        ui.onNodeWithTag("music_nav_FRIENDS").performClick()
        ui.waitUntil(15000){model.friends.state.value.friendsComplete&&!model.friends.state.value.busy}
        ui.onNodeWithText("Друзей: 8").assertExists();shot("friends-list")
        ui.onNodeWithTag("native_music_screen_list").performScrollToNode(hasTestTag("friend_41"))
        ui.onNodeWithTag("friend_41").performClick()
        ui.waitUntil(15000){!model.friends.state.value.busy&&model.friends.state.value.tracksComplete}
        ui.onNodeWithTag("friend_profile_header").assertIsDisplayed()
        ui.onNodeWithContentDescription("Назад").performClick()
        ui.onNodeWithTag("friends_search").assertIsDisplayed()
        ui.onNodeWithTag("friends_search").performTextInput("алина")
        ui.onNodeWithTag("friend_34").assertIsDisplayed();ui.onNodeWithTag("friend_35").assertDoesNotExist();shot("friends-search")
        ui.onNodeWithTag("friend_34").performClick()
        ui.waitUntil(15000){!model.friends.state.value.busy&&model.friends.state.value.tracksComplete}
        ui.onNodeWithTag("friend_handle").assertTextEquals("@ivl609")
        ui.onAllNodesWithText("vk.com/",substring=true).assertCountEquals(0)
        ui.onAllNodesWithText("VK Music Export").assertCountEquals(0)
        shot("friend-profile")
        ui.onNodeWithTag("native_music_screen_list").performScrollToNode(hasTestTag("friend_select"));ui.onNodeWithTag("friend_select").performClick()
        ui.onNodeWithTag("native_music_screen_list").performScrollToNode(hasTestTag("friend_select_3"));ui.onNodeWithTag("friend_select_3").performClick()
        assertEquals(setOf(3),model.friends.state.value.selection);top();shot("friend-selection")
        ui.onNodeWithContentDescription("Назад").performClick();assertFalse(model.friends.state.value.selecting)
        ui.onNodeWithTag("friend_tab_PLAYLISTS").performClick()
        ui.onNodeWithTag("friend_collection_-55_7").assertIsDisplayed();ui.onNodeWithTag("friend_collection_-66_9").assertDoesNotExist();shot("friend-playlists")
        ui.onNodeWithTag("friend_tab_ALBUMS").performClick()
        ui.onNodeWithTag("friend_collection_-66_9").assertIsDisplayed();ui.onNodeWithTag("friend_collection_-55_7").assertDoesNotExist();shot("friend-albums")
        ui.onNodeWithTag("friend_tab_PLAYLISTS").performClick();ui.onNodeWithTag("friend_collection_-55_7").performClick()
        ui.waitUntil(15000){model.friends.state.value.playlistComplete&&!model.friends.state.value.busy}
        ui.onNodeWithTag("friend_playlist_header").assertExists();shot("friend-playlist")
        ui.onNodeWithTag("open_music_downloads").performClick();ui.onNodeWithTag("downloads_status").assertExists()
        androidx.test.espresso.Espresso.pressBack();ui.onNodeWithTag("friend_playlist_header").assertExists()
    }
    @Test fun selectionPersistsViewerAccountOriginalFriendPositionsAndFullFriendPagination()=fixture {_,api->
        val app=ui.activity.application;val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        var starts=0;val controller=FriendMusicController(app,api,scope,queueStart={starts++})
        try {
            runBlocking(Dispatchers.IO){AudioExportRuntime.initialize(app);AudioExportRuntime.clear(app)}
            api.rows=(34L..134L).map{MusicFriend(it,"Друг","$it")}
            ui.runOnUiThread{controller.loadFriends()}
            ui.waitUntil(15000){controller.state.value.friendsComplete}
            assertEquals(listOf(0,100),api.offsets);assertEquals(101,controller.state.value.friends.size)
            ui.runOnUiThread{controller.open(api.rows.first())}
            ui.waitUntil(15000){controller.state.value.tracksComplete&&!controller.state.value.busy}
            ui.runOnUiThread{controller.musicQuery("Север");controller.download(setOf(3))}
            ui.waitUntil(15000){!controller.state.value.busy}
            assertEquals(1,starts)
            runBlocking(Dispatchers.IO){AudioExportStore(app).use{store->
                assertEquals(12L,store.account())
                val task=store.taskRows().single().first
                assertEquals(3,task.position);assertEquals("90_3",task.track.id)
                assertEquals(12L,task.source!!.account);assertEquals("34",task.source.id);assertEquals("friend_library",task.source.kind)
                assertTrue(task.folders.first()=="Друзья")
            }}
        }finally{scope.cancel();runBlocking(Dispatchers.IO){AudioExportRuntime.clear(app)}}
    }
    @Test fun deniedMusicIsExplicitAndChangedCredentialsDiscardLateProfileResults()=fixture {model,api->
        ui.onNodeWithTag("music_nav_FRIENDS").performClick();ui.waitUntil(15000){model.friends.state.value.friendsComplete&&!model.friends.state.value.busy}
        ui.onNodeWithTag("friend_35").performClick();ui.waitUntil(15000){!model.friends.state.value.busy&&model.friends.state.value.problems.isNotEmpty()}
        ui.onNodeWithTag("native_music_screen_list").performScrollToNode(hasTestTag("friend_music_problem"))
        ui.onNodeWithText("Доступ к музыке запрещён").assertIsDisplayed()
        ui.onNodeWithTag("friend_problem_message").assertTextEquals("ВК запретил доступ к аудиозаписям этого профиля.")
        ui.onNodeWithTag("friend_problem_code").assertTextEquals("Код API ВК: 201")
        ui.onAllNodes(hasText("Открыть в ВК") and hasAnyAncestor(hasTestTag("friend_music_problem"))).assertCountEquals(0)
        for(tag in listOf("friend_problem_message","friend_problem_code")) {
            val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            ui.onNodeWithTag(tag).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult){it(layouts)}
            val layout=layouts.single()
            for(line in 0 until layout.lineCount)assertEquals(layout.size.width/2f,(layout.getLineLeft(line)+layout.getLineRight(line))/2f,1f)
        }
        top()
        val badge=ui.onNodeWithText("Музыка недоступна",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        val open=ui.onNodeWithText("Открыть в ВК",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        assertEquals(badge.center.y,open.center.y,2f)
        shot("friend-denied")
        assertNull(model.friends.state.value.count(FriendMusicTab.TRACKS))
        assertFalse(model.friends.state.value.problems.getValue(FriendMusicTab.TRACKS).description.contains("владелец ограничил"))
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();api.entered=entered;api.hold=release
        try {
            ui.runOnUiThread{model.friends.open(api.rows.first())}
            runBlocking{withTimeout(15000){entered.await()}}
            MusicAccessStore(ui.activity.application).save(MusicAccess("b".repeat(64),"CI/1",accountId=99))
            release.complete(Unit)
            ui.waitUntil(15000){!model.friends.state.value.busy&&model.friends.state.value.listProblem?.code==MusicError.ACCOUNT_CHANGED}
            assertNull(model.friends.state.value.user);assertTrue(model.friends.state.value.friends.isEmpty())
        }finally{release.complete(Unit)}
    }
    @Test fun unclassifiedCollectionsRemainAccessibleWithoutAppearingAsAlbumsOrPlaylists()=fixture {model,api->
        api.extra=NativePlaylist(-88,10,"Без подтверждённого типа",2,null,null,null,null)
        ui.onNodeWithTag("music_nav_FRIENDS").performClick()
        ui.waitUntil(15000){model.friends.state.value.friendsComplete&&!model.friends.state.value.busy}
        ui.onNodeWithTag("friend_34").performClick()
        ui.waitUntil(15000){model.friends.state.value.catalog?.complete==true&&!model.friends.state.value.busy}
        ui.onNodeWithTag("friend_tab_PLAYLISTS").performClick()
        ui.onNodeWithTag("friend_collection_-88_10").assertDoesNotExist()
        ui.onNodeWithTag("friend_tab_ALBUMS").performClick()
        ui.onNodeWithTag("friend_collection_-88_10").assertDoesNotExist()
        ui.onNodeWithTag("friend_tab_COLLECTIONS").performClick()
        ui.onNodeWithTag("friend_collection_-88_10").assertIsDisplayed()
        ui.onNodeWithTag("friend_collection_-55_7").assertDoesNotExist()
        shot("friend-unclassified")
        ui.onNodeWithTag("friend_collection_-88_10").performClick()
        ui.waitUntil(15000){model.friends.state.value.playlistComplete&&!model.friends.state.value.busy}
        ui.onNodeWithTag("friend_playlist_header").assertIsDisplayed()
    }
    @Test fun scopedHttpDenialDoesNotInvalidateTheAccountButExplicitTokenRejectionIsPreserved()=runBlocking {
        val access=MusicAccess("a".repeat(64),"CI/1",accountId=12)
        for(index in 0..3) {
            val body=if(index==3)"""{"error":{"error_code":5,"error_msg":"PRIVATE"}}"""else "PRIVATE HTTP PAGE"
            val client=NetworkClients.api().newBuilder().addInterceptor{chain->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403).message("Synthetic").body(body.toResponseBody()).build()
            }.build()
            val api=VkAudioClient(client,observe={_,_->})
            try {
                when(index){0->api.friendsPage(access,0);1->api.friendProfile(access,34);else->api.libraryPage(access,34,0)}
                fail("A precise scoped failure is required")
            }catch(e: MusicFailure) {
                assertEquals(if(index==3)MusicError.ACCESS else MusicError.DENIED,e.code)
                if(index==3)assertEquals(5,e.remoteCode)else assertEquals(403,e.httpStatus)
                assertFalse(e.message.orEmpty().contains("PRIVATE"))
            }
        }
        val identity=NetworkClients.api().newBuilder().addInterceptor{chain->
            val body=chain.request().body as FormBody
            assertFalse((0 until body.size).any{body.name(it)=="user_ids"})
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
                .body("""{"response":[{"id":12}]}""".toResponseBody()).build()
        }.build()
        assertEquals(12L,VkAudioClient(identity,observe={_,_->}).accountOwner(access))
    }

}
