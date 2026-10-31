package app.alcovaibe.vkmusicexport

import android.app.Application
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.music.*
import org.junit.Assert.*
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MusicLibraryRedesignDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun screenshot(name: String) {
        ui.waitForIdle()
        saveScreenshot(name,InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
    }
    private fun saveScreenshot(name: String,bitmap: Bitmap) {
        val i=InstrumentationRegistry.getInstrumentation()
        val file=File(i.targetContext.getExternalFilesDir(null),"screenshots/$name.png").apply{parentFile!!.mkdirs()}
        file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        for(cmd in listOf("mkdir -p /data/local/tmp/vk-music-export-screenshots","cp ${file.absolutePath} /data/local/tmp/vk-music-export-screenshots/$name.png"))
            ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand(cmd)).use{it.readBytes()}
    }
    private fun fixture(block:(NativeMusicViewModel)->Unit) {
        val context=ui.activity.application;val vmStore=ViewModelStore();val vault=MusicAccessStore(context)
        val tracks=listOf(NativeTrack("12_1","Миражи","Егор Крид",161,subtitle="hehehe Cover sped up"),
            NativeTrack("12_2","Белый шум","Север",238,subtitle="Live at Roof"),NativeTrack("12_3","Ночной рейс","MAREVO",202,subtitle="Remix"))
        val album=NativePlaylist(12,4,"I AM",3,null,null,null,null,PlaylistKind.ALBUM)
        val api=object: MusicApi {
            override suspend fun library(access: MusicAccess)=NativeProbe(12,NativePage(tracks,3))
            override suspend fun catalog(access: MusicAccess,owner: Long)=NativeCatalog(listOf(album),1)
            override suspend fun playlist(access: MusicAccess,list: NativePlaylist)=NativePage(tracks,3)
            override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("No audio request")
            override suspend fun followedArtists(access: MusicAccess,owner: Long)=listOf(MusicArtist("creed","Егор Крид"),MusicArtist("north","Север"))
            override suspend fun artistDetails(access: MusicAccess,artist: MusicArtist)=MusicArtistDetails(artist,NativePage(tracks,3),listOf(album))
        }
        lateinit var model: NativeMusicViewModel
        try {
            vault.delete()
            ui.runOnUiThread {
                model=ViewModelProvider(vmStore,object: ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T: androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T=NativeMusicViewModel(context,api) as T
                })[NativeMusicViewModel::class.java]
            }
            ui.waitUntil(10_000){model.state.value.initialized}
            ui.runOnUiThread{model.configure("vk1.a."+"SYNTHETIC".repeat(12),"CI/1")}
            ui.waitUntil(10_000){!model.state.value.busy&&model.state.value.page!=null}
            ui.runOnUiThread{ui.activity.setContent{MusicTheme{NativeMusicPanel(model,true,{})}}}
            block(model)
        }finally{ui.runOnUiThread{vmStore.clear()};vault.delete()}
    }
    @Test fun warmLibraryKeepsToolsAndShowsVersionSeparatelyWithoutArtistLinks()=fixture {
        ui.onNodeWithTag("vk_connection_logo").assertIsDisplayed()
        ui.onNodeWithText("VK").assertDoesNotExist()
        ui.onNodeWithText("Друзья").assertIsDisplayed()
        run {
            val icon=ui.activity.applicationInfo.loadIcon(ui.activity.packageManager)
            assertTrue(icon is android.graphics.drawable.AdaptiveIconDrawable)
            val bitmap=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888)
            icon.setBounds(0,0,512,512);icon.draw(android.graphics.Canvas(bitmap))
            saveScreenshot("launcher-icon",bitmap)
        }
        ui.onNodeWithTag("native_music_screen_list").performScrollToNode(hasTestTag("music_track_1_12_1"))
        ui.onNodeWithText("Миражи").assertIsDisplayed();ui.onNodeWithText("hehehe Cover sped up").assertIsDisplayed()
        ui.onNodeWithText("Егор Крид").assertHasNoClickAction()
        ui.onNodeWithTag("native_music_screen_list").performScrollToIndex(0)
        screenshot("redesign-library")
        ui.onNodeWithTag("native_music_screen_list").performScrollToNode(hasTestTag("library_tools"))
        ui.onNodeWithTag("library_tools").performClick()
        ui.onNodeWithTag("native_music_screen_list").performScrollToNode(hasTestTag("clean_duplicates"))
        ui.onNodeWithTag("clean_duplicates").assertIsDisplayed()
        ui.onNodeWithTag("native_music_screen_list").performScrollToNode(hasTestTag("check_unavailable"))
        ui.onNodeWithTag("check_unavailable").assertIsDisplayed()
        ui.onNodeWithTag("music_nav_SAVED").performClick()
        for(tag in listOf("saved_artwork","saved_track_metadata","saved_file_recovery"))ui.onNodeWithTag(tag).assertIsDisplayed()
        screenshot("redesign-saved")
        ui.onNodeWithTag("music_nav_DOWNLOADS").performClick()
        ui.onNodeWithTag("downloads_status").assertIsDisplayed();screenshot("redesign-downloads")
    }
    @Test fun sortingChangesOnlyPresentationAndDownloadKeepsTheOriginalVkPosition()=fixture {model->
        val context=ui.activity.application;val vault=MusicAccessStore(context)
        runBlocking(Dispatchers.IO) {
            AudioExportRuntime.initialize(context);AudioExportRuntime.clear(context)
            val old=vault.read()!!;assertTrue(vault.replace(old,old.withSession(rejected=true)))
        }
        try {
            ui.onNodeWithTag("native_music_screen_list").performScrollToNode(hasText("Порядок ВК ↕"))
            ui.onNodeWithText("Порядок ВК ↕").performClick()
            ui.onNodeWithTag("native_music_screen_list").performScrollToNode(hasTestTag("track_menu_2_12_2"))
            ui.onNodeWithTag("track_menu_2_12_2").performClick()
            ui.onNodeWithText("Скачать этот трек").performClick()
            ui.waitUntil(10_000){model.downloadsOpen.value&&!model.state.value.busy}
            runBlocking(Dispatchers.IO) {
                val task=AudioExportStore(context).use{it.taskRows().single().first}
                assertEquals("12_2",task.track.id);assertEquals(2,task.position)
            }
        }finally {
            runBlocking(Dispatchers.IO) {
                AudioExportRuntime.pause(context)
                withTimeout(15_000){while(AudioExportRuntime.active.get())delay(25)}
                AudioExportRuntime.clear(context)
            }
        }
    }
    @Test fun followedArtistsOpenOnlyFromTheirCategoryAndAlbumsReturnToTheArtist()=fixture {model->
        ui.onNodeWithTag("library_category_ARTISTS").performClick()
        ui.waitUntil(10_000){model.artists.value.loaded&&!model.state.value.busy}
        ui.onNodeWithTag("followed_artist_creed").assertIsDisplayed()
        ui.onNodeWithText("MAREVO").assertDoesNotExist()
        screenshot("redesign-artists")
        ui.onNodeWithTag("followed_artist_creed").performClick()
        ui.waitUntil(10_000){model.artists.value.details!=null&&!model.state.value.busy}
        ui.onNodeWithTag("native_music_screen_list").performScrollToNode(hasTestTag("artist_album_12_4"))
        screenshot("redesign-artist-details")
        ui.onNodeWithTag("artist_album_12_4").performClick()
        ui.waitUntil(10_000){model.artists.value.albumTracks!=null&&!model.state.value.busy}
        ui.onNodeWithTag("native_music_screen_list").performScrollToNode(hasTestTag("music_track_3_12_3"))
        ui.onNodeWithText("Ночной рейс").assertIsDisplayed()
        ui.onNodeWithContentDescription("Назад").performClick()
        ui.runOnIdle{assertNull(model.artists.value.album);assertNotNull(model.artists.value.selected)}
        ui.onNodeWithContentDescription("Назад").performClick()
        ui.runOnIdle{assertNull(model.artists.value.selected)}
    }
}
