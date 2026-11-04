package app.alcovaibe.vkmusicexport

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteFullException
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DuplicateReceiptDeviceTest {
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val now=1_800_000_000_000L
    private val signature=DuplicateSignature("mp3","a".repeat(64))
    private fun track(i: Int)=NativeTrack("12_$i","Synthetic $i","CI",60,album=MusicAlbum("old","Old"))
    private fun isolated(name: String): Pair<Context,File> {
        val dir=File(inst.targetContext.cacheDir,"receipt-$name").apply{deleteRecursively();mkdirs()}
        return object: ContextWrapper(inst.targetContext) {
            override fun getNoBackupFilesDir()=File(dir,"private").apply{mkdirs()}
            override fun getCacheDir()=File(dir,"cache").apply{mkdirs()}
        } to dir
    }
    private fun audio(context: Context)=File(context.cacheDir,"verified.output").apply{writeBytes(byteArrayOf(1,2,3))}
    private fun legacy(context: Context,tracks: List<NativeTrack>,fullKeys: Boolean=false,created: Long=now): File {
        val data=buildJsonObject {
            put("schema",2);put("owner",12);put("snapshot",DuplicateReceiptKeys.snapshot(tracks));put("created",created)
            putJsonObject("checks"){tracks.forEach{track->putJsonObject(if(fullKeys)DuplicateReceiptKeys.legacy(track)else DuplicateReceiptKeys.key(track)) {
                put("codec",signature.codec);put("sha256",signature.sha256)
            }}}
        }
        return File(context.noBackupFilesDir,"duplicate-scan/checks.json").apply{parentFile!!.mkdirs();writeText(data.toString())}
    }
    private fun number(db: SQLiteDatabase,sql: String)=db.rawQuery(sql,null).use{it.moveToFirst();it.getLong(0)}
    private fun primary(db: SQLiteDatabase,sql: String): Long {
        db.beginTransaction()
        try{val value=number(db,sql);db.setTransactionSuccessful();return value}finally{db.endTransaction()}
    }
    @Test fun legacyBackupAndAlbumKeysMigrateOnceAndSurviveReopen() {
        val(context,dir)=isolated("legacy");val tracks=(1..3).map(::track)
        try {
            val file=legacy(context,tracks,true);assertTrue(file.renameTo(File(file.path+".bak")))
            val orphan=File(file.parentFile,"old.mp3").apply{writeBytes(byteArrayOf(9))}
            DuplicateScanCache(context,{now}).use{cache->
                assertEquals(3,cache.count());cache.begin(12,tracks)
                tracks.forEach{assertEquals(signature,cache.get(it))}
                assertFalse(file.exists());assertFalse(File(file.path+".bak").exists());assertFalse(orphan.exists())
            }
            DuplicateScanCache(context,{now}).use{cache->
                val reordered=tracks.reversed().map{it.copy(album=MusicAlbum("new","New"))}
                cache.begin(12,reordered);assertEquals(3,cache.count())
                reordered.forEach{assertEquals(signature,cache.get(it))}
            }
        }finally{dir.deleteRecursively()}
    }
    @Test fun failedLegacyImportRetainsOriginalAndRetriesWithoutPartialRows() {
        val(context,dir)=isolated("import-failure");val tracks=(1..3).map(::track)
        val file=legacy(context,tracks);val bytes=file.readBytes();val store=DuplicateReceiptStore(context)
        val cache=DuplicateScanCache(context,{now},store)
        try {
            val db=store.writableDatabase
            db.execSQL("CREATE TRIGGER abort_receipt_import BEFORE INSERT ON duplicate_import BEGIN SELECT RAISE(ABORT,'injected interruption'); END")
            try{cache.count();fail("Import must fail")}catch(_: SQLiteConstraintException){}
            assertArrayEquals(bytes,file.readBytes());assertEquals(0L,number(db,"SELECT COUNT(*) FROM duplicate_receipts"))
            assertEquals(0L,number(db,"SELECT COUNT(*) FROM duplicate_state"))
            db.execSQL("DROP TRIGGER abort_receipt_import")
            assertEquals(3,cache.count());assertFalse(file.exists());cache.begin(12,tracks)
            tracks.forEach{assertEquals(signature,cache.get(it))}
            cache.close()
            DuplicateScanCache(context,{now}).use{assertEquals(3,it.count())}
        }finally{cache.close();dir.deleteRecursively()}
    }
    @Test fun malformedLegacyAndCorruptRowsNeverBecomeMatches() {
        val(context,dir)=isolated("corrupt");val rows=listOf(track(1),track(2));val file=legacy(context,rows).apply{writeText("{broken")}
        val raw=file.readBytes();val store=DuplicateReceiptStore(context);val cache=DuplicateScanCache(context,{now},store)
        try {
            assertEquals(0,cache.count());assertArrayEquals(raw,file.readBytes())
            cache.begin(12,rows);assertNull(cache.get(rows[0]));cache.save(rows[0],signature,audio(context));cache.save(rows[1],signature,audio(context))
            store.writableDatabase.execSQL("UPDATE duplicate_receipts SET signature_version=1 WHERE track_key=?",arrayOf(DuplicateReceiptKeys.key(rows[0])))
            assertNull(cache.get(rows[0]));assertEquals(1,cache.count());assertEquals(signature,cache.get(rows[1]))
            assertArrayEquals(raw,file.readBytes());cache.close()
            DuplicateScanCache(context,{now}).use{it.begin(12,rows);assertNull(it.get(rows[0]));assertEquals(signature,it.get(rows[1]))}
        }finally{cache.close();dir.deleteRecursively()}
    }
    @Test fun accountExpiryAndSnapshotChangesFenceStaleInstances() {
        val(context,dir)=isolated("fence");var clock=now;val rows=(1..3).map(::track);val input=audio(context)
        val first=DuplicateScanCache(context,{clock});val second=DuplicateScanCache(context,{clock})
        fun stale(){try{first.save(rows[0],signature,input);fail("Stale writer must be rejected")}catch(e: MusicFailure){assertEquals(MusicError.STORAGE,e.code)}}
        try {
            first.begin(12,rows);first.save(rows[0],signature,input);first.save(rows[1],signature,input)
            val edited=rows[1].copy(duration=61)
            second.begin(12,listOf(edited,rows[0]));assertEquals(1,second.count());assertEquals(signature,second.get(rows[0]));assertNull(second.get(edited))
            assertNull(first.get(rows[0]));stale()
            first.begin(12,listOf(edited,rows[0]));assertEquals(signature,first.get(rows[0]))
            second.begin(99,rows);assertEquals(0,second.count());assertEquals(0,first.count());stale()
            first.begin(12,rows);first.save(rows[0],signature,input)
            clock+=DuplicateReceiptKeys.TTL;assertNull(first.get(rows[0]));assertEquals(0,first.count());stale()
            second.begin(12,rows);assertEquals(0,second.count());second.save(rows[1],signature,input)
            clock--;assertNull(second.get(rows[1]));assertEquals(0,second.count())
        }finally{first.close();second.close();dir.deleteRecursively()}
    }
    @Test fun abortAndActualSQLiteFullKeepThePreviousReceiptAndCount() {
        val(context,dir)=isolated("full");val rows=listOf(track(1),track(2));val store=DuplicateReceiptStore(context)
        val cache=DuplicateScanCache(context,{now},store);val input=audio(context)
        try {
            cache.begin(12,rows);cache.save(rows[0],signature,input);val db=store.writableDatabase
            db.execSQL("CREATE TRIGGER abort_receipt_count BEFORE UPDATE OF receipt_count ON duplicate_state BEGIN SELECT RAISE(ABORT,'injected write interruption'); END")
            try{cache.save(rows[1],signature,input);fail("Write must fail")}catch(_: SQLiteConstraintException){}
            assertEquals(1,cache.count());assertNull(cache.get(rows[1]));assertEquals(signature,cache.get(rows[0]))
            db.execSQL("DROP TRIGGER abort_receipt_count")
            // Consume free pages with unrelated test rows, then cap the writer connection.
            db.execSQL("CREATE TABLE pressure(value BLOB)")
            db.execSQL("CREATE TRIGGER receipt_pressure AFTER INSERT ON duplicate_receipts BEGIN INSERT INTO pressure(value) VALUES(zeroblob(65536)); END")
            val maximum=primary(db,"PRAGMA max_page_count")
            primary(db,"PRAGMA max_page_count="+number(db,"PRAGMA page_count"))
            var full=false
            try{repeat(100){db.execSQL("INSERT INTO pressure(value) VALUES(zeroblob(65536))")}}catch(_: SQLiteFullException){full=true}
            assertTrue("The isolated DB must actually reach SQLiteFull",full)
            // The existing trigger forces an overflow inside the receipt transaction itself.
            try{cache.save(rows[1],signature,input);fail("Receipt transaction must raise SQLiteFull")}catch(_: SQLiteFullException){}
            primary(db,"PRAGMA max_page_count=$maximum");cache.close()
            DuplicateScanCache(context,{now}).use{it.begin(12,rows);assertEquals(1,it.count());assertEquals(signature,it.get(rows[0]));assertNull(it.get(rows[1]))}
        }finally{cache.close();dir.deleteRecursively()}
    }
    @Test fun clearInvalidatesOtherInstancesWithoutTouchingExportQueueOrMedia()=runBlocking<Unit>(Dispatchers.IO) {
        val(context,dir)=isolated("clear");val library=AudioExportStore(context);val shared=SharedAudioStore(context)
        val first=DuplicateScanCache(context,{now});val second=DuplicateScanCache(context,{now});var published: PublishedAudio?=null
        try {
            val task=ExportLayout.task(track(870081),listOf("CI receipt preservation"),1,ExportSource(12,"library","receipt-ci"))
            library.replace(listOf(task),account=12)
            val input=File(dir,"tone.mp3").apply{writeBytes(inst.context.assets.open("native-audio/tone.mp3").use{it.readBytes()})}
            val row=shared.publish(task,TransferResult(input,ProcessedAudio("mp3","audio/mpeg",1200))){}
            published=row;library.done(task,row);library.status("PAUSED")
            first.begin(12,listOf(task.track));first.save(task.track,signature,input)
            second.clear();assertEquals(0,second.count());assertNull(first.get(task.track))
            try{first.save(task.track,signature,input);fail("Clear must fence an old writer")}catch(_: MusicFailure){}
            assertEquals(row,library.existing(task.key));assertEquals("PAUSED",library.snapshot().status);assertTrue(shared.valid(row))
            assertArrayEquals(input.readBytes(),inst.targetContext.contentResolver.openInputStream(android.net.Uri.parse(row.uri))!!.use{it.readBytes()})
        }finally{published?.let{inst.targetContext.contentResolver.delete(android.net.Uri.parse(it.uri),null,null)};first.close();second.close();library.close();dir.deleteRecursively()}
    }
    @Test fun invalidSignaturesAndMissingAudioNeverCommitReceipts() {
        val(context,dir)=isolated("validation");val row=track(1);val cache=DuplicateScanCache(context,{now});val file=audio(context)
        try {
            cache.begin(12,listOf(row))
            for(bad in listOf(signature.copy(version=1),signature.copy(codec="aac"),signature.copy(sha256="broken"))) {
                try{cache.save(row,bad,file);fail("Invalid evidence must not be saved")}catch(_: MusicFailure){}
            }
            file.delete();try{cache.save(row,signature,file);fail("Missing verified output must not be saved")}catch(_: MusicFailure){}
            assertEquals(0,cache.count());assertNull(cache.get(row))
        }finally{cache.close();dir.deleteRecursively()}
    }
    @Test fun receiptWritesAndReadsScaleFor1004500And20000() {
        val results=mutableListOf<JsonObject>()
        for(size in listOf(100,4500,20000)) {
            val(context,dir)=isolated("benchmark-$size");val store=DuplicateReceiptStore(context);var cache=DuplicateScanCache(context,{now},store)
            try {
                val db=store.writableDatabase
                db.execSQL("CREATE TABLE receipt_metrics(inserts INTEGER,updates INTEGER,receipt_text_bytes INTEGER,state_writes INTEGER,state_text_bytes INTEGER)")
                db.execSQL("INSERT INTO receipt_metrics VALUES(0,0,0,0,0)")
                db.execSQL("CREATE TRIGGER receipt_insert_metric AFTER INSERT ON duplicate_receipts BEGIN UPDATE receipt_metrics SET inserts=inserts+1,receipt_text_bytes=receipt_text_bytes+length(CAST(new.track_key AS BLOB))+length(CAST(new.codec AS BLOB))+length(CAST(new.sha256 AS BLOB)); END")
                db.execSQL("CREATE TRIGGER receipt_update_metric AFTER UPDATE ON duplicate_receipts BEGIN UPDATE receipt_metrics SET updates=updates+1; END")
                for(event in listOf("INSERT","UPDATE"))db.execSQL("CREATE TRIGGER receipt_state_${event.lowercase()} AFTER $event ON duplicate_state BEGIN UPDATE receipt_metrics SET state_writes=state_writes+1,state_text_bytes=state_text_bytes+length(CAST(new.generation AS BLOB))+length(CAST(new.snapshot AS BLOB)); END")
                val tracks=(1..size).map(::track);val input=audio(context);val latencies=LongArray(size)
                val begin=SystemClock.elapsedRealtimeNanos();cache.begin(12,tracks)
                val beginMs=(SystemClock.elapsedRealtimeNanos()-begin)/1_000_000.0
                val start=SystemClock.elapsedRealtimeNanos()
                tracks.forEachIndexed{i,track->val t=SystemClock.elapsedRealtimeNanos();cache.save(track,signature,input);latencies[i]=SystemClock.elapsedRealtimeNanos()-t}
                val saveMs=(SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0
                val textBytes=number(db,"SELECT receipt_text_bytes FROM receipt_metrics")
                val stateBytes=number(db,"SELECT state_text_bytes FROM receipt_metrics")
                val walBytes=File(context.noBackupFilesDir,"duplicate-scan.db-wal").length()
                assertEquals(size.toLong(),number(db,"SELECT inserts FROM receipt_metrics"));assertEquals(0L,number(db,"SELECT updates FROM receipt_metrics"))
                assertEquals(size+1L,number(db,"SELECT state_writes FROM receipt_metrics"))
                cache.close();cache=DuplicateScanCache(context,{now})
                val reopen=SystemClock.elapsedRealtimeNanos();cache.begin(12,tracks);assertEquals(size,cache.count())
                val reopenMs=(SystemClock.elapsedRealtimeNanos()-reopen)/1_000_000.0
                val read=SystemClock.elapsedRealtimeNanos();tracks.forEach{assertEquals(signature,cache.get(it))}
                val readMs=(SystemClock.elapsedRealtimeNanos()-read)/1_000_000.0
                val restored=DuplicateReceiptStore(context)
                restored.use {
                    val verified=it.readableDatabase
                    assertEquals(size.toLong(),number(verified,"SELECT inserts FROM receipt_metrics"));assertEquals(0L,number(verified,"SELECT updates FROM receipt_metrics"))
                    assertEquals(size+1L,number(verified,"SELECT state_writes FROM receipt_metrics"))
                }
                latencies.sort()
                results+=buildJsonObject {
                    put("receipts",size);put("begin_ms",beginMs);put("save_ms",saveMs);put("save_p95_ms",latencies[(size*95+99)/100-1]/1_000_000.0)
                    put("reopen_ms",reopenMs);put("read_all_ms",readMs);put("receipt_inserts",size);put("receipt_updates",0);put("state_writes",size+1)
                    put("receipt_text_bytes",textBytes);put("state_text_bytes",stateBytes);put("observed_wal_size_bytes",walBytes)
                    put("legacy_min_text_bytes_estimate",textBytes*(size+1)/2)
                }
                if(size==20000) {
                    val extra=track(size+1);cache.begin(12,tracks+extra);cache.save(extra,signature,input)
                    assertEquals(size,cache.count());assertNull(cache.get(tracks.first()));assertEquals(signature,cache.get(extra))
                    val(legacyContext,legacyDir)=isolated("large-legacy")
                    try {
                        val old=legacy(legacyContext,tracks);assertTrue(old.length()>2*1024*1024)
                        DuplicateScanCache(legacyContext,{now}).use{migrated->
                            migrated.begin(12,tracks);assertEquals(size,migrated.count())
                            tracks.forEach{assertEquals(signature,migrated.get(it))};assertFalse(old.exists())
                        }
                    }finally{legacyDir.deleteRecursively()}
                }
            }finally{cache.close();store.close();dir.deleteRecursively()}
        }
        val report=buildJsonObject {
            put("schema",1);put("api",Build.VERSION.SDK_INT);put("workload","isolated SQLite receipts; no VK/media hashing; one CI emulator run")
            put("byte_metric","UTF-8 text column bytes; WAL file size is one observation, not cumulative writes")
            put("results",JsonArray(results))
        }.toString()
        assertTrue(report.toByteArray().size<3000)
        assertTrue(android.util.Log.i("VKReceiptBench","VK_RECEIPT_BENCHMARK="+report)>0)
    }
}
