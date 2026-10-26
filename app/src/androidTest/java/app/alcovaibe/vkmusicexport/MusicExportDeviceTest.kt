package app.alcovaibe.vkmusicexport

import android.content.ContextWrapper
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
            var reopened=AudioExportStore(context)
            try {
                var engine=AudioExportEngine(reopened,shared,File(base,"temporary"),noApi,credential={error("must reuse checkpoint")},observe={_,_->})
                engine.recover();assertEquals(12,reopened.snapshot().done);assertFalse(reopened.snapshot().unfinished)
                rows.forEach{assertTrue(shared.valid(it))};assertEquals(5,reopened.snapshot().recent.size)
                val beforeClear=reopened.snapshot()
                reopened.clearRecentFiles()
                assertEquals(beforeClear.copy(recent=emptyList()),reopened.snapshot())
                rows.forEach{assertTrue(shared.valid(it))}
                assertEquals(rows.map{it.uri},tasks.map{reopened.existing(it.key)!!.uri})
                assertEquals(listOf(rows.first()),reopened.copies(tasks.first().track))
                reopened.close();reopened=AudioExportStore(context)
                assertTrue(reopened.snapshot().recent.isEmpty())
                engine=AudioExportEngine(reopened,shared,File(base,"temporary"),noApi,credential={error("must reuse checkpoint")},observe={_,_->})
                // A new queue reuses every existing file. The UI's last-five list is not retention.
                reopened.replace(tasks);reopened.status("QUEUED")
                val queued=reopened.snapshot();reopened.clearRecentFiles()
                assertEquals(queued,reopened.snapshot()) // Clearing history cannot pause/replace a queue.
                engine.run{}
                assertEquals("FINISHED",reopened.snapshot().status);assertEquals(12,reopened.snapshot().done)
                assertEquals(rows.map{it.uri},tasks.map{reopened.existing(it.key)!!.uri})
                assertTrue(reopened.prepared().isEmpty())
                assertTrue(reopened.snapshot().recent.isEmpty()) // Existing files are reused, not new saves.
                val next=ExportLayout.task(track(99),listOf("Моя музыка","CI checkpoints"),1)
                reopened.replace(listOf(next))
                val nextRow=shared.publish(next,result){reopened.prepared(next,it)};rows.add(nextRow)
                reopened.done(next,nextRow)
                assertEquals(listOf(nextRow),reopened.snapshot().recent)
                reopened.close();reopened=AudioExportStore(context)
                assertEquals(listOf(nextRow),reopened.snapshot().recent)
                assertEquals(rows.first(),reopened.existing(tasks.first().key))
                reopened.clearQueue();rows.forEach{assertTrue(shared.valid(it))}
            }finally{reopened.close()}
        }finally{store.close();cleanup(rows);base.deleteRecursively()}
    }
    @Test fun cancellationBeforePublicationDeletesPendingRowAndKeepsItemResumable()=runBlocking(Dispatchers.IO) {
        val (context,base)=isolated("public-audio-cancel");val store=AudioExportStore(context);val shared=SharedAudioStore(context)
        val task=ExportLayout.task(track(20),listOf("Моя музыка","CI cancel"),1);var prepared: PublishedAudio?=null
        try {
            store.replace(listOf(task));store.status("QUEUED");assertTrue(store.beginRun());assertEquals(task,store.claim());assertEquals(task,store.snapshot().currentTask)
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
                assertEquals(1,reopened.snapshot().pending);assertEquals("PAUSED",reopened.snapshot().status);assertNull(reopened.snapshot().currentTask);assertTrue(reopened.prepared().isEmpty())
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
    @Test fun cleanNamesKeepDuplicateTracksAndEqualPlaylistTitlesSeparateAcrossRestart()=runBlocking(Dispatchers.IO) {
        val (context,base)=isolated("clean-audio-names");var store=AudioExportStore(context);val shared=SharedAudioStore(context)
        val t=track(50).copy(title="Песня [2026]",artist="Исполнитель")
        val folderTitle="CI Солнечный день"
        fun tasks(id: Long,title: String=folderTitle)=ExportLayout.playlist(NativePage(listOf(t,t),2),NativePlaylist(12,id,title,2,null,null,null,null))
        val first=tasks(535);val second=tasks(536);val caseVariant=tasks(537,folderTitle.uppercase())
        val all=first+second+caseVariant
        val rows=mutableListOf<PublishedAudio>()
        try {
            store.replace(all)
            val input=File(base,"audio.mp3").apply{InstrumentationRegistry.getInstrumentation().context.assets.open("native-audio/tone.mp3").use{writeBytes(it.readBytes())}}
            val result=TransferResult(input,ProcessedAudio("mp3","audio/mpeg",1200))
            all.forEach{task->val row=shared.publish(task,result,store.directory(task)){store.prepared(task,it)};rows.add(row);store.done(task,row)}
            assertEquals("Music/VK Music Export/Плейлисты/$folderTitle/",rows[0].relativePath)
            assertEquals("Music/VK Music Export/Плейлисты/$folderTitle (2)/",rows[2].relativePath)
            assertEquals("Music/VK Music Export/Плейлисты/${folderTitle.uppercase()} (3)/",rows[4].relativePath)
            for(i in listOf(0,2,4)) {
                assertEquals("Исполнитель — Песня [2026].mp3",rows[i].displayName)
                assertEquals("Исполнитель — Песня [2026] (2).mp3",rows[i+1].displayName)
            }
            assertEquals(6,rows.map{it.uri}.toSet().size);rows.forEach{assertTrue(shared.valid(it))}
            val paths=all.map(store::directory);store.close();store=AudioExportStore(context)
            assertEquals(paths,all.map(store::directory))
            store.replace(all);store.status("QUEUED")
            AudioExportEngine(store,shared,File(base,"tmp"),noApi,credential={error("already saved")},observe={_,_->}).run{}
            assertEquals(6,store.snapshot().done);assertEquals(rows.map{it.uri},all.map{store.existing(it.key)!!.uri})
        }finally{store.close();cleanup(rows);base.deleteRecursively()}
    }
    @Test fun versionOneQueueAndOldPublishedNamesSurviveMigrationAndResumeWithoutRedownload()=runBlocking(Dispatchers.IO) {
        val (context,base)=isolated("audio-names-v1-upgrade");val shared=SharedAudioStore(context)
        val t=track(60);val tasks=ExportLayout.playlist(NativePage(listOf(t,t),2),NativePlaylist(12,535,"CI Existing playlist",2,null,null,null,null))
        val rows=mutableListOf<PublishedAudio>();var store: AudioExportStore?=null
        try {
            val input=File(base,"audio.mp3").apply{InstrumentationRegistry.getInstrumentation().context.assets.open("native-audio/tone.mp3").use{writeBytes(it.readBytes())}}
            val published=shared.publish(tasks.first(),TransferResult(input,ProcessedAudio("mp3","audio/mpeg",1200))){}
            rows.add(published)
            val old=published.copy(displayName="00001 - CI — Synthetic export 60 [12_99060].mp3",
                relativePath=ExportLayout.ROOT+tasks.first().folders.joinToString("/")+"/")
            assertEquals(1,context.contentResolver.update(Uri.parse(old.uri),ContentValues().apply{
                put(MediaStore.MediaColumns.DISPLAY_NAME,old.displayName);put(MediaStore.MediaColumns.RELATIVE_PATH,old.relativePath)
            },null,null))
            val json=Json{encodeDefaults=true}
            SQLiteDatabase.openOrCreateDatabase(File(context.noBackupFilesDir,"audio-export.db"),null).use{db->
                db.execSQL("CREATE TABLE tasks(ordinal INTEGER PRIMARY KEY AUTOINCREMENT,task_key TEXT UNIQUE NOT NULL,payload TEXT NOT NULL,state TEXT NOT NULL,error TEXT,prepared TEXT)")
                db.execSQL("CREATE TABLE files(task_key TEXT PRIMARY KEY,track_id TEXT NOT NULL,payload TEXT NOT NULL)")
                db.execSQL("CREATE TABLE meta(name TEXT PRIMARY KEY,value TEXT NOT NULL)")
                tasks.forEachIndexed{i,task->db.execSQL("INSERT INTO tasks(task_key,payload,state) VALUES(?,?,?)",arrayOf(task.key,json.encodeToString(task),if(i==0)"DONE" else "PENDING"))}
                db.execSQL("INSERT INTO files VALUES(?,?,?)",arrayOf(old.taskKey,t.id,json.encodeToString(old)))
                db.execSQL("INSERT INTO meta VALUES('status','PAUSED')");db.version=1
            }
            val reopened=AudioExportStore(context);store=reopened
            assertEquals(2,reopened.readableDatabase.version);assertEquals(1,reopened.snapshot().done);assertEquals(1,reopened.snapshot().pending)
            assertEquals(old,reopened.existing(tasks.first().key));assertEquals("PAUSED",reopened.snapshot().status)
            assertEquals(listOf(old),reopened.snapshot().recent) // Old schema has no separate history yet.
            val engine=AudioExportEngine(reopened,shared,File(base,"tmp"),noApi,credential={error("local copy")},observe={_,_->})
            engine.recover();reopened.status("QUEUED");engine.run{}
            val copy=reopened.existing(tasks.last().key)!!;rows.add(copy)
            assertEquals("FINISHED",reopened.snapshot().status);assertEquals(2,reopened.snapshot().done)
            assertEquals("Music/VK Music Export/Плейлисты/CI Existing playlist/",copy.relativePath)
            assertEquals("CI — Synthetic export 60.mp3",copy.displayName);assertEquals(old.sha256,copy.sha256)
            assertEquals(old,reopened.existing(tasks.first().key));assertTrue(shared.valid(old));assertTrue(shared.valid(copy))
            context.contentResolver.query(Uri.parse(old.uri),arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),null,null,null)!!.use{
                assertTrue(it.moveToFirst());assertEquals(old.displayName,it.getString(0))
            }
        }finally{store?.close();cleanup(rows);base.deleteRecursively()}
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
