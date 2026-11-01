package app.alcovaibe.vkmusicexport

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import androidx.compose.material3.Text
import android.app.NotificationManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val app get()=inst.targetContext
    private fun fixture(block:(List<PlaybackEntry>)->Unit) {
        val rows=mutableListOf<PublishedAudio>()
        val root=File(app.cacheDir,"player-${UUID.randomUUID()}").apply{mkdirs()}
        ui.runOnUiThread{PlaybackRuntime.stop()}
        try {
            val input=File(root,"tone.mp3").apply{inst.context.assets.open("native-audio/tone.mp3").use{writeBytes(it.readBytes())}}
            val covered=File(root,"covered.mp3")
            val image=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888)
            val paint=Paint().apply{color=0xff786859.toInt()}
            val canvas=Canvas(image);canvas.drawColor(0xffe9dfd3.toInt());canvas.drawRect(12f,12f,84f,84f,paint)
            paint.color=0xffb8a493.toInt();canvas.drawCircle(48f,48f,23f,paint)
            val out=ByteArrayOutputStream();image.compress(Bitmap.CompressFormat.JPEG,90,out);image.recycle()
            EmbeddedArtwork.write(input,covered,"mp3",CoverImage(out.toByteArray(),"image/jpeg"))
            val shared=SharedAudioStore(app)
            runBlocking(Dispatchers.IO) {
                repeat(3){i->
                    val track=NativeTrack("12_${99871+i}","Проверка плеера ${i+1}","Тестовая запись",1)
                    val task=ExportLayout.task(track,listOf("CI player",UUID.randomUUID().toString()),i+1)
                    rows.add(shared.publish(task,TransferResult(covered,ProcessedAudio("mp3","audio/mpeg",1200),coverEmbedded=true)) {})
                }
            }
            block(rows.map{PlaybackEntry(it.track,"Сохранённая музыка",saved=it)})
        }finally {
            ui.runOnUiThread{PlaybackRuntime.stop()}
            ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            rows.forEach{app.contentResolver.delete(Uri.parse(it.uri),null,null)}
            root.deleteRecursively()
        }
    }
    private fun start(rows: List<PlaybackEntry>) {
        ui.runOnUiThread{PlaybackRuntime.play(app,rows)}
        ui.waitUntil(20000){PlaybackRuntime.state.value.seekable&&!PlaybackRuntime.state.value.buffering}
        ui.runOnUiThread{PlaybackRuntime.repeat();PlaybackRuntime.repeat();if(!PlaybackRuntime.state.value.playing)PlaybackRuntime.toggle(app)}
        ui.waitUntil(10000){PlaybackRuntime.state.value.playing&&PlaybackRuntime.state.value.artwork!=null&&PlaybackRuntime.state.value.error==null}
    }
    private fun shot(name: String) {
        ui.waitForIdle();inst.waitForIdleSync()
        val image=inst.uiAutomation.takeScreenshot()
        val file=File(app.getExternalFilesDir(null),"screenshots/$name.png").apply{parentFile!!.mkdirs()}
        file.outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};image.recycle()
        for(cmd in listOf("mkdir -p /data/local/tmp/vk-music-export-screenshots","cp ${file.absolutePath} /data/local/tmp/vk-music-export-screenshots/$name.png"))
            ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(cmd)).use{it.readBytes()}
    }
    @Test fun localPlaybackSurvivesStoppedActivityAndPublishesMediaNotification()=fixture {rows->
        start(rows)
        val manager=app.getSystemService(NotificationManager::class.java)
        ui.waitUntil(10000){manager.activeNotifications.any{it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)}}
        ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        Thread.sleep(2000)
        assertTrue("Music must keep playing after onStop",PlaybackRuntime.state.value.playing)
        assertTrue(PlaybackRuntime.state.value.current!!.offline)
        assertTrue(manager.activeNotifications.any{it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE!=0})
        ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertNotNull(PlaybackRuntime.service)
    }
    @Test fun seekPreviousNextShuffleAndRepeatControlTheServicePlayer()=fixture {rows->
        start(rows)
        ui.runOnUiThread{PlaybackRuntime.toggle(app);PlaybackRuntime.seek(500)}
        ui.waitUntil(10000){!PlaybackRuntime.state.value.playing&&PlaybackRuntime.state.value.positionMs in 350L..750L}
        ui.runOnUiThread{PlaybackRuntime.next()}
        ui.waitUntil(10000){PlaybackRuntime.state.value.index==1&&PlaybackRuntime.state.value.playing}
        ui.runOnUiThread{PlaybackRuntime.shuffle()}
        ui.waitUntil(10000){PlaybackRuntime.state.value.shuffle}
        assertEquals(setOf(0,1,2),PlaybackRuntime.state.value.order.toSet())
        ui.runOnUiThread{PlaybackRuntime.repeat()}
        ui.waitUntil(10000){PlaybackRuntime.state.value.repeat==Player.REPEAT_MODE_OFF}
        ui.runOnUiThread{PlaybackRuntime.previous()}
        ui.waitUntil(10000){PlaybackRuntime.state.value.index!=1||PlaybackRuntime.state.value.positionMs<300}
    }
    @Test fun fullScreenAndMiniPlayerShareStateAndLocalModeIsOffline()=fixture {rows->
        start(rows)
        ui.runOnUiThread{PlaybackRuntime.toggle(app);ui.activity.setContent {
            val state by PlaybackRuntime.state.collectAsState()
            val expanded by PlaybackRuntime.expanded.collectAsState()
            MusicTheme {
                if(expanded)PlaybackScreen(state,{PlaybackRuntime.expanded.value=false},{},false)
                else MusicLibraryScaffold("Сохранённое",MusicDestination.SAVED,{},ExportSnapshot(),{},{},{},
                    playback=state,onPlayerOpen={PlaybackRuntime.expanded.value=true}) {
                    item {Text("Сохранённая музыка")}
                }
            }
        }}
        ui.onNodeWithTag("music_player").assertExists()
        ui.onNodeWithText("Офлайн").assertIsDisplayed()
        ui.onNodeWithTag("player_download").assertIsNotEnabled()
        ui.onNodeWithTag("player_seek").assertIsEnabled()
        shot("player-offline")
        ui.onNodeWithTag("player_collapse").performClick()
        ui.onNodeWithTag("music_mini_player").assertIsDisplayed()
        val mini=ui.onNodeWithTag("music_mini_player").fetchSemanticsNode().boundsInRoot
        val navigation=ui.onNodeWithTag("music_nav_SAVED").fetchSemanticsNode().boundsInRoot
        assertTrue("Mini player belongs above the bottom navigation",mini.bottom<=navigation.top)
        shot("player-mini")
        ui.onNodeWithTag("music_mini_player").performClick()
        ui.onNodeWithTag("player_queue").performClick()
        ui.onNodeWithTag("playback_queue_2").performClick()
        ui.waitUntil(10000){PlaybackRuntime.state.value.index==2}
        ui.onNodeWithTag("player_title").assertTextContains("Проверка плеера 3")
    }
    @Test fun onlineHlsUsesTheRealDecoderAndPublicTimelineHidesTheResolvedAddress() {
        val requested=CopyOnWriteArrayList<String>()
        val ready=AtomicBoolean(false);val error=AtomicReference<androidx.media3.common.PlaybackException?>()
        val fixture=inst.context.assets.open("native-audio/tone-aac.ts").use{it.readBytes()}
        val client=PlaybackNetwork.client().newBuilder().addInterceptor {chain->
            val request=chain.request();requested.add(request.url.encodedPath)
            val manifest=request.url.encodedPath.endsWith(".m3u8")
            val bytes=if(manifest)"#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:1.2,\ntone.ts\n#EXT-X-ENDLIST\n".toByteArray()else fixture
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
                .header("Content-Type",if(manifest)"application/vnd.apple.mpegurl" else "video/mp2t").body(bytes.toResponseBody()).build()
        }.build()
        var player: ExoPlayer?=null
        try {
            ui.runOnUiThread {
                val factory=DefaultMediaSourceFactory(app).setDataSourceFactory(PlaybackDataSources.online(client))
                val item=MediaItem.Builder().setMediaId("opaque-test-id").setUri("vkmusic://test/1").build()
                val source=PlaybackMediaSource(item,factory){Uri.parse("https://audio.userapi.com/index.m3u8")}
                assertTrue(requested.isEmpty())
                val playing=ExoPlayer.Builder(app).build();player=playing
                playing.addListener(object: Player.Listener {
                    override fun onPlaybackStateChanged(state: Int){if(state==Player.STATE_READY)ready.set(true)}
                    override fun onPlayerError(problem: androidx.media3.common.PlaybackException){error.set(problem)}
                })
                playing.repeatMode=Player.REPEAT_MODE_ONE
                playing.setMediaSource(source);playing.prepare();playing.play()
            }
            ui.waitUntil(20000){ready.get()||error.get()!=null}
            assertNull(error.get());assertTrue(ready.get())
            assertTrue(requested.contains("/index.m3u8"));assertTrue(requested.contains("/tone.ts"))
            ui.runOnUiThread {
                val playing=checkNotNull(player)
                val item=playing.currentTimeline.getWindow(0,Timeline.Window()).mediaItem
                assertEquals("opaque-test-id",item.mediaId)
                assertEquals("vkmusic://test/1",item.localConfiguration!!.uri.toString())
                assertTrue(playing.isCurrentMediaItemSeekable)
                playing.pause();playing.seekTo(500)
            }
        }finally {ui.runOnUiThread{player?.release()}}
    }

    @Test fun onlineResourcesCannotOpenLocalFilesContentProvidersOrInlineData()=fixture {rows->
        val factory=PlaybackDataSources.online()
        for(address in listOf(rows.first().saved!!.uri,"file:///data/local/tmp/private.mp3","data:audio/mpeg;base64,AAAA","http://audio.userapi.com/song.mp3")) {
            val source=factory.createDataSource()
            try{source.open(DataSpec(Uri.parse(address)));fail("A non-HTTPS media resource was opened")}
            catch(e: java.io.IOException){assertEquals("PLAYBACK_ADDRESS",e.message)}
            finally{source.close()}
        }
    }

}
