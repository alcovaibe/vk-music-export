package app.alcovaibe.vkmusicexport.music

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.Json
import java.util.UUID

internal enum class DownloadsTab(val title: String) { QUEUE("Очередь"),ERRORS("Ошибки"),DONE("Готово") }
internal data class ExportQueueRow(val ordinal: Long,val task: AudioExportTask,val state: String,val error: MusicError?=null,
    val details: ExportFailureDetails?=null,val file: PublishedAudio?=null)
internal data class ExportQueuePage(val queueId: String="",val revision: Long=0,val tab: DownloadsTab=DownloadsTab.QUEUE,
    val offset: Int=0,val total: Int=0,val rows: List<ExportQueueRow> = emptyList(),val sources: List<ExportSourceFailure> = emptyList()) {
    val failures get()=rows.mapNotNull{row->row.error?.let{ExportFailure(row.task,it,row.details)}}
}
/** Counters and invalidation are part of the same SQLite commit as task/source changes. */
internal object QueueReadSchema {
    fun create(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE queue_counts(state TEXT PRIMARY KEY,total INTEGER NOT NULL CHECK(total>=0))")
        db.execSQL("CREATE INDEX tasks_queue_order ON tasks(ordinal) WHERE state IN ('PENDING','RUNNING')")
        db.execSQL("INSERT INTO queue_counts SELECT state,COUNT(*) FROM tasks GROUP BY state")
        db.execSQL("INSERT OR IGNORE INTO meta(name,value) VALUES('queue_revision','0')")
        db.execSQL("INSERT OR IGNORE INTO meta(name,value) VALUES('queue_identity',?)",arrayOf(UUID.randomUUID().toString()))
        db.execSQL("INSERT OR REPLACE INTO meta(name,value) SELECT 'source_failure_count',CAST(COUNT(*) AS TEXT) FROM source_failures")
        val revision="UPDATE meta SET value=CAST(value AS INTEGER)+1 WHERE name='queue_revision';"
        db.execSQL("CREATE TRIGGER queue_task_insert AFTER INSERT ON tasks BEGIN INSERT OR IGNORE INTO queue_counts VALUES(NEW.state,0); UPDATE queue_counts SET total=total+1 WHERE state=NEW.state; $revision END")
        db.execSQL("CREATE TRIGGER queue_task_delete AFTER DELETE ON tasks BEGIN UPDATE queue_counts SET total=total-1 WHERE state=OLD.state; $revision END")
        db.execSQL("CREATE TRIGGER queue_task_update AFTER UPDATE ON tasks BEGIN UPDATE queue_counts SET total=total-1 WHERE state=OLD.state; INSERT OR IGNORE INTO queue_counts VALUES(NEW.state,0); UPDATE queue_counts SET total=total+1 WHERE state=NEW.state; $revision END")
        // Source upserts use UPDATE/INSERT; only a new source key increments the counter.
        db.execSQL("CREATE TRIGGER queue_source_insert BEFORE INSERT ON source_failures WHEN NOT EXISTS(SELECT 1 FROM source_failures WHERE source_key=NEW.source_key) BEGIN UPDATE meta SET value=CAST(value AS INTEGER)+1 WHERE name='source_failure_count'; END")
        db.execSQL("CREATE TRIGGER queue_source_changed AFTER INSERT ON source_failures BEGIN $revision END")
        db.execSQL("CREATE TRIGGER queue_source_update AFTER UPDATE ON source_failures BEGIN $revision END")
        db.execSQL("CREATE TRIGGER queue_source_delete AFTER DELETE ON source_failures BEGIN UPDATE meta SET value=CAST(value AS INTEGER)-1 WHERE name='source_failure_count'; $revision END")
        db.execSQL("CREATE TRIGGER queue_file_insert AFTER INSERT ON files WHEN EXISTS(SELECT 1 FROM tasks WHERE task_key=NEW.task_key) BEGIN $revision END")
        db.execSQL("CREATE TRIGGER queue_file_update AFTER UPDATE ON files WHEN EXISTS(SELECT 1 FROM tasks WHERE task_key=NEW.task_key OR task_key=OLD.task_key) BEGIN $revision END")
        db.execSQL("CREATE TRIGGER queue_file_delete AFTER DELETE ON files WHEN EXISTS(SELECT 1 FROM tasks WHERE task_key=OLD.task_key) BEGIN $revision END")
    }
}
/** Bounded UI reads. Full export deliberately uses a separate joined cursor. Caller owns the transaction. */
internal class QueueReadIndex(private val db: SQLiteDatabase,private val onQuery: ((String)->Unit)?=null) {
    private val json=Json{encodeDefaults=true}
    private fun read(sql: String,args: Array<String>?=null): android.database.Cursor {onQuery?.invoke(sql);return db.rawQuery(sql,args)}
    private fun metadata()=read("SELECT name,value FROM meta WHERE name IN ('status','error','recent_files','source_issues','queue_account','covered_count','queue_revision','queue_identity','source_failure_count')").use{c->buildMap{while(c.moveToNext())put(c.getString(0),c.getString(1))}}
    private fun error(raw: String?)=runCatching{MusicError.valueOf(raw.orEmpty())}.getOrNull()
    fun snapshot(): ExportSnapshot {
        val meta=metadata()
        val counts=read("SELECT state,total FROM queue_counts").use{c->buildMap{while(c.moveToNext())put(c.getString(0),c.getInt(1))}}
        val recent=meta["recent_files"]?.let{json.decodeFromString<List<PublishedAudio>>(it)}?:read("SELECT payload FROM files ORDER BY rowid DESC LIMIT 5").use{c->buildList{while(c.moveToNext())add(json.decodeFromString<PublishedAudio>(c.getString(0)))}}
        val current=read("SELECT payload FROM tasks WHERE state='RUNNING' ORDER BY ordinal LIMIT 1").use{if(it.moveToFirst())json.decodeFromString<AudioExportTask>(it.getString(0))else null}
        val done=counts["DONE"]?:0
        return ExportSnapshot(status=meta["status"]?:"EMPTY",total=counts.values.sum(),done=done,
            pending=(counts["PENDING"]?:0)+(counts["RUNNING"]?:0),failed=counts["FAILED"]?:0,
            sourceIssues=(meta["source_issues"]?.toIntOrNull()?:0)+(meta["source_failure_count"]?.toIntOrNull()?:0),
            error=error(meta["error"]),recent=recent,covered=(meta["covered_count"]?.toIntOrNull()?:0).coerceIn(0,done),currentTask=current,
            account=meta["queue_account"]?.toLongOrNull()?:0,queueId=meta["queue_identity"].orEmpty(),revision=meta["queue_revision"]?.toLongOrNull()?:0)
    }
    private fun joined(includeFile: Boolean)=
        "SELECT t.ordinal,t.payload,t.state,t.error,m.value,"+(if(includeFile)"f.payload" else "NULL")+
        " FROM tasks t LEFT JOIN meta m ON m.name='failure_detail:'||t.task_key"+
        (if(includeFile)" LEFT JOIN files f ON f.task_key=t.task_key" else "")
    private fun row(c: android.database.Cursor)=ExportQueueRow(c.getLong(0),json.decodeFromString(c.getString(1)),c.getString(2),if(c.getString(2)=="FAILED")(error(c.getString(3))?:MusicError.RESPONSE)else null,
        if(c.getString(2)!="FAILED"||c.isNull(4))null else runCatching{json.decodeFromString<ExportFailureDetails>(c.getString(4))}.getOrNull(),
        if(c.isNull(5))null else json.decodeFromString<PublishedAudio>(c.getString(5)))
    fun page(tab: DownloadsTab,requestedOffset: Int,limit: Int=50): ExportQueuePage {
        require(requestedOffset>=0&&limit in 1..100)
        val snapshot=snapshot()
        val meta=if(tab==DownloadsTab.ERRORS)metadata()else emptyMap()
        val sourceCount=meta["source_failure_count"]?.toIntOrNull()?:0
        val total=when(tab){DownloadsTab.QUEUE->snapshot.pending;DownloadsTab.ERRORS->snapshot.failed+sourceCount;DownloadsTab.DONE->snapshot.done}
        val offset=requestedOffset.coerceAtMost(((total-1).coerceAtLeast(0)/limit)*limit)
        val sources=if(tab==DownloadsTab.ERRORS&&offset<sourceCount)read("SELECT payload FROM source_failures ORDER BY rowid LIMIT ? OFFSET ?",arrayOf(minOf(limit,sourceCount-offset).toString(),offset.toString())).use{c->buildList{while(c.moveToNext())add(json.decodeFromString<ExportSourceFailure>(c.getString(0)))}}else emptyList()
        val state=when(tab){DownloadsTab.QUEUE->"t.state IN ('PENDING','RUNNING')";DownloadsTab.ERRORS->"t.state='FAILED'";DownloadsTab.DONE->"t.state='DONE'"}
        val taskOffset=if(tab==DownloadsTab.ERRORS)(offset-sourceCount).coerceAtLeast(0)else offset
        val joined=joined(tab==DownloadsTab.DONE)
        val query=if(tab==DownloadsTab.QUEUE)joined.replace("FROM tasks t ","FROM tasks t INDEXED BY tasks_queue_order ")else joined
        val rows=if(sources.size==limit)emptyList()else read("$query WHERE $state ORDER BY t.ordinal LIMIT ? OFFSET ?",arrayOf((limit-sources.size).toString(),taskOffset.toString())).use{c->buildList{while(c.moveToNext())add(row(c))}}
        return ExportQueuePage(snapshot.queueId,snapshot.revision,tab,offset,total,rows,sources)
    }
    fun forEachReportRow(consume:(ExportQueueRow)->Unit) {read("${joined(true)} ORDER BY t.ordinal").use{c->while(c.moveToNext())consume(row(c))}}
    fun forEachSource(consume:(ExportSourceFailure)->Unit) {read("SELECT payload FROM source_failures ORDER BY rowid").use{c->while(c.moveToNext())consume(json.decodeFromString(c.getString(0)))}}
}
