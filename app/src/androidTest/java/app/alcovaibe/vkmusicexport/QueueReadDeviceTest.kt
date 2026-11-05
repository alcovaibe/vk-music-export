package app.alcovaibe.vkmusicexport

import android.content.ContextWrapper
import android.database.sqlite.SQLiteConstraintException
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class QueueReadDeviceTest {
    private fun fixture(block:suspend(ContextWrapper,AudioExportStore,MutableList<String>)->Unit)=runBlocking<Unit>(Dispatchers.IO) {
        val real=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(real.cacheDir,"queue-read-${UUID.randomUUID()}").apply{mkdirs()}
        val context=object: ContextWrapper(real){override fun getNoBackupFilesDir()=dir}
        val queries=mutableListOf<String>()
        try{AudioExportStore(context,{sql->queries.add(sql);Unit}).use{block(context,it,queries)}}finally{dir.deleteRecursively()}
    }
    private fun tasks(size: Int)=(1..size).map{AudioExportTask("queue-$it",NativeTrack("12_$it","Queue $it","CI",1),listOf("CI queue"),it,ExportSource(12,"library","my"))}
    private fun file(task: AudioExportTask)=PublishedAudio(task.key,task.track,"content://media/external_primary/audio/media/900001","CI.mp3",ExportLayout.ROOT+"CI queue/","mp3","audio/mpeg",1200,100,"a".repeat(64),source=task.source)
    private fun issue(i: Int)=ExportSourceFailure(ExportSource(12,"catalog","source-$i"),"Source $i",MusicError.NETWORK,PlaylistKind.PLAYLIST)

    @Test fun v5MigrationPreservesTasksFilesFailuresAndCheckpointsAcrossReopen()=fixture{context,store,_->
        val tasks=tasks(4);store.replace(tasks,account=12,sources=listOf(issue(1)))
        store.done(tasks[0],file(tasks[0]));store.failed(tasks[1],MusicError.HTTP,ExportFailureDetails(httpStatus=503));store.status("PAUSED")
        store.checkpoint("must-preserve","checkpoint");val before=store.snapshot();val saved=store.savedPage();val rows=store.taskRows()
        legacyQueueSchema(store.writableDatabase);store.writableDatabase.version=5;store.close()
        AudioExportStore(context).use{migrated->
            val after=migrated.snapshot()
            assertEquals(6,migrated.readableDatabase.version)
            assertEquals(before.copy(queueId=after.queueId,revision=after.revision),after)
            assertEquals(saved,migrated.savedPage());assertEquals(rows,migrated.taskRows());assertEquals("checkpoint",migrated.checkpoint("must-preserve"))
            assertEquals(503,migrated.queuePage(DownloadsTab.ERRORS).failures.single().details!!.httpStatus)
            assertEquals(listOf(issue(1)),migrated.sourceFailures())
        }
    }
    @Test fun countersRevisionsAndDetailsRemainAtomicThroughRetryClearAndRollback()=fixture{_,store,_->
        val tasks=tasks(3);store.replace(tasks,account=12,sources=listOf(issue(1)));store.clearRecentFiles()
        store.sourceFailure(issue(1).copy(title="Changed source"));assertEquals(1,store.snapshot().sourceIssues)
        val claimed=store.claim()!!;assertEquals(tasks[0],claimed);assertEquals(3,store.snapshot().pending)
        store.done(claimed,file(claimed));store.failed(tasks[1],MusicError.HTTP,ExportFailureDetails(httpStatus=503))
        val before=store.snapshot();assertEquals(1,before.done);assertEquals(1,before.failed);assertEquals(1,before.pending)
        store.writableDatabase.execSQL("CREATE TRIGGER abort_queue BEFORE UPDATE ON tasks WHEN NEW.state='FAILED' BEGIN SELECT RAISE(ABORT,'injected rollback'); END")
        try{store.failed(tasks[2],MusicError.NETWORK);fail("Must roll back")}catch(_: SQLiteConstraintException){}
        assertEquals(before,store.snapshot());store.writableDatabase.execSQL("DROP TRIGGER abort_queue")
        store.retryFailed();assertEquals(0,store.snapshot().failed);assertEquals(2,store.snapshot().pending)
        assertTrue(store.queuePage(DownloadsTab.QUEUE).rows.all{it.error==null&&it.details==null})
        val saved=store.existing(claimed.key)!!;val revision=store.snapshot().revision
        store.retagged(saved,saved.copy(coverEmbedded=true));assertTrue(store.snapshot().revision>revision)
        assertTrue(store.queuePage(DownloadsTab.DONE).rows.single().file!!.coverEmbedded)
        val identity=store.snapshot().queueId;store.clearQueue();assertEquals(0,store.snapshot().total)
        assertEquals(0,store.snapshot().sourceIssues);assertNotEquals(identity,store.snapshot().queueId)
        assertNotNull(store.existing(claimed.key))
    }
    @Test fun progressDoesNotDecodeFailuresAndFirstPageDoesNotReadOffPagePayloads()=fixture{_,store,queries->
        val tasks=tasks(80);store.replace(tasks,account=12);store.clearRecentFiles()
        store.writableDatabase.beginTransaction()
        try{tasks.forEach{store.failed(it,MusicError.HTTP,ExportFailureDetails(httpStatus=503))};store.writableDatabase.setTransactionSuccessful()}finally{store.writableDatabase.endTransaction()}
        store.writableDatabase.execSQL("UPDATE tasks SET payload='not JSON' WHERE task_key=?",arrayOf(tasks[70].key))
        queries.clear();assertEquals(80,store.snapshot().failed);assertEquals(3,queries.size)
        assertTrue(queries.none{it.contains("failure_detail:")})
        queries.clear();val page=store.queuePage(DownloadsTab.ERRORS)
        assertEquals(80,page.total);assertEquals(50,page.rows.size);assertTrue(queries.size<=7)
        assertTrue(page.failures.all{it.details?.httpStatus==503})
        try{store.queuePage(DownloadsTab.ERRORS,50);fail("Corrupt requested row must not be silently omitted")}catch(_: kotlinx.serialization.SerializationException){}
        store.writableDatabase.execSQL("UPDATE tasks SET payload=? WHERE task_key=?",arrayOf(Json.encodeToString(tasks[70]),tasks[70].key))
        assertEquals(30,store.queuePage(DownloadsTab.ERRORS,50).rows.size)
    }
    @Test fun pagedErrorsAndFullExportKeepEveryTrackSourceAndDetail()=fixture{context,store,_->
        val tasks=tasks(70);store.replace(tasks,account=12,sources=(1..3).map(::issue))
        store.writableDatabase.beginTransaction()
        try {
            tasks.take(3).forEach{store.done(it,file(it).copy(uri="content://media/external_primary/audio/media/${900001+it.position}"))}
            tasks.drop(3).forEach{store.failed(it,MusicError.HTTP,ExportFailureDetails(httpStatus=503))}
            store.writableDatabase.setTransactionSuccessful()
        }finally{store.writableDatabase.endTransaction()}
        val first=store.queuePage(DownloadsTab.ERRORS);val last=store.queuePage(DownloadsTab.ERRORS,50)
        assertEquals(70,first.total);assertEquals(3,first.sources.size);assertEquals(47,first.failures.size);assertEquals(20,last.failures.size)
        assertEquals(tasks.drop(3).map{it.key},(first.failures+last.failures).map{it.task.key})
        val share=ExportReportShare.intent(context);val uri=share.clipData!!.getItemAt(0).uri
        val text=context.contentResolver.openInputStream(uri)!!.bufferedReader().use{it.readText()}
        val report=Json.parseToJsonElement(text).jsonObject;val exported=report.getValue("tracks").jsonArray
        assertEquals(70,exported.size);assertEquals(3,report.getValue("sources").jsonArray.size)
        assertEquals(67,exported.count{it.jsonObject["errorDetails"]?.jsonObject?.get("httpStatus")?.jsonPrimitive?.int==503})
        assertFalse(text.contains("content://"));assertFalse(text.contains("https://"))
        assertEquals(67,report.getValue("failed").jsonPrimitive.int)
        store.clearSourceFailure(issue(1).source);store.retryFailed()
        assertEquals(67,store.queuePage(DownloadsTab.QUEUE).total);assertEquals(2,store.queuePage(DownloadsTab.ERRORS,50).total)
        store.clearQueue();assertEquals(3,store.savedPage().size)
    }
    @Test fun progressAndPageQueryCountsStayBoundedFor1004500And20000Failures()=fixture{context,store,queries->
        val results=mutableListOf<JsonObject>()
        for(size in listOf(100,4500,20000)) {
            val tasks=tasks(size);val db=store.writableDatabase;db.beginTransaction()
            try {store.replace(tasks,account=12);store.clearRecentFiles();tasks.forEach{store.failed(it,MusicError.HTTP,ExportFailureDetails(httpStatus=503))};db.setTransactionSuccessful()}finally{db.endTransaction()}
            queries.clear();var start=SystemClock.elapsedRealtimeNanos();val snapshot=store.snapshot();val snapshotMs=(SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0
            val snapshotQueries=queries.size;assertEquals(size,snapshot.failed);assertEquals(3,snapshotQueries)
            queries.clear();start=SystemClock.elapsedRealtimeNanos();val page=store.queuePage(DownloadsTab.ERRORS);val pageMs=(SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0
            val pageQueries=queries.size;assertEquals(50,page.failures.size);assertTrue(pageQueries<=7)
            queries.clear();start=SystemClock.elapsedRealtimeNanos();var count=0
            store.forEachReportRow{assertEquals(503,it.details!!.httpStatus);count++}
            val reportMs=(SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0;val reportQueries=queries.size
            assertEquals(size,count);assertEquals(1,reportQueries)
            start=SystemClock.elapsedRealtimeNanos();AudioExportStore(context).use{assertEquals(snapshot,it.snapshot())};val reopenMs=(SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0
            results.add(buildJsonObject {put("tasks",size);put("snapshot_queries",snapshotQueries);put("page_queries",pageQueries);put("report_queries",reportQueries);put("snapshot_ms",snapshotMs);put("page_ms",pageMs);put("report_cursor_ms",reportMs);put("reopen_ms",reopenMs)})
            store.retryFailed();assertEquals(size,store.snapshot().pending);assertEquals(0,store.snapshot().failed)
            queries.clear();assertEquals(50,store.queuePage(DownloadsTab.QUEUE).rows.size);assertTrue(queries.size<=7)
            store.clearQueue()
        }
        android.util.Log.i("VKQueueBench","VK_QUEUE_BENCHMARK="+buildJsonObject{put("schema",1);put("api",android.os.Build.VERSION.SDK_INT);put("results",JsonArray(results))})
    }
}
