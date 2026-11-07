package app.alcovaibe.vkmusicexport

import android.content.ContextWrapper
import android.database.sqlite.SQLiteConstraintException
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SavedLibraryDeviceTest {
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private fun fixture(block:suspend(ContextWrapper,AudioExportStore)->Unit)=runBlocking<Unit>(Dispatchers.IO) {
        val dir=File(inst.targetContext.cacheDir,"saved-index-${UUID.randomUUID()}").apply{mkdirs()}
        val context=object: ContextWrapper(inst.targetContext){override fun getNoBackupFilesDir()=dir}
        try{AudioExportStore(context).use{block(context,it)}}finally{dir.deleteRecursively()}
    }
    private fun task(i: Int,source: String="my")=ExportLayout.task(NativeTrack("12_$i","Track $i","CI",1),listOf("CI saved"),i,ExportSource(12,"library",source))
    private fun row(i: Int): PublishedAudio {val task=task(i);return PublishedAudio(task.key,task.track,"content://media/external_primary/audio/media/${900000+i}","$i.mp3",ExportLayout.ROOT+"CI saved/","mp3","audio/mpeg",1200,100,"a".repeat(64),source=task.source)}
    private suspend fun until(check:()->Boolean){withTimeout(20_000){while(!check())delay(10)}}
    @Test fun allLegacySchemasPreserveQueueFilesAndCheckpointsAcrossMigration()=fixture{context,store->
        store.close()
        for(version in 1..4) {
            val dbFile=File(context.noBackupFilesDir,"audio-export.db")
            android.database.sqlite.SQLiteDatabase.deleteDatabase(dbFile)
            AudioExportStore(context).use{old->
                old.replace(listOf(task(1)),account=12);old.done(task(1),row(1));old.checkpoint("must-preserve","bytes")
                val db=old.writableDatabase;legacySavedFileSchema(db)
                if(version<4)listOf("metadata_runs","metadata_targets","metadata_issues").forEach{db.execSQL("DROP TABLE $it")}
                if(version<3)db.execSQL("DROP TABLE source_failures")
                if(version<2)db.execSQL("DROP TABLE directories")
                db.version=version
            }
            AudioExportStore(context).use{migrated->
                assertEquals(7,migrated.readableDatabase.version);assertEquals(row(1),migrated.existing(task(1).key))
                assertEquals(1,migrated.snapshot().done);assertEquals(12L,migrated.account());assertEquals("bytes",migrated.checkpoint("must-preserve"))
                assertEquals(row(1),migrated.savedPage().single().file);assertTrue(migrated.savedPage().single().order>0)
            }
        }
    }
    @Test fun aliasesRetagRelinkAndRollbackPreservePhysicalDownloadOrder()=fixture{context,store->
        val first=task(1);val alias=first.copy(key="another-source",source=ExportSource(12,"friend_library","34"))
        store.replace(listOf(first,alias),account=12);store.done(first,row(1));store.done(alias,row(1).copy(taskKey=alias.key,source=alias.source))
        store.imported(row(2));val order=store.savedPage().associate{it.uri to it.order};val revision=store.savedRevision()
        store.writableDatabase.execSQL("CREATE TRIGGER abort_alias BEFORE UPDATE OF payload ON files WHEN OLD.task_key='another-source' BEGIN SELECT RAISE(ABORT,'stop alias transaction'); END")
        try{store.retagged(row(1),row(1).copy(coverEmbedded=true));fail("Rollback required")}catch(_: SQLiteConstraintException){}
        assertEquals(revision,store.savedRevision());assertEquals(row(1),store.existing(first.key));assertFalse(store.existing(alias.key)!!.coverEmbedded)
        store.writableDatabase.execSQL("DROP TRIGGER abort_alias")
        store.clearRecentFiles();store.retagged(row(1),row(1).copy(coverEmbedded=true))
        val before=store.existing(first.key)!!;val after=before.copy(uri="content://media/external_primary/audio/media/999999")
        store.relinked(before,after)
        assertEquals(2,store.aliases(after.uri).size);assertTrue(store.aliases(before.uri).isEmpty())
        assertTrue(store.savedChanges(revision).any{it.uri==before.uri&&it.file==null})
        assertEquals(order[before.uri],store.savedPage().first{it.uri==after.uri}.order)
        assertEquals(row(2).uri,store.savedPage().first().uri);assertTrue(store.snapshot().recent.isEmpty())
        AudioExportStore(context).use{assertEquals(store.savedPage(),it.savedPage())}
    }
    @Test fun newPublicationAppearsBeforeBlockedPresencePassAndMissedEventsCatchUp()=fixture{context,store->
        store.imported(row(1));val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val batches=java.util.concurrent.CopyOnWriteArrayList<Int>()
        try {
            val repository=SavedLibrary(context,scope){files->batches.add(files.size);entered.complete(Unit);release.await();files.associate{it.uri to SavedAvailability.READY}}
            withTimeout(10_000){entered.await()};assertEquals(row(1),repository.rows.value.single().file)
            assertTrue(repository.rows.value.single().playable);assertFalse(repository.rows.value.single().present)
            AudioExportStore(context).use{it.imported(row(2))}
            until{repository.rows.value.size==2}
            assertEquals(row(2),repository.rows.value.first().file);assertFalse(release.isCompleted)
            store.forgetFile(row(1));until{repository.rows.value.size==1}
            release.complete(Unit);until{repository.rows.value.single().present}
            assertTrue(batches.all{it in 1..100});assertEquals(row(2),repository.rows.value.single().file)
        }finally{release.complete(Unit);scope.coroutineContext[Job]!!.cancelAndJoin()}
        val reopenedScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try {
            val reopened=SavedLibrary(context,reopenedScope){files->files.associate{it.uri to SavedAvailability.READY}}
            until{reopened.rows.value.size==1};assertEquals(row(2),reopened.rows.value.single().file)
        }finally{reopenedScope.coroutineContext[Job]!!.cancelAndJoin()}
    }
    @Test fun providerBatchHintsKeepOwnershipSizeAndFullHashValidation()=fixture{context,store->
        val shared=SharedAudioStore(context);val published=mutableListOf<PublishedAudio>()
        try {
            val input=File(context.noBackupFilesDir,"tone.mp3").apply{writeBytes(inst.context.assets.open("native-audio/tone.mp3").use{it.readBytes()})}
            repeat(3){i->published.add(shared.publish(task(i+1),TransferResult(input,ProcessedAudio("mp3","audio/mpeg",1200))) {})}
            val hints=shared.presence(published)
            assertEquals(3,hints.size);assertTrue(hints.values.all{it==SavedAvailability.READY})
            assertEquals(SavedAvailability.CHANGED,shared.presence(listOf(published[0].copy(bytes=published[0].bytes+1)))[published[0].uri])
            assertEquals(SavedAvailability.CHANGED,shared.presence(listOf(published[0].copy(uri="https://example.org/file")))["https://example.org/file"])
            assertFalse(shared.valid(published[0].copy(sha256="b".repeat(64))))
            val removed=published.removeAt(1)
            assertEquals(1,context.contentResolver.delete(android.net.Uri.parse(removed.uri),null,null))
            assertEquals(SavedAvailability.MISSING,shared.presence(listOf(removed))[removed.uri])
        }finally{published.forEach{context.contentResolver.delete(android.net.Uri.parse(it.uri),null,null)}}
    }
    @Test fun indexedPagesAndAliasesScaleFor1004500And20000PhysicalFiles()=fixture{context,store->
        val results=mutableListOf<JsonObject>()
        for(size in listOf(100,4500,20000)) {
            val writeStart=SystemClock.elapsedRealtimeNanos()
            val db=store.writableDatabase;db.beginTransaction()
            try{for(i in 1..size)store.imported(row(i));db.setTransactionSuccessful()}finally{db.endTransaction()}
            val writeMs=(SystemClock.elapsedRealtimeNanos()-writeStart)/1_000_000
            val firstStart=SystemClock.elapsedRealtimeNanos();assertEquals(20,store.savedPage(limit=20).size)
            val firstMs=(SystemClock.elapsedRealtimeNanos()-firstStart).toDouble()/1_000_000
            val reopenStart=SystemClock.elapsedRealtimeNanos()
            AudioExportStore(context).use{assertEquals(store.savedPage(limit=20),it.savedPage(limit=20))}
            val reopenMs=(SystemClock.elapsedRealtimeNanos()-reopenStart).toDouble()/1_000_000
            val prefix=NativePage((1..size).map{NativeTrack("12_$it","Track $it","CI",1)},size)
            val queueStart=SystemClock.elapsedRealtimeNanos()
            val queue=PlaybackLists.library(NativeMusicState(probe=NativeProbe(12,prefix),page=prefix,readSessionId="CI"),MusicLibrarySort.DEFAULT)
            val queueMs=(SystemClock.elapsedRealtimeNanos()-queueStart).toDouble()/1_000_000
            assertEquals(size,queue.size);assertEquals(size,queue.last().download!!.position)
            val readStart=SystemClock.elapsedRealtimeNanos();var before=Long.MAX_VALUE;var count=0;var pages=0
            while(true){val page=store.savedPage(before);if(page.isEmpty())break;count+=page.size;pages++;before=page.last().order}
            assertEquals(size,count);assertEquals((size+99)/100,pages)
            val readMs=(SystemClock.elapsedRealtimeNanos()-readStart)/1_000_000
            val aliasStart=SystemClock.elapsedRealtimeNanos();assertEquals(listOf(row(size)),store.aliases(row(size).uri))
            val aliasUs=(SystemClock.elapsedRealtimeNanos()-aliasStart)/1000
            val plan=db.rawQuery("EXPLAIN QUERY PLAN SELECT payload FROM files WHERE uri=?",arrayOf(row(size).uri)).use{c->buildString{while(c.moveToNext())append(c.getString(3))}}
            assertTrue(plan,plan.contains("file_uris"))
            results.add(buildJsonObject {
                put("files",size);put("first20_ms",firstMs);put("reopen_ms",reopenMs);put("queue_build_ms",queueMs);put("write_ms",writeMs);put("paged_read_ms",readMs);put("alias_us",aliasUs);put("pages",pages)
                put("uri_index",plan.contains("file_uris"))
            })
        }
        android.util.Log.i("VKSavedBench","VK_SAVED_BENCHMARK="+buildJsonObject {
            put("schema",1);put("api",android.os.Build.VERSION.SDK_INT);put("results",JsonArray(results))
        })
    }
}
