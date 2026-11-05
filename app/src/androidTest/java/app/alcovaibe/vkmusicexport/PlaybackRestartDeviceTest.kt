package app.alcovaibe.vkmusicexport

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.os.Process
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Executed by playback-resumption-check.sh in four independent instrumentation processes. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackRestartDeviceTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val app get()=inst.targetContext
    private val proof get()=File(app.filesDir,"playback-restart-proof.json")
    private fun main(block:()->Unit)=inst.runOnMainSync{block()}
    private suspend fun until(check:()->Boolean){withTimeout(20_000){while(!check())delay(20)}}
    private suspend fun flush(){withTimeout(20_000){PlaybackRuntime.flushHistory()}}
    private suspend fun foreground() {
        main{app.startActivity(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
        until {
            var resumed=false
            main{resumed=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any{it is MainActivity}}
            resumed
        }
    }
    private fun record()=PlaybackHistoryStore(app).use{checkNotNull(it.read())}
    private fun saveProof(fields: Map<String,JsonElement>) {
        val previous=if(proof.exists())Json.parseToJsonElement(proof.readText()).jsonObject else emptyMap()
        proof.writeText(JsonObject(previous+fields).toString())
    }
    private fun expected()=Json.parseToJsonElement(proof.readText()).jsonObject
    private fun assertMetadata(record: PlaybackCheckpoint,expected: JsonObject) {
        val state=PlaybackRuntime.state.value
        assertEquals(expected.getValue("queue_id").jsonPrimitive.content,record.id)
        assertEquals(4,state.entries.size);assertEquals(2,state.index)
        assertEquals(state.entries[0].saved!!.uri,state.entries[2].saved!!.uri)
        assertEquals(expected.getValue("order").jsonArray.map{it.jsonPrimitive.int},state.order)
        assertTrue(state.shuffle);assertEquals(1,state.repeat)
        assertFalse(state.playing);assertFalse(state.playWhenReady)
        assertEquals(record.cursor.positionMs,state.positionMs)
    }
    @Test fun playbackHistorySurvivesActualPausedAndPlayingProcessDeath()=runBlocking<Unit>(Dispatchers.IO) {
        check(BuildConfig.DEBUG)
        val phase=InstrumentationRegistry.getArguments().getString("playbackPhase")
        require(phase in setOf("seed","resume","active","cleared"))
        until{PlaybackRuntime.restorationComplete.value}
        if(phase=="seed") {
            proof.delete()
            main{PlaybackRuntime.stop()};flush()
            val root=File(app.cacheDir,"resume-${UUID.randomUUID()}").apply{mkdirs()}
            val input=File(root,"tone.mp3").apply{inst.context.assets.open("native-audio/tone.mp3").use{writeBytes(it.readBytes())}}
            val rows=(1..3).map{i->
                val task=ExportLayout.task(NativeTrack("12_${997000+i}","Restart $i","CI playback",1),listOf("CI restart",root.name),i)
                SharedAudioStore(app).publish(task,TransferResult(input,ProcessedAudio("mp3","audio/mpeg",1200)),prepared={})
            }
            val entries=listOf(rows[0],rows[1],rows[0],rows[2]).mapIndexed{i,file->PlaybackEntry(file.track,"Position $i",saved=file)}
            foreground();main{PlaybackRuntime.play(app,entries,2)}
            until{PlaybackRuntime.state.value.playing&&PlaybackRuntime.state.value.seekable}
            main{PlaybackRuntime.toggle(app);PlaybackRuntime.shuffle();PlaybackRuntime.repeat();PlaybackRuntime.repeat();PlaybackRuntime.seek(321)}
            until{!PlaybackRuntime.state.value.playWhenReady&&PlaybackRuntime.state.value.positionMs==321L}
            flush();val saved=record()
            assertEquals(321L,saved.cursor.positionMs);assertEquals(2,saved.cursor.index)
            saveProof(mapOf("api" to JsonPrimitive(android.os.Build.VERSION.SDK_INT),"seed_pid" to JsonPrimitive(Process.myPid()),
                "queue_id" to JsonPrimitive(saved.id),"order" to JsonArray(saved.order.map(::JsonPrimitive)),"paused_position" to JsonPrimitive(321),
                "files" to JsonArray(rows.map{JsonPrimitive(it.taskKey)})))
            root.deleteRecursively()
        }else if(phase=="resume") {
            val expected=expected();assertNotEquals(expected.getValue("seed_pid").jsonPrimitive.int,Process.myPid())
            val saved=record();assertMetadata(saved,expected);assertEquals(321L,PlaybackRuntime.state.value.positionMs)
            foreground()
            val controller=MediaController.Builder(app,SessionToken(app,ComponentName(app,MusicPlaybackService::class.java)))
                .setApplicationLooper(Looper.getMainLooper()).buildAsync().get(20,TimeUnit.SECONDS)
            try {
                main{controller.play()}
                until{PlaybackRuntime.state.value.playing&&PlaybackRuntime.state.value.error==null}
                val state=PlaybackRuntime.state.value;assertEquals(2,state.index);assertEquals(saved.order,state.order);assertTrue(state.shuffle);assertEquals(1,state.repeat)
                main {
                    assertEquals(4,controller.mediaItemCount)
                    assertEquals("vkmusic",controller.currentMediaItem!!.localConfiguration!!.uri.scheme)
                }
                flush();saveProof(mapOf("resume_pid" to JsonPrimitive(Process.myPid()),"media_session_resumption" to JsonPrimitive(true)))
            }finally{main{controller.release()}}
        }else if(phase=="active") {
            val expected=expected();assertNotEquals(expected.getValue("resume_pid").jsonPrimitive.int,Process.myPid())
            assertMetadata(record(),expected)
            main{PlaybackRuntime.stop()};flush();assertNull(PlaybackHistoryStore(app).use{it.read()})
            val keys=expected.getValue("files").jsonArray.map{it.jsonPrimitive.content}
            AudioExportStore(app).use{store->keys.forEach{key->store.existing(key)?.let{file->
                app.contentResolver.delete(Uri.parse(file.uri),null,null);store.forgetFile(file)
            }}}
            saveProof(mapOf("active_pid" to JsonPrimitive(Process.myPid()),"paused_restore" to JsonPrimitive(true),"active_restore" to JsonPrimitive(true),"explicit_stop" to JsonPrimitive(true)))
        }else {
            val expected=expected();assertNotEquals(expected.getValue("active_pid").jsonPrimitive.int,Process.myPid())
            assertTrue(PlaybackRuntime.state.value.entries.isEmpty());assertFalse(PlaybackRuntime.state.value.playWhenReady)
            assertNull(PlaybackHistoryStore(app).use{it.read()})
            saveProof(mapOf("cleared_pid" to JsonPrimitive(Process.myPid()),"cleared_after_restart" to JsonPrimitive(true)))
        }
    }
}
