package app.alcovaibe.vkmusicexport

import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Dedicated release source set: no reflection, debug-only hooks, or production signing key. */
@RunWith(AndroidJUnit4::class)
class R8PlaybackDeviceTest {
    @Test fun minifiedOfflinePlaybackPauseStopResumeAndBackgroundProduceMeasuredEvidence(): Unit=runBlocking {
        val inst=InstrumentationRegistry.getInstrumentation();val app=inst.targetContext
        assertEquals(0,app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        val root=File(app.cacheDir,"r8-playback").apply{mkdirs()}
        var saved: PublishedAudio?=null
        suspend fun until(check:()->Boolean){withTimeout(20_000){while(!check())delay(20)}}
        try {
            val input=File(root,"tone.mp3").apply{inst.context.assets.open("native-audio/tone.mp3").use{writeBytes(it.readBytes())}}
            val task=ExportLayout.task(NativeTrack("12_992001","R8 playback fixture","Synthetic",1),listOf("CI R8 playback"),1)
            saved=withContext(Dispatchers.IO){SharedAudioStore(app).publish(task,TransferResult(input,ProcessedAudio("mp3","audio/mpeg",1200))) {}}
            val entry=PlaybackEntry(saved!!.track,"R8 offline",saved=saved)
            val cpu=android.os.Process.getElapsedCpuTime();val started=SystemClock.elapsedRealtimeNanos()
            inst.runOnMainSync{PlaybackRuntime.stop();PlaybackRuntime.play(app,listOf(entry,entry,entry),1)}
            until{PlaybackRuntime.state.value.playing&&PlaybackRuntime.state.value.seekable}
            val firstPlayMs=(SystemClock.elapsedRealtimeNanos()-started)/1_000_000.0
            inst.runOnMainSync{repeat(3){if(PlaybackRuntime.state.value.repeat!=1)PlaybackRuntime.repeat()};PlaybackRuntime.toggle(app)}
            until{!PlaybackRuntime.state.value.playWhenReady}
            inst.runOnMainSync{PlaybackRuntime.seek(321);PlaybackRuntime.toggle(app)}
            until{PlaybackRuntime.state.value.playing}
            // Short functional background sample; it does not stand in for 1h/8h OEM acceptance.
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            delay(5_000)
            assertTrue(PlaybackRuntime.state.value.playing);assertNull(PlaybackRuntime.state.value.error)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            inst.runOnMainSync{PlaybackRuntime.stop()};PlaybackRuntime.flushHistory()
            assertTrue(PlaybackRuntime.state.value.entries.isEmpty())
            inst.runOnMainSync{PlaybackRuntime.play(app,listOf(entry),0)}
            until{PlaybackRuntime.state.value.playing};assertNull(PlaybackRuntime.state.value.error)
            val memory=Debug.MemoryInfo();Debug.getMemoryInfo(memory)
            val report=JSONObject().apply {
                put("schema",1);put("api",android.os.Build.VERSION.SDK_INT);put("debuggable",false)
                put("first_play_ms",firstPlayMs);put("cpu_ms",android.os.Process.getElapsedCpuTime()-cpu)
                put("pss_kib",memory.totalPss);put("java_heap_bytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
                put("background_sample_ms",5_000);put("pause_seek_resume",true);put("stop_new_play",true)
            }
            android.util.Log.i("VKR8Bench","VK_R8_PLAYBACK="+report)
        }finally {
            inst.runOnMainSync{PlaybackRuntime.stop()};PlaybackRuntime.flushHistory()
            saved?.let{app.contentResolver.delete(Uri.parse(it.uri),null,null)}
            scenario.close();root.deleteRecursively()
        }
    }
}
