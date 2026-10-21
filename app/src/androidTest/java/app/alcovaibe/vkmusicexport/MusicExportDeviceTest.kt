package app.alcovaibe.vkmusicexport

import android.content.ContextWrapper
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MusicExportDeviceTest {
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun isolated(name: String): Pair<ContextWrapper,File> {
        val base=File(target.cacheDir,name).apply{deleteRecursively();mkdirs()}
        val context=object: ContextWrapper(target) {
            override fun getNoBackupFilesDir()=File(base,"private").apply{mkdirs()}
            override fun getCacheDir()=File(base,"cache").apply{mkdirs()}
        }
        return context to base
    }
    private fun track(i: Int)=NativeTrack("12_${99000+i}","Synthetic export $i","CI",1)
    private val noApi=object: MusicApi {
        override suspend fun library(access: MusicAccess): NativeProbe=error("unexpected request")
        override suspend fun catalog(access: MusicAccess,owner: Long): NativeCatalog=error("unexpected request")
        override suspend fun playlist(access: MusicAccess,list: NativePlaylist): NativePage=error("unexpected request")
        override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("unexpected request")
    }
    private fun cleanup(rows: List<PublishedAudio>){rows.forEach{runCatching{target.contentResolver.delete(Uri.parse(it.uri),null,null)}}}

    @Test fun publishedMusicRetainsMoreThanFiveFilesAndCrashCheckpointIsAdoptedWithoutRedownload()=runBlocking(Dispatchers.IO) {
        val (context,base)=isolated("public-audio-checkpoints");val store=AudioExportStore(context);val shared=SharedAudioStore(context)
        val tasks=(0 until 12).map{ExportLayout.task(track(it),listOf("Моя музыка","CI checkpoints"),it+1)}
        val rows=mutableListOf<PublishedAudio>()
        try {
            store.replace(tasks)
            val input=File(base,"audio.mp3").apply{InstrumentationRegistry.getInstrumentation().context.assets.open("native-audio/tone.mp3").use{writeBytes(it.readBytes())}}
            val result=TransferResult(input,ProcessedAudio("mp3","audio/mpeg",1200))
            tasks.forEachIndexed{i,task->
                val row=shared.publish(task,result){store.prepared(task,it)};rows.add(row)
                assertTrue(row.relativePath.startsWith("Music/VK Music Export/"));assertTrue(shared.valid(row))
                if(i!=tasks.lastIndex)store.done(task,row) // Simulate process death after IS_PENDING=0, before done.
            }
            assertEquals(11,store.snapshot().done);store.close()
            val reopened=AudioExportStore(context)
            try {
                val engine=AudioExportEngine(reopened,shared,File(base,"temporary"),noApi,credential={error("must reuse checkpoint")},observe={_,_->})
                engine.recover();assertEquals(12,reopened.snapshot().done);assertFalse(reopened.snapshot().unfinished)
                rows.forEach{assertTrue(shared.valid(it))};assertEquals(5,reopened.snapshot().recent.size)
                // A new queue reuses every existing file. The UI's last-five list is not retention.
                reopened.replace(tasks);reopened.status("QUEUED");engine.run{}
                assertEquals("FINISHED",reopened.snapshot().status);assertEquals(12,reopened.snapshot().done)
                assertEquals(rows.map{it.uri},tasks.map{reopened.existing(it.key)!!.uri})
                assertTrue(reopened.prepared().isEmpty())
                reopened.clearQueue();rows.forEach{assertTrue(shared.valid(it))}
            }finally{reopened.close()}
        }finally{store.close();cleanup(rows);base.deleteRecursively()}
    }
    @Test fun cancellationBeforePublicationDeletesPendingRowAndKeepsItemResumable()=runBlocking(Dispatchers.IO) {
        val (context,base)=isolated("public-audio-cancel");val store=AudioExportStore(context);val shared=SharedAudioStore(context)
        val task=ExportLayout.task(track(20),listOf("Моя музыка","CI cancel"),1);var prepared: PublishedAudio?=null
        try {
            store.replace(listOf(task));store.status("QUEUED");assertTrue(store.beginRun());assertEquals(task,store.claim())
            val input=File(base,"audio.mp3").apply{writeBytes(ByteArray(8192))}
            val job=launch {
                shared.publish(task,TransferResult(input,ProcessedAudio("mp3","audio/mpeg",1000))){row->
                    prepared=row;store.prepared(task,row);cancel()
                }
            }
            job.join();assertTrue(job.isCancelled);assertNotNull(prepared)
            assertFalse(shared.valid(prepared!!))
            assertEquals(0,context.contentResolver.query(Uri.parse(prepared!!.uri),arrayOf("_id"),null,null,null)!!.use{it.count})
            store.close();val reopened=AudioExportStore(context)
            try{AudioExportEngine(reopened,shared,File(base,"tmp"),noApi,credential={error("no access")},observe={_,_->}).recover()
                assertEquals(1,reopened.snapshot().pending);assertEquals("PAUSED",reopened.snapshot().status);assertTrue(reopened.prepared().isEmpty())
            }finally{reopened.close()}
        }finally{store.close();prepared?.let{cleanup(listOf(it))};base.deleteRecursively()}
    }
    @Test fun actualMp3TransferUsesMedia3ThenPublishesAndCopiesAcrossAlbumFoldersWithoutSecondNetworkRequest()=runBlocking(Dispatchers.IO) {
        val (context,base)=isolated("public-audio-engine");val store=AudioExportStore(context);val shared=SharedAudioStore(context)
        val bytes=InstrumentationRegistry.getInstrumentation().context.assets.open("native-audio/tone.mp3").use{it.readBytes()}
        val t=track(30).copy(album=MusicAlbum("album:991","CI album"));val tasks=ExportLayout.library(NativePage(listOf(t),1))
        var requests=0;var resolutions=0;val rows=mutableListOf<PublishedAudio>();val events=mutableListOf<String>()
        val client=NetworkClients.media().newBuilder().addInterceptor{chain->requests++
            assertNull(chain.request().header("Authorization"));assertNull(chain.request().header("Cookie"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic").body(bytes.toResponseBody()).build()
        }.build()
        val api=object: MusicApi by noApi {override suspend fun media(access: MusicAccess,track: NativeTrack): String{resolutions++;return "https://a.userapi.com/audio?signature=PRIVATE"}}
        try {
            val engine=AudioExportEngine(store,shared,File(base,"tmp"),api,AudioTransfer(client){type,fields->events.add(type+fields)},
                credential={MusicAccess("SYNTHETIC_SECRET".repeat(8),"Client/1")},observe={type,fields->events.add(type+fields)})
            engine.recover();store.replace(tasks);store.status("QUEUED");engine.run{}
            assertEquals("FINISHED",store.snapshot().status);assertEquals(2,store.snapshot().done)
            assertEquals(1,requests);assertEquals(1,resolutions)
            tasks.forEach{rows.add(store.existing(it.key)!!)}
            assertEquals(rows[0].sha256,rows[1].sha256);rows.forEach{assertTrue(shared.valid(it))}
            assertTrue(rows.any{it.relativePath.contains("/Альбомы/")});assertFalse(events.toString().contains("PRIVATE"));assertFalse(events.toString().contains("SYNTHETIC_SECRET"))
            assertEquals(0,File(base,"tmp").listFiles()!!.size)
        }finally{tasks.mapNotNull{store.existing(it.key)}.forEach{if(it !in rows)rows.add(it)};store.close();cleanup(rows);base.deleteRecursively()}
    }
    @Test fun systemUidtJobRunsFromVisibleActivityAndPersistsSkippedItemsWithoutCredentials()=runBlocking {
        val context=target
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            scenario.onActivity{} // Activity must be visible when scheduling a user-initiated job.
            withContext(Dispatchers.IO) {
                AudioExportRuntime.initialize(context)
                AudioExportRuntime.clear(context)
                AudioExportRuntime.replace(context,listOf(ExportLayout.task(track(40).copy(restricted=true),listOf("Моя музыка"),1)))
                AudioExportRuntime.start(context)
            }
            withTimeout(20000){while(AudioExportRuntime.state.value.status !in setOf("FINISHED_WITH_ERRORS","FINISHED"))delay(100)}
            assertEquals(1,AudioExportRuntime.state.value.failed);assertEquals(0,AudioExportRuntime.state.value.pending)
            withContext(Dispatchers.IO){while(AudioExportRuntime.active.get())delay(50);AudioExportRuntime.clear(context)}
        }
    }
}
