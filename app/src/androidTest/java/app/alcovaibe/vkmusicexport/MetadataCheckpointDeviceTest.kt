package app.alcovaibe.vkmusicexport

import android.content.ContextWrapper
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteFullException
import android.os.Build
import android.os.ParcelFileDescriptor
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

@RunWith(AndroidJUnit4::class)
class MetadataCheckpointDeviceTest {
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val json=Json{encodeDefaults=true}
    private fun isolated(name: String): Pair<ContextWrapper,File> {
        val dir=File(inst.targetContext.cacheDir,"metadata-checkpoint-$name").apply{deleteRecursively();mkdirs()}
        return object: ContextWrapper(inst.targetContext){override fun getNoBackupFilesDir()=File(dir,"private").apply{mkdirs()}} to dir
    }
    private fun run(size: Int,owner: Long=12)=TrackMetadataRun(owner,(1..size).map{
        TrackMetadataTarget("task-$it","content://media/external_primary/audio/media/$it","12_$it","Synthetic $it.mp3")
    })
    private fun legacy(run: TrackMetadataRun)=JsonObject(json.encodeToJsonElement(run).jsonObject-"runId").toString()
    private fun count(store: AudioExportStore,table: String)=store.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table",null).use{it.moveToFirst();it.getInt(0)}
    private fun number(store: AudioExportStore,sql: String)=store.readableDatabase.rawQuery(sql,null).use{it.moveToFirst();it.getLong(0)}

