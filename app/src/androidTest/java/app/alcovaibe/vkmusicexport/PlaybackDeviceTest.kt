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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
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
import java.util.concurrent.atomic.AtomicInteger
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.BandwidthMeter
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
        val trace=CopyOnWriteArrayList<String>()
        PlaybackCommandDiagnostics.observer={event->trace.add(event);if(trace.size>256)trace.removeAt(0)}
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
        }catch(t: Throwable) {
            throw AssertionError("Playback command trace: ${trace.takeLast(100)}",t)
        }finally {
            ui.runOnUiThread{PlaybackRuntime.stop()}
            PlaybackCommandDiagnostics.observer=null
            ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            rows.forEach{app.contentResolver.delete(Uri.parse(it.uri),null,null)}
            root.deleteRecursively()
        }
    }
    private fun start(rows: List<PlaybackEntry>) {
        ui.runOnUiThread{PlaybackRuntime.play(app,rows)}
        ui.waitUntil(20000){PlaybackRuntime.state.value.seekable&&!PlaybackRuntime.state.value.buffering}
        ui.runOnUiThread {
            // A bound service may retain modes from a previous fixture.
            if(PlaybackRuntime.state.value.shuffle)PlaybackRuntime.shuffle()
            repeat(3){if(PlaybackRuntime.state.value.repeat!=Player.REPEAT_MODE_ONE)PlaybackRuntime.repeat()}
            if(!PlaybackRuntime.state.value.playing)PlaybackRuntime.toggle(app)
        }
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
    private fun showPlayer(largeText: Boolean=false) {
        ui.runOnUiThread{ui.activity.setContent {
            val state by PlaybackRuntime.state.collectAsState()
            val expanded by PlaybackRuntime.expanded.collectAsState()
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,if(largeText)2f else density.fontScale)) {
              MusicTheme {
                if(expanded)PlaybackScreen(state,{PlaybackRuntime.expanded.value=false},{},false)
                else MusicLibraryScaffold("Сохранённое",MusicDestination.SAVED,{},ExportSnapshot(),{},{},{},
                    playback=state,onPlayerOpen={PlaybackRuntime.expanded.value=true}) {
                    item {Text("Сохранённая музыка")}
                }
              }
            }
        }}
    }
    @Test fun uncheckedSavedThirdTrackStartsWithoutVkLoginOrEnabledDownloadActions()=fixture {rows->
        val vault=MusicAccessStore(app);val before=vault.read()
        try {
            vault.delete()
            val saved=rows.map{SavedLibraryEntry(it.saved!!)} // No MediaStore list pass has completed.
            val playable=PlaybackLists.saved(saved)
            ui.runOnUiThread{ui.activity.setContent{MusicTheme {
                MusicTrackRow(playable[2].track,3,enabled=false,onDownload={error("Download is disabled")},
                    onPlay={PlaybackRuntime.play(app,playable,2)},tag="unchecked_saved_third")
            }}}
            ui.onNodeWithTag("unchecked_saved_third").performClick()
            ui.waitUntil(20000){PlaybackRuntime.state.value.index==2&&PlaybackRuntime.state.value.seekable&&!PlaybackRuntime.state.value.buffering}
            assertNull(PlaybackRuntime.state.value.error);assertTrue(PlaybackRuntime.state.value.current!!.offline)
            assertEquals(rows[2].saved!!.uri,PlaybackRuntime.state.value.current!!.saved!!.uri);assertNull(vault.read())
        }finally{if(before!=null)vault.save(before)}
    }
    @Test fun lateQueuePreparationCannotReplaceLatestLocalChoice()=fixture {rows->
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        try {
            ui.runOnUiThread{PlaybackRuntime.prepare(app){entered.countDown();check(release.await(20,java.util.concurrent.TimeUnit.SECONDS));rows.take(1)}}
            assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS))
            ui.runOnUiThread{PlaybackRuntime.play(app,rows,2)}
            ui.waitUntil(20000){PlaybackRuntime.state.value.index==2&&PlaybackRuntime.state.value.entries.size==3&&PlaybackRuntime.state.value.seekable}
            release.countDown();Thread.sleep(500)
            assertEquals(2,PlaybackRuntime.state.value.index);assertEquals(3,PlaybackRuntime.state.value.entries.size)
        }finally{release.countDown()}
    }
    @Test fun realOfflinePlaybackContinuesThroughDownloadsAndBack()=fixture {rows->
        start(rows)
        ui.onNodeWithTag("player_collapse").performClick()
        ui.onNodeWithTag("music_nav_SAVED").performClick()
        ui.onNodeWithTag("open_music_downloads").performClick()
        ui.waitUntil(10000){ui.onAllNodesWithTag("downloads_screen").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("music_mini_player").assertIsDisplayed()
        assertTrue(PlaybackRuntime.state.value.playing)
        shot("downloads-with-real-mini-player")
        androidx.test.espresso.Espresso.pressBack()
        ui.onNodeWithTag("music_nav_SAVED").assertIsSelected()
        assertTrue(PlaybackRuntime.state.value.playing)
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
        ui.runOnUiThread{PlaybackRuntime.toggle(app)}
        showPlayer()
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
    @Test fun downwardSingleFingerSwipeCollapsesAndKeepsPlaybackWhileOtherGesturesDoNot()=fixture {rows->
        start(rows)
        ui.runOnUiThread{PlaybackRuntime.toggle(app)}
        showPlayer(largeText=true)
        ui.onNodeWithContentDescription("Меню плеера").assertDoesNotExist()
        val shortDrag=with(ui.density){40.dp.toPx()}
        val longDrag=with(ui.density){120.dp.toPx()}
        ui.onNodeWithTag("player_artwork").performTouchInput {
            swipe(center,center+Offset(0f,shortDrag),600)
        }
        ui.onNodeWithTag("music_player").assertIsDisplayed()
        ui.onNodeWithTag("player_artwork").performTouchInput {
            down(center);moveBy(Offset(0f,longDrag));cancel()
        }
        ui.onNodeWithTag("music_player").assertIsDisplayed()
        ui.onNodeWithTag("player_artwork").performTouchInput {
            down(0,centerLeft+Offset(20f,0f));down(1,centerRight-Offset(20f,0f))
            moveBy(0,Offset(0f,longDrag));moveBy(1,Offset(0f,longDrag))
            up(0);up(1)
        }
        ui.onNodeWithTag("music_player").assertIsDisplayed()
        ui.onNodeWithTag("player_seek").performScrollTo().performTouchInput {
            swipe(Offset(width*.2f,centerY),Offset(width*.75f,centerY),500)
        }
        ui.waitUntil(10000){PlaybackRuntime.state.value.positionMs>500}
        ui.onNodeWithTag("music_player").assertIsDisplayed()
        // Begin inside the scroll viewport, with its position explicitly away from the top.
        ui.onNodeWithTag("player_connection").performScrollTo()
        val axis=ui.onNodeWithTag("player_content").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("Large text must provide scrollable content for this scenario",axis.value()>0f)
        ui.onNodeWithTag("player_content").performTouchInput {
            swipeDown(startY=height*.25f,endY=height*.7f,durationMillis=450)
        }
        ui.onNodeWithTag("music_player").assertIsDisplayed()
        ui.onNodeWithTag("player_header").performScrollTo()
        ui.onNodeWithTag("player_artwork").performTouchInput {swipeUp()}
        ui.onNodeWithTag("music_player").assertIsDisplayed()
        ui.onNodeWithTag("player_header").performScrollTo()
        assertEquals("Dismissal begins at the top of the scroll viewport",0f,axis.value(),0f)
        ui.runOnUiThread{PlaybackRuntime.toggle(app)}
        ui.waitUntil(10000){PlaybackRuntime.state.value.playing}
        val before=PlaybackRuntime.state.value
        ui.onNodeWithTag("player_artwork").performTouchInput {
            swipe(center,center+Offset(0f,longDrag),450)
        }
        ui.waitUntil(5000){!PlaybackRuntime.expanded.value}
        ui.onNodeWithTag("music_player").assertDoesNotExist()
        ui.onNodeWithTag("music_mini_player").assertIsDisplayed()
        assertTrue(PlaybackRuntime.state.value.playing)
        assertEquals(before.index,PlaybackRuntime.state.value.index)
        assertEquals(before.entries,PlaybackRuntime.state.value.entries)
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

    @Test fun artworkMetadataUpdateDoesNotRepublishTheChildTimelineOrResolveAgain()=fixture {rows->
        val thread=HandlerThread("CI source lifecycle").apply{start()}
        val handler=Handler(thread.looper)
        val ready=CompletableDeferred<Unit>();val released=CompletableDeferred<Unit>()
        val calls=AtomicInteger();val timelines=AtomicInteger()
        var registered=false
        val item=MediaItem.Builder().setMediaId("opaque-metadata-ci").setUri("vkmusic://test/metadata").build()
        val source=PlaybackMediaSource(item,PlaybackMediaSource.factory(app,offline=true)) {
            calls.incrementAndGet();Uri.parse(rows.first().saved!!.uri)
        }
        val caller=MediaSource.MediaSourceCaller {_,timeline->
            try {
                assertEquals("vkmusic://test/metadata",timeline.getWindow(0,Timeline.Window()).mediaItem.localConfiguration!!.uri.toString())
                timelines.incrementAndGet();ready.complete(Unit)
            }catch(t: Throwable){ready.completeExceptionally(t)}
        }
        try {
            handler.post {
                try{source.prepareSource(caller,PlayerId.UNSET,BandwidthMeter.NO_OP);registered=true}
                catch(t: Throwable){ready.completeExceptionally(t)}
            }
            runBlocking{withTimeout(10_000){ready.await()}}
            val updated=CompletableDeferred<Unit>()
            val changed=item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setTitle("CI artwork metadata").build()).build()
            ui.runOnUiThread{assertTrue(source.canUpdateMediaItem(changed))}
            handler.post {
                try {
                    val count=timelines.get()
                    source.updateMediaItem(changed)
                    assertEquals(count,timelines.get());assertEquals(1,calls.get());updated.complete(Unit)
                }catch(t: Throwable){updated.completeExceptionally(t)}
            }
            runBlocking{withTimeout(10_000){updated.await()}}
            ui.runOnUiThread{assertEquals("CI artwork metadata",source.getMediaItem().mediaMetadata.title)}
        }finally {
            handler.post{try{if(registered)source.releaseSource(caller);released.complete(Unit)}catch(t: Throwable){released.completeExceptionally(t)}}
            try{runBlocking{withTimeout(10_000){released.await()}}}finally{thread.quitSafely();thread.join(10_000)}
        }
    }

    @Test fun serviceRecreationRetainsPausedQueueModesAndAllowsAnotherSelectedPosition()=fixture {rows->
        start(rows)
        fun player(): ExoPlayer {
            val service=checkNotNull(PlaybackRuntime.service)
            val field=service.javaClass.getDeclaredField("player").apply{isAccessible=true}
            return field.get(service) as ExoPlayer
        }
        fun awaitState(timeout: Long,phase: String,expectedOrder: List<Int>?,condition:()->Boolean) {
            try{ui.waitUntil(timeout){condition()}}
            catch(t: Throwable) {
                val state=PlaybackRuntime.state.value
                var playerDetails="no service"
                ui.runOnUiThread {
                    playerDetails=runCatching {
                        val player=player()
                        "state=${player.playbackState}, playing=${player.isPlaying}, ready=${player.playWhenReady}, position=${player.currentPosition}, suppression=${player.playbackSuppressionReason}, duration=${player.duration}, buffered=${player.bufferedPosition}, loading=${player.isLoading}, repeat=${player.repeatMode}, shuffle=${player.shuffleModeEnabled}, tracks=${player.mediaItemCount}, playerError=${player.playerError?.errorCode}"
                    }.getOrElse{"unavailable: ${it.javaClass.simpleName}"}
                }
                throw AssertionError("Playback phase=$phase, index=${state.index}, playing=${state.playing}, ready=${state.playWhenReady}, buffering=${state.buffering}, position=${state.positionMs}, repeat=${state.repeat}, error=${state.error}; player: $playerDetails; expectedOrder=$expectedOrder, currentOrder=${state.order}",t)
            }
        }
        // Keep the original random user-shuffle path, then cover every permutation.
        val orders=listOf<List<Int>?>(null,listOf(0,1,2),listOf(0,2,1),listOf(1,0,2),listOf(1,2,0),listOf(2,0,1),listOf(2,1,0))
        for(order in orders) {
            ui.runOnUiThread{PlaybackRuntime.playIndex(2)}
            awaitState(20_000,"select third track",order){PlaybackRuntime.state.value.index==2&&PlaybackRuntime.state.value.seekable&&PlaybackRuntime.state.value.playing}
            ui.runOnUiThread {
                PlaybackRuntime.toggle(app)
                if(order==null)PlaybackRuntime.shuffle()
                else {
                    if(PlaybackRuntime.state.value.shuffle)PlaybackRuntime.shuffle()
                    player().setShuffleOrder(androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(order.toIntArray(),0L))
                    PlaybackRuntime.shuffle()
                    assertEquals(order,PlaybackRuntime.state.value.order)
                }
                PlaybackRuntime.seek(321)
            }
            awaitState(10_000,"pause and set order",order){!PlaybackRuntime.state.value.playWhenReady&&PlaybackRuntime.state.value.positionMs==321L&&(order==null||PlaybackRuntime.state.value.order==order)}
            runBlocking(Dispatchers.IO){withTimeout(15_000){PlaybackRuntime.flushHistory()}}
            val saved=PlaybackRuntime.state.value
            assertEquals(Player.REPEAT_MODE_ONE,saved.repeat);assertTrue(saved.shuffle)
            ui.runOnUiThread{app.stopService(android.content.Intent(app,MusicPlaybackService::class.java))}
            ui.waitUntil(10_000){PlaybackRuntime.service==null}
            assertFalse(PlaybackRuntime.state.value.playing);assertEquals(2,PlaybackRuntime.state.value.index)
            ui.runOnUiThread{PlaybackRuntime.toggle(app)}
            awaitState(20_000,"resume third track",saved.order){PlaybackRuntime.state.value.playing&&PlaybackRuntime.state.value.index==2}
            assertEquals(saved.entries,PlaybackRuntime.state.value.entries)
            assertEquals(saved.order,PlaybackRuntime.state.value.order)
            assertTrue(PlaybackRuntime.state.value.shuffle);assertEquals(saved.repeat,PlaybackRuntime.state.value.repeat)
            ui.runOnUiThread{PlaybackRuntime.playIndex(1)}
            awaitState(10_000,"select second track after resume",saved.order){PlaybackRuntime.state.value.index==1&&PlaybackRuntime.state.value.playing}
        }
    }

}
