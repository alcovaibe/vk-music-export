package app.alcovaibe.vkmusicexport

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SourceMembershipDeviceTest {
    private val json=Json{encodeDefaults=true}
    private val source=ExportSource(12,"catalog","playlist:12:8")
    private val other=source.copy(account=13)
    private fun task(source: ExportSource=this.source,occurrence: Int=1)=ExportLayout.task(
        NativeTrack("99_7","Fixture","CI",1),listOf("Альбомы","CI"),occurrence,source,occurrence)
    private fun row(task: AudioExportTask)=PublishedAudio(task.key,task.track,"content://media/external/audio/media/11",
        "CI.mp3",ExportLayout.ROOT+"Альбомы/CI/","mp3","audio/mpeg",1000,123,"0".repeat(64),
        source=task.source,occurrence=task.occurrence)
    private fun isolated(): Pair<Context,File> {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(base.cacheDir,"membership-"+UUID.randomUUID()).apply{check(mkdirs())}
        return object: ContextWrapper(base){override fun getNoBackupFilesDir()=root} to root
    }
    /** Golden schema 6. Do not use the new migration to create its own baseline. */
    private fun old(context: Context)=object: SQLiteOpenHelper(context,File(context.noBackupFilesDir,"audio-export.db").path,null,6) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE tasks(ordinal INTEGER PRIMARY KEY AUTOINCREMENT, task_key TEXT UNIQUE NOT NULL, payload TEXT NOT NULL, state TEXT NOT NULL, error TEXT, prepared TEXT)")
            db.execSQL("CREATE INDEX task_states ON tasks(state,ordinal)")
            db.execSQL("CREATE TABLE files(task_key TEXT PRIMARY KEY,track_id TEXT NOT NULL,payload TEXT NOT NULL)")
            db.execSQL("CREATE INDEX file_tracks ON files(track_id)")
            db.execSQL("CREATE TABLE meta(name TEXT PRIMARY KEY,value TEXT NOT NULL)")
            db.execSQL("CREATE TABLE directories(identity TEXT PRIMARY KEY,relative_path TEXT NOT NULL,comparison_key TEXT NOT NULL UNIQUE)")
            db.execSQL("CREATE TABLE source_failures(source_key TEXT PRIMARY KEY,payload TEXT NOT NULL)")
            TrackMetadataCheckpoints.create(db);SavedFileIndex.create(db);QueueReadSchema.create(db)
        }
        override fun onUpgrade(db: SQLiteDatabase,old: Int,next: Int)=error("Fixture must remain schema 6")
    }
    private fun seed(context: Context,rows: List<PublishedAudio>)=old(context).use{helper->
        val db=helper.writableDatabase
        for(row in rows) {
            db.execSQL("INSERT INTO files(task_key,track_id,payload,uri) VALUES(?,?,?,?)",
                arrayOf(row.taskKey,row.track.id,json.encodeToString(row),row.uri))
            SavedFileIndex(db).sync(row.uri)
        }
        db.rawQuery("SELECT value FROM meta WHERE name='queue_identity'",null).use{check(it.moveToFirst());it.getString(0)}
    }
    private fun indexed(store: AudioExportStore) {
        store.readableDatabase.rawQuery("EXPLAIN QUERY PLAN SELECT payload FROM files WHERE source_account=? AND source_kind=? AND source_id=?",
            arrayOf("12","catalog",source.id)).use{c->
            val plan=buildList{while(c.moveToNext())add(c.getString(3))}.joinToString()
            assertTrue(plan,plan.contains("file_sources"));assertFalse(plan,plan.contains("SCAN files"))
        }
    }
    @Test fun schema6UpgradePreservesAllOwnersOccurrencesUrisQueueAndReopen() {
        val (context,root)=isolated()
        val rows=listOf(row(task()),row(task(occurrence=2)),row(task(other)))
        try {
            val queue=seed(context,rows)
            repeat(2) {AudioExportStore(context).use{store->
                assertEquals(7,store.readableDatabase.version)
                assertEquals(rows.take(2),store.sourceFiles(source))
                assertEquals(listOf(rows.last()),store.sourceFiles(other))
                assertEquals(mapOf(source.id to 2),store.sourceCounts(12,"catalog"))
                assertEquals(rows,store.aliases(rows.first().uri))
                assertEquals(queue,store.snapshot().queueId);assertEquals(0,store.snapshot().pending)
                assertEquals(rows.map{it.taskKey}.toSet(),store.savedFiles().map{it.taskKey}.toSet())
                indexed(store)
            }}
        }finally{root.deleteRecursively()}
    }
    @Test fun indexedSourceReadsAt1004500And20000NeverDecodeUnrelatedPayloads() {
        for(size in listOf(100,4500,20000)) {
            val (context,root)=isolated()
            try{AudioExportStore(context).use{store->
                val first=row(task());val second=row(task(occurrence=2));store.imported(first);store.imported(second)
                val db=store.writableDatabase;db.beginTransaction()
                try {
                    repeat(size-2) {n->db.execSQL(
                        "INSERT INTO files(task_key,track_id,payload,uri,source_account,source_kind,source_id,occurrence) VALUES(?,?,?,?,?,?,?,?)",
                        arrayOf<Any>("foreign-$n","88_${n+1}","UNRELATED_PAYLOAD_MUST_NOT_BE_DECODED",
                            "content://media/external/audio/media/${n+100}",99,"catalog","foreign",1))}
                    db.setTransactionSuccessful()
                }finally{db.endTransaction()}
                assertEquals(listOf(first,second),store.sourceFiles(source))
                assertEquals(mapOf(source.id to 2),store.sourceCounts(12,"catalog"))
                assertTrue(store.sourceFiles(source.copy(account=11)).isEmpty())
                indexed(store)
            }}finally{root.deleteRecursively()}
        }
    }
    @Test fun malformedLegacyPayloadRollsBackSchemaAndPreservesOriginals() {
        val (context,root)=isolated();val rows=listOf(row(task()),row(task(other)))
        val original=File(root,"user-original.mp3").apply{writeBytes(byteArrayOf(1,2,3,4))}
        try {
            seed(context,rows)
            old(context).use{it.writableDatabase.execSQL("UPDATE files SET payload='broken-json' WHERE task_key=?",arrayOf(rows.last().taskKey))}
            try{AudioExportStore(context).use{it.writableDatabase};fail("Corrupt migration must fail")}
            catch(e: MusicFailure){assertEquals(MusicError.STORAGE,e.code)}
            SQLiteDatabase.openDatabase(File(root,"audio-export.db").path,null,SQLiteDatabase.OPEN_READONLY).use{db->
                assertEquals(6,db.version)
                db.rawQuery("PRAGMA table_info(files)",null).use{c->while(c.moveToNext())assertNotEquals("source_account",c.getString(1))}
                db.rawQuery("SELECT payload FROM files WHERE task_key=?",arrayOf(rows.first().taskKey)).use{c->
                    assertTrue(c.moveToFirst());assertEquals(json.encodeToString(rows.first()),c.getString(0))
                }
            }
            assertArrayEquals(byteArrayOf(1,2,3,4),original.readBytes())
        }finally{root.deleteRecursively()}
    }
    @Test fun liveLinksAdoptionAndForgetUpdateMembershipsWithoutDeletingThePhysicalCopy() {
        val (context,root)=isolated()
        try{AudioExportStore(context).use{store->
            val first=row(task());store.imported(first)
            val linked=store.link(task(occurrence=2),first);store.imported(linked)
            assertEquals(2,store.sourceFiles(source).size)
            val adopted=store.adopt(task(other),linked)
            assertEquals(listOf(adopted),store.sourceFiles(other))
            assertEquals(listOf(first),store.sourceFiles(source))
            store.forgetFile(first)
            assertTrue(store.sourceFiles(source).isEmpty())
            assertEquals(listOf(adopted),store.aliases(first.uri))
            assertEquals(first.uri,store.savedPage().single().file!!.uri)
        }}finally{root.deleteRecursively()}
    }
}