    @Test fun v3MigrationRetainsQueueAudioAndLegacyCursorAcrossReopen()=runBlocking<Unit>(Dispatchers.IO) {
        val(context,dir)=isolated("v3");var store=AudioExportStore(context);val shared=SharedAudioStore(context)
        var row: PublishedAudio?=null
        try {
            val task=ExportLayout.task(NativeTrack("12_870001","Legacy checkpoint","CI",1),listOf("CI metadata migration"),1,ExportSource(12,"library","migration"))
            store.replace(listOf(task),account=12)
            val input=File(dir,"tone.mp3").apply{writeBytes(inst.context.assets.open("native-audio/tone.mp3").use{it.readBytes()})}
            val saved=shared.publish(task,TransferResult(input,ProcessedAudio("mp3","audio/mpeg",1200))){}
            row=saved;store.done(task,saved);store.status("PAUSED")
            val checked=TrackMetadataTarget(saved.taskKey,saved.uri,saved.track.id,saved.displayName)
            val old=TrackMetadataRun(12,listOf(checked,run(2).targets[1]),next=1,unavailable=1,error=MusicError.NETWORK,
                issues=listOf(TrackMetadataIssue(saved.displayName,"Unavailable",checked)))
            val raw=legacy(old)
            store.checkpoint("track-metadata-run:12",raw);store.checkpoint("unrelated-checkpoint","preserve")
            val db=store.writableDatabase
            for(table in listOf("metadata_runs","metadata_targets","metadata_issues"))db.execSQL("DROP TABLE $table")
            db.version=3;store.close();store=AudioExportStore(context)
            assertEquals(4,store.readableDatabase.version)
            assertEquals(raw,store.checkpoint("track-metadata-run:12")) // DDL migration preserves old bytes until read.
            val loaded=store.metadataRun(12)!!
            assertEquals(old.copy(runId=loaded.runId),loaded);assertTrue(loaded.unfinished)
            assertNull(store.checkpoint("track-metadata-run:12"));assertNull(store.metadataRun(99))
            assertEquals("preserve",store.checkpoint("unrelated-checkpoint"));assertEquals("PAUSED",store.snapshot().status)
            assertEquals(saved,store.existing(task.key));assertTrue(shared.valid(saved))
            assertArrayEquals(input.readBytes(),inst.targetContext.contentResolver.openInputStream(android.net.Uri.parse(saved.uri))!!.use{it.readBytes()})
            store.close();store=AudioExportStore(context)
            assertEquals(loaded,store.metadataRun(12));assertEquals(loaded.state(),store.metadataRunState(12))
            val noApi=object: MusicApi {
                override suspend fun library(access: MusicAccess): NativeProbe=error("No completed file may be fetched again")
                override suspend fun catalog(access: MusicAccess,owner: Long): NativeCatalog=error("No catalog")
                override suspend fun playlist(access: MusicAccess,list: NativePlaylist): NativePage=error("No playlist")
                override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("No media")
            }
            val resumed=TrackMetadataBatch(noApi,{MusicAccess("a".repeat(64),"CI",accountId=12)},store::metadataTarget,
                verify={error("The pending removed file must be skipped")},update={_,_->error("No rewrite")},recover={},
                save=store::metadataRun,checkpoint=store::metadataProgress).run(loaded)
            assertEquals(2,resumed.next);assertEquals(1,resumed.skipped);assertEquals(1,resumed.unavailable)
            assertTrue(shared.valid(saved));assertEquals(saved,store.existing(task.key))
        }finally{row?.let{inst.targetContext.contentResolver.delete(android.net.Uri.parse(it.uri),null,null)};store.close();dir.deleteRecursively()}
    }
    @Test fun failedLegacyImportRollsBackAndKeepsItsOnlyOriginalCopy() {
        val(context,dir)=isolated("import-abort");val store=AudioExportStore(context)
        try {
            val old=run(2).copy(next=1,unavailable=1,issues=listOf(TrackMetadataIssue("Synthetic","Unavailable")))
            val raw=legacy(old);store.checkpoint("track-metadata-run:12",raw)
            store.writableDatabase.execSQL("CREATE TRIGGER abort_import BEFORE INSERT ON metadata_issues BEGIN SELECT RAISE(ABORT,'injected import interruption'); END")
            try{store.metadataRun(12);fail("Import must fail")}catch(_: SQLiteConstraintException){}
            assertEquals(raw,store.checkpoint("track-metadata-run:12"));assertEquals(0,count(store,"metadata_targets"));assertEquals(0,count(store,"metadata_runs"))
            store.writableDatabase.execSQL("DROP TRIGGER abort_import")
            val restored=store.metadataRun(12)!!
            assertEquals(old.copy(runId=restored.runId),restored);assertNull(store.checkpoint("track-metadata-run:12"))
            store.checkpoint("track-metadata-run:99","not valid JSON")
            try{store.metadataRun(99);fail("Malformed legacy data must fail closed")}catch(e: MusicFailure){assertEquals(MusicError.STORAGE,e.code)}
            assertEquals("not valid JSON",store.checkpoint("track-metadata-run:99"));assertEquals(restored,store.metadataRun(12))
            val oversized=run(1,98).let{it.copy(targets=it.targets.map{target->target.copy(name="x".repeat(65536))})}
            val oversizedRaw=legacy(oversized);store.checkpoint("track-metadata-run:98",oversizedRaw)
            try{store.metadataRun(98);fail("An oversized row must retain its legacy source")}catch(e: MusicFailure){assertEquals(MusicError.STORAGE,e.code)}
            assertEquals(oversizedRaw,store.checkpoint("track-metadata-run:98"))
        }finally{store.close();dir.deleteRecursively()}
    }
    @Test fun cursorAndIssuesRollbackTogetherAndStaleWritersCannotReplaceANewRun() {
        val(context,dir)=isolated("cursor-abort");var store=AudioExportStore(context)
        try {
            val before=run(2);store.metadataRun(before)
            val after=before.copy(next=1,unavailable=1,issues=listOf(TrackMetadataIssue("Synthetic","Unavailable")))
            store.writableDatabase.execSQL("CREATE TRIGGER abort_cursor BEFORE UPDATE OF progress ON metadata_runs BEGIN SELECT RAISE(ABORT,'injected checkpoint interruption'); END")
            try{store.metadataProgress(before,after);fail("Checkpoint must fail")}catch(_: SQLiteConstraintException){}
            assertEquals(before,store.metadataRun(12));assertEquals(0,count(store,"metadata_issues"))
            store.writableDatabase.execSQL("DROP TRIGGER abort_cursor")
            store.metadataProgress(before,after);store.close();store=AudioExportStore(context)
            assertEquals(after,store.metadataRun(12))
            try{store.metadataProgress(before,after);fail("A duplicate old cursor must be rejected")}catch(e: MusicFailure){assertEquals(MusicError.STORAGE,e.code)}
            val newer=run(3);store.metadataRun(newer)
            try{store.metadataProgress(after,after.copy(next=2,current=1));fail("An old run must not overwrite its replacement")}catch(e: MusicFailure){assertEquals(MusicError.STORAGE,e.code)}
            assertEquals(newer,store.metadataRun(12))
        }finally{store.close();dir.deleteRecursively()}
    }
    @Test fun sqliteFullLeavesThePreviousCursorAndIssueSetRecoverable() {
        val(context,dir)=isolated("full");var store=AudioExportStore(context)
        try {
            val before=run(2);store.metadataRun(before)
            // PRAGMA through a cursor can use a read connection in the WAL pool.
            // Pin a separate setup transaction to the primary/write connection.
            // The checkpoint below still runs in its own real transaction.
            fun primary(sql: String): Long {
                val db=store.writableDatabase;db.beginTransaction()
                try {
                    val value=number(store,sql);db.setTransactionSuccessful();return value
                }finally{db.endTransaction()}
            }
            val maximum=primary("PRAGMA max_page_count")
            val pages=number(store,"PRAGMA page_count")
            assertEquals(pages,primary("PRAGMA max_page_count=$pages"))
            val after=before.copy(next=1,failed=1,issues=listOf(TrackMetadataIssue("x".repeat(60000),"Synthetic storage pressure")))
            try{store.metadataProgress(before,after);fail("Expected actual SQLiteFullException")}catch(_: SQLiteFullException){}
            primary("PRAGMA max_page_count=$maximum")
            store.close();store=AudioExportStore(context)
            assertEquals(before,store.metadataRun(12));assertEquals(0,count(store,"metadata_issues"))
        }finally{store.close();dir.deleteRecursively()}
    }
    @Test fun immutableTargetsAndManualIssueResolutionSurviveAccountScopedReopen() {
        val(context,dir)=isolated("manual");var store=AudioExportStore(context)
        try {
            val first=run(3).copy(next=1,unavailable=1,issues=listOf(TrackMetadataIssue("Synthetic","Unavailable",run(3).targets[0])))
            store.metadataRun(first);store.metadataRun(run(1,99))
            store.writableDatabase.execSQL("CREATE TRIGGER forbid_targets BEFORE INSERT ON metadata_targets BEGIN SELECT RAISE(ABORT,'immutable targets rewritten'); END")
            val manual=first.copy(unavailable=0,updated=1,issues=first.issues.map{it.copy(resolved=true,selectedId="56_1")})
            store.metadataRun(manual)
            val after=manual.copy(next=2,current=1);store.metadataProgress(manual,after)
            store.close();store=AudioExportStore(context)
            assertEquals(after,store.metadataRun(12));assertEquals(1,store.metadataRun(99)!!.targets.size)
            try{store.metadataRun(after.copy(targets=after.targets.reversed()));fail("The same run cannot change its target order")}catch(e: MusicFailure){assertEquals(MusicError.STORAGE,e.code)}
            assertEquals(after,store.metadataRun(12))
            store.writableDatabase.execSQL("UPDATE metadata_targets SET payload='[]' WHERE owner=12")
            try{store.metadataRun(12);fail("Damaged snapshot must not silently resume")}catch(e: MusicFailure){assertEquals(MusicError.STORAGE,e.code)}
            assertNotNull(store.metadataRun(99))
        }finally{store.close();dir.deleteRecursively()}
    }
    @Test fun maintenanceJournalUsesDatabaseIssuesAndRestoresThemAfterReload()=runBlocking<Unit>(Dispatchers.IO) {
        val(context,dir)=isolated("maintenance");val real=inst.targetContext
        try {
            val run=run(2).copy(next=1,unavailable=1,issues=listOf(TrackMetadataIssue("Synthetic","Unavailable",run(2).targets[0])))
            AudioExportStore(context).use{it.metadataRun(run)}
            MaintenanceRuntime.initialize(context)
            MaintenanceRuntime.updateMetadata(12){run.state()}
            MaintenanceRuntime.updateMetadata(99){it} // A rejected screen load must not rebind the active state.
            MaintenanceRuntime.checkpoint()
            val file=File(context.noBackupFilesDir,"maintenance.json")
            val saved=Json.parseToJsonElement(file.readText()).jsonObject
            assertEquals(12,saved.getValue("metadataOwner").jsonPrimitive.int)
            assertTrue(saved.getValue("metadata").jsonObject.getValue("issues").jsonArray.isEmpty())
            MaintenanceRuntime.initialize(real);MaintenanceRuntime.initialize(context)
            assertEquals(run.issues,MaintenanceRuntime.metadata.value.issues)
            assertEquals(1,MaintenanceRuntime.metadata.value.processed)
        }finally{MaintenanceRuntime.initialize(real);dir.deleteRecursively()}
    }
    @Test fun checkpointPayloadScalesLinearlyFor1004500And20000Targets()=runBlocking<Unit>(Dispatchers.IO) {
        val results=mutableListOf<JsonObject>()
        for(size in listOf(100,4500,20000)) {
            val(context,dir)=isolated("benchmark-$size");var store=AudioExportStore(context)
            try {
                val db=store.writableDatabase
                db.execSQL("CREATE TABLE checkpoint_metrics(target_writes INTEGER,target_bytes INTEGER,progress_writes INTEGER,progress_bytes INTEGER)")
                db.execSQL("INSERT INTO checkpoint_metrics VALUES(0,0,0,0)")
                db.execSQL("CREATE TRIGGER measure_targets AFTER INSERT ON metadata_targets BEGIN UPDATE checkpoint_metrics SET target_writes=target_writes+1,target_bytes=target_bytes+length(CAST(new.payload AS BLOB)); END")
                for(event in listOf("INSERT","UPDATE"))db.execSQL("CREATE TRIGGER measure_progress_${event.lowercase()} AFTER $event ON metadata_runs BEGIN UPDATE checkpoint_metrics SET progress_writes=progress_writes+1,progress_bytes=progress_bytes+length(CAST(new.progress AS BLOB)); END")
                var cursor=run(size)
                val begin=SystemClock.elapsedRealtimeNanos();store.metadataRun(cursor)
                val snapshotMs=(SystemClock.elapsedRealtimeNanos()-begin)/1_000_000.0
                val checkpointNanos=LongArray(size)
                val started=SystemClock.elapsedRealtimeNanos()
                repeat(size) {
                    val after=cursor.copy(next=cursor.next+1,current=cursor.current+1)
                    val checkpointStart=SystemClock.elapsedRealtimeNanos()
                    store.metadataProgress(cursor,after)
                    checkpointNanos[it]=SystemClock.elapsedRealtimeNanos()-checkpointStart
                    cursor=after
                }
                val writeMs=(SystemClock.elapsedRealtimeNanos()-started)/1_000_000.0
                val targets=number(store,"SELECT target_writes FROM checkpoint_metrics")
                val targetBytes=number(store,"SELECT target_bytes FROM checkpoint_metrics")
                val progressWrites=number(store,"SELECT progress_writes FROM checkpoint_metrics")
                val progressBytes=number(store,"SELECT progress_bytes FROM checkpoint_metrics")
                assertEquals(size.toLong(),targets);assertEquals(size+1L,progressWrites)
                assertTrue(progressBytes<512L*(size+1));assertEquals(0,count(store,"metadata_issues"))
                store.close();store=AudioExportStore(context)
                val read=SystemClock.elapsedRealtimeNanos();val restored=store.metadataRun(12)!!
                val restoreMs=(SystemClock.elapsedRealtimeNanos()-read)/1_000_000.0
                assertEquals(cursor,restored)
                checkpointNanos.sort()
                results+=buildJsonObject {
                    put("targets",size);put("snapshot_ms",snapshotMs);put("checkpoint_ms",writeMs);put("restore_ms",restoreMs)
                    put("checkpoint_p95_ms",checkpointNanos[(size*95+99)/100-1]/1_000_000.0)
                    put("target_writes",targets);put("target_payload_bytes",targetBytes);put("progress_writes",progressWrites);put("progress_payload_bytes",progressBytes)
                    put("legacy_targets_min_bytes_estimate",targetBytes*(size+1));put("issues_in_workload",0)
                }
                if(size==20000) {
                    val old=run(size,99).let{it.copy(targets=it.targets.map{target->target.copy(name="Синтетический трек — "+target.name)})}
                    val raw=legacy(old);assertTrue(raw.toByteArray().size>2*1024*1024)
                    store.checkpoint("track-metadata-run:99",raw)
                    val migrated=store.metadataRun(99)!!
                    assertEquals(old.copy(runId=migrated.runId),migrated)
                    assertNull(store.checkpoint("track-metadata-run:99"))
                    assertEquals(cursor,store.metadataRun(12))
                }
            }finally{store.close();dir.deleteRecursively()}
        }
        val report=buildJsonObject {
            put("schema",1);put("api",Build.VERSION.SDK_INT);put("environment","CI emulator; isolated SQLite metadata checkpoints; no VK/media scan")
            put("byte_metric","UTF-8 SQL payload sizes observed by triggers, not physical WAL/device writes")
            put("results",JsonArray(results))
        }
        val file=File(inst.targetContext.getExternalFilesDir(null),"metadata-benchmarks/api-${Build.VERSION.SDK_INT}.json").apply{parentFile!!.mkdirs();writeText(report.toString())}
        // executeShellCommand runs one command, without shell operators such as &&.
        val destination="/data/local/tmp/vk-music-export-benchmarks"
        for(command in listOf("mkdir -p $destination","cp ${file.absolutePath} $destination/")) {
            ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(command)).use{it.readBytes()}
        }
        val copied=ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("cat $destination/${file.name}")).use{it.readBytes().toString(Charsets.UTF_8)}
        assertEquals(report.toString(),copied)
    }
}
