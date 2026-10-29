package app.alcovaibe.vkmusicexport.music

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

internal data class ExportFailure(val task: AudioExportTask,val error: MusicError)
internal data class ExportSnapshot(val status: String="EMPTY",val total: Int=0,val done: Int=0,val pending: Int=0,
    val failed: Int=0,val sourceIssues: Int=0,val error: MusicError?=null,val recent: List<PublishedAudio> = emptyList(),
    val covered: Int=0,val failures: List<ExportFailure> = emptyList(),val progress: AudioProgress?=null,val currentTask: AudioExportTask?=null,
    val account: Long=0,val sourceFailures: List<ExportSourceFailure> = emptyList()) {
    val unfinished get()=pending>0
}

/** SQLite checkpoints each item. Only canonical track/folder metadata and local media URIs; no VK secrets. IO only. */
internal class AudioExportStore(context: Context): SQLiteOpenHelper(context,File(context.noBackupFilesDir,"audio-export.db").path,null,3) {
    private val json=Json{encodeDefaults=true}
    init{setWriteAheadLoggingEnabled(true)}
    override fun onConfigure(db: SQLiteDatabase){db.execSQL("PRAGMA synchronous=FULL")}
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE tasks(ordinal INTEGER PRIMARY KEY AUTOINCREMENT, task_key TEXT UNIQUE NOT NULL, payload TEXT NOT NULL, state TEXT NOT NULL, error TEXT, prepared TEXT)")
        db.execSQL("CREATE INDEX task_states ON tasks(state,ordinal)")
        db.execSQL("CREATE TABLE files(task_key TEXT PRIMARY KEY, track_id TEXT NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE INDEX file_tracks ON files(track_id)")
        db.execSQL("CREATE TABLE meta(name TEXT PRIMARY KEY,value TEXT NOT NULL)")
        createDirectories(db)
        createSourceFailures(db)
    }
    private fun createDirectories(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE directories(identity TEXT PRIMARY KEY,relative_path TEXT NOT NULL,comparison_key TEXT NOT NULL UNIQUE)")
    }
    private fun createSourceFailures(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE source_failures(source_key TEXT PRIMARY KEY,payload TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase,oldVersion: Int,newVersion: Int) {
        check(oldVersion in 1..2&&newVersion==3){"Unsupported export schema"}
        if(oldVersion<2)createDirectories(db)
        createSourceFailures(db) // Preserve all tasks, files, checkpoints and unknown legacy owner.
    }
    private fun put(name: String,value: String){writableDatabase.execSQL("INSERT OR REPLACE INTO meta(name,value) VALUES(?,?)",arrayOf(name,value))}
    private fun get(name: String)=readableDatabase.rawQuery("SELECT value FROM meta WHERE name=?",arrayOf(name)).use{if(it.moveToFirst())it.getString(0)else null}
    private inline fun <T> transaction(block:()->T): T {
        val db=writableDatabase;db.beginTransaction()
        try{val result=block();db.setTransactionSuccessful();return result}finally{db.endTransaction()}
    }
    /** Source identity stays private; equal visible titles get stable, short folder suffixes. */
    @Synchronized fun directory(task: AudioExportTask): String=transaction {
        val identity=task.source?.let{"source:v2:"+it.key}?:json.encodeToString(task.folders)
        val existing=readableDatabase.rawQuery("SELECT relative_path FROM directories WHERE identity=?",arrayOf(identity)).use{
            if(it.moveToFirst())it.getString(0)else null
        }
        if(existing!=null)existing else {
            val legacy=task.source?.let{source->
                readableDatabase.rawQuery("SELECT identity,relative_path FROM directories",null).use{c->
                    var match: Pair<String,String>?=null
                    while(c.moveToNext()) {
                        val folders=runCatching{json.decodeFromString<List<String>>(c.getString(0))}.getOrNull()?:continue
                        if(ExportLayout.legacySource(folders,source)){match=c.getString(0) to c.getString(1);break}
                    };match
                }
            }
            if(legacy!=null) {
                writableDatabase.execSQL("UPDATE directories SET identity=? WHERE identity=?",arrayOf(identity,legacy.first))
                return@transaction legacy.second
            }
            var duplicate=1
            var relative=ExportLayout.relative(task)
            while(readableDatabase.rawQuery("SELECT 1 FROM directories WHERE comparison_key=?",arrayOf(relative.lowercase(Locale.ROOT))).use{it.moveToFirst()}) {
                duplicate++;musicCheck(duplicate<=1_000_000,MusicError.STORAGE)
                relative=ExportLayout.relative(task,duplicate)
            }
            writableDatabase.execSQL("INSERT INTO directories(identity,relative_path,comparison_key) VALUES(?,?,?)",
                arrayOf(identity,relative,relative.lowercase(Locale.ROOT)))
            relative
        }
    }
    @Synchronized fun replace(tasks: List<AudioExportTask>,sourceIssues: Int=0,account: Long=0,
        sources: List<ExportSourceFailure> = emptyList())=transaction {
        musicCheck(!snapshot().unfinished,MusicError.BUSY)
        musicCheck(account>=0&&tasks.all{it.source==null||it.source.account==account},MusicError.ACCOUNT_CHANGED)
        writableDatabase.delete("tasks",null,null)
        writableDatabase.delete("source_failures",null,null)
        for(task in tasks)writableDatabase.insertOrThrow("tasks",null,ContentValues().apply{
            put("task_key",task.key);put("payload",json.encodeToString(task));put("state","PENDING")
        })
        sources.forEach{sourceFailure(it)}
        put("queue_account",account.toString())
        put("covered_count","0");put("status",if(tasks.isEmpty())"FINISHED" else "READY");put("source_issues",sourceIssues.toString());put("error","")
    }
    @Synchronized fun snapshot(): ExportSnapshot {
        val counts=mutableMapOf<String,Int>()
        readableDatabase.rawQuery("SELECT state,COUNT(*) FROM tasks GROUP BY state",null).use{while(it.moveToNext())counts[it.getString(0)]=it.getInt(1)}
        val recent=recentFiles()
        val failures=readableDatabase.rawQuery("SELECT payload,error FROM tasks WHERE state='FAILED' ORDER BY ordinal",null).use{c->buildList{while(c.moveToNext())add(ExportFailure(json.decodeFromString(c.getString(0)),parseError(c.getString(1))?:MusicError.RESPONSE))}}
        val current=readableDatabase.rawQuery("SELECT payload FROM tasks WHERE state='RUNNING' ORDER BY ordinal LIMIT 1",null).use{
            if(it.moveToFirst())json.decodeFromString<AudioExportTask>(it.getString(0))else null
        }
        return ExportSnapshot(get("status")?:"EMPTY",counts.values.sum(),counts["DONE"]?:0,
            (counts["PENDING"]?:0)+(counts["RUNNING"]?:0),counts["FAILED"]?:0,
            (get("source_issues")?.toIntOrNull()?:0)+sourceFailures().size,parseError(get("error")),recent,
            (get("covered_count")?.toIntOrNull()?:0).coerceIn(0,counts["DONE"]?:0),failures,currentTask=current,
            account=account(),sourceFailures=sourceFailures())
    }
    @Synchronized fun account(): Long=get("queue_account")?.toLongOrNull()?:0
    @Synchronized fun bindAccount(owner: Long)=transaction {
        musicCheck(owner>0&&(account()==0L||account()==owner),MusicError.ACCOUNT_CHANGED)
        musicCheck(!running()&&get("status")!="QUEUED",MusicError.BUSY)
        put("queue_account",owner.toString())
    }
    @Synchronized fun sourceFailures(): List<ExportSourceFailure> = readableDatabase.rawQuery(
        "SELECT payload FROM source_failures ORDER BY rowid",null).use{c->buildList{
            while(c.moveToNext())add(json.decodeFromString<ExportSourceFailure>(c.getString(0)))
        }}
    @Synchronized fun sourceFailure(value: ExportSourceFailure) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO source_failures(source_key,payload) VALUES(?,?)",
            arrayOf(value.source.key,json.encodeToString(value)))
    }
    @Synchronized fun clearSourceFailure(source: ExportSource){writableDatabase.delete("source_failures","source_key=?",arrayOf(source.key))}
    @Synchronized fun retrySource(source: ExportSource,tasks: List<AudioExportTask>)=transaction {
        musicCheck(source.account==account()&&tasks.all{it.source?.account==source.account},MusicError.ACCOUNT_CHANGED)
        musicCheck(!running()&&get("status")!="QUEUED",MusicError.BUSY)
        for(task in tasks) {
            writableDatabase.insertWithOnConflict("tasks",null,ContentValues().apply{
                put("task_key",task.key);put("payload",json.encodeToString(task));put("state","PENDING")
            },SQLiteDatabase.CONFLICT_IGNORE)
            writableDatabase.execSQL("UPDATE tasks SET payload=?,state=CASE WHEN state='DONE' THEN state ELSE 'PENDING' END,error=NULL WHERE task_key=?",
                arrayOf(json.encodeToString(task),task.key))
        }
        writableDatabase.delete("source_failures","source_key=?",arrayOf(source.key))
        status("PAUSED")
    }
    @Synchronized fun taskRows(): List<Pair<AudioExportTask,String>> = readableDatabase.rawQuery(
        "SELECT payload,state FROM tasks ORDER BY ordinal",null).use{c->buildList{
            while(c.moveToNext())add(json.decodeFromString<AudioExportTask>(c.getString(0)) to c.getString(1))
        }}
    @Synchronized fun checkpoint(name: String): String?=get(name)
    @Synchronized fun checkpoint(name: String,value: String){put(name,value)}
    @Synchronized fun status(status: String,error: MusicError?=null){put("status",status);put("error",error?.name?:"")}
    @Synchronized fun beginRun(): Boolean=transaction {if(get("status")!="QUEUED")false else{status("RUNNING");true}}
    @Synchronized fun running()=get("status")=="RUNNING"
    @Synchronized fun recoverItems()=transaction {
        writableDatabase.execSQL("UPDATE tasks SET state='PENDING' WHERE state='RUNNING'")
        val recovered=snapshot()
        if(recovered.unfinished)status("PAUSED",recovered.error)
        else if(recovered.total>0)status(if(recovered.failed>0||recovered.sourceIssues>0)"FINISHED_WITH_ERRORS" else "FINISHED")
    }
    @Synchronized fun claim(): AudioExportTask?=transaction {
        val task=readableDatabase.rawQuery("SELECT payload FROM tasks WHERE state='PENDING' ORDER BY ordinal LIMIT 1",null).use{if(it.moveToFirst())json.decodeFromString<AudioExportTask>(it.getString(0))else null}
        if(task!=null)writableDatabase.execSQL("UPDATE tasks SET state='RUNNING',error=NULL WHERE task_key=?",arrayOf(task.key))
        task
    }
    @Synchronized fun prepared(task: AudioExportTask,record: PublishedAudio){writableDatabase.execSQL("UPDATE tasks SET prepared=? WHERE task_key=?",arrayOf(json.encodeToString(record),task.key))}
    @Synchronized fun prepared(): List<Pair<AudioExportTask,PublishedAudio>> = readableDatabase.rawQuery("SELECT payload,prepared FROM tasks WHERE prepared IS NOT NULL",null).use{c->buildList{
        while(c.moveToNext())add(json.decodeFromString<AudioExportTask>(c.getString(0)) to json.decodeFromString<PublishedAudio>(c.getString(1)))
    }}
    @Synchronized fun clearPrepared(key: String){writableDatabase.execSQL("UPDATE tasks SET prepared=NULL WHERE task_key=?",arrayOf(key))}
    @Synchronized fun done(task: AudioExportTask,record: PublishedAudio)=transaction {
        musicCheck(record.taskKey==task.key,MusicError.STORAGE)
        val previous=existing(task.key)
        val wasDone=readableDatabase.rawQuery("SELECT state FROM tasks WHERE task_key=?",arrayOf(task.key)).use{it.moveToFirst()&&it.getString(0)=="DONE"}
        val delta=(if(record.coverEmbedded)1 else 0)-(if(wasDone&&previous?.coverEmbedded==true)1 else 0)
        put("covered_count",((get("covered_count")?.toIntOrNull()?:0)+delta).toString())
        val recent=recentFiles()
        writableDatabase.execSQL("INSERT OR REPLACE INTO files(task_key,track_id,payload) VALUES(?,?,?)",arrayOf(task.key,task.track.id,json.encodeToString(record)))
        writableDatabase.execSQL("UPDATE tasks SET state='DONE',error=NULL,prepared=NULL WHERE task_key=?",arrayOf(task.key))
        // Reusing an existing file completes the queue item without reviving cleared history.
        if(previous!=record)put("recent_files",json.encodeToString((listOf(record)+recent.filterNot{it.taskKey==record.taskKey}).take(5)))
    }
    private fun recentFiles(): List<PublishedAudio> {
        get("recent_files")?.let{return json.decodeFromString(it)}
        // Existing installations keep their last-five list until the first clear/new publication.
        return readableDatabase.rawQuery("SELECT payload FROM files ORDER BY rowid DESC LIMIT 5",null).use{c->buildList{
            while(c.moveToNext())add(json.decodeFromString<PublishedAudio>(c.getString(0)))
        }}
    }
    /** Display history only. Files, deduplication records, tasks and checkpoints stay intact. */
    @Synchronized fun clearRecentFiles()=transaction {put("recent_files","[]")}
    @Synchronized fun existing(key: String): PublishedAudio?=readableDatabase.rawQuery("SELECT payload FROM files WHERE task_key=?",arrayOf(key)).use{if(it.moveToFirst())json.decodeFromString<PublishedAudio>(it.getString(0))else null}
    /** Reuse the same owned URI when adopting an old position-based identity. No filename guesses. */
    @Synchronized fun reusable(task: AudioExportTask): List<PublishedAudio> {
        existing(task.key)?.let{return listOf(it)}
        val source=task.source?:return emptyList()
        val path=directory(task)
        return readableDatabase.rawQuery("SELECT payload FROM files WHERE track_id=? ORDER BY rowid",arrayOf(task.track.id)).use{c->buildList{
            while(c.moveToNext()) {
                val row=json.decodeFromString<PublishedAudio>(c.getString(0))
                if(row.track.duration!=task.track.duration&&task.track.duration>0)continue
                if(row.source==source&&row.occurrence==task.occurrence||row.source==null&&row.relativePath==path)add(row)
            }
        }}
    }
    @Synchronized fun adopt(task: AudioExportTask,before: PublishedAudio): PublishedAudio=transaction {
        musicCheck(existing(before.taskKey)==before&&before.track.id==task.track.id,MusicError.INCOMPLETE)
        val after=before.copy(taskKey=task.key,source=task.source,occurrence=task.occurrence)
        if(before==after)return@transaction before
        musicCheck(existing(task.key)==null||before.taskKey==task.key,MusicError.STORAGE)
        val recent=recentFiles()
        writableDatabase.delete("files","task_key=?",arrayOf(before.taskKey))
        writableDatabase.execSQL("INSERT INTO files(task_key,track_id,payload) VALUES(?,?,?)",
            arrayOf(task.key,after.track.id,json.encodeToString(after)))
        put("recent_files",json.encodeToString(recent.map{if(it.taskKey==before.taskKey)after else it}))
        after
    }
    @Synchronized fun imported(record: PublishedAudio)=transaction {
        val previous=existing(record.taskKey)
        val recent=recentFiles()
        writableDatabase.execSQL("INSERT OR REPLACE INTO files(task_key,track_id,payload) VALUES(?,?,?)",
            arrayOf(record.taskKey,record.track.id,json.encodeToString(record)))
        if(previous!=record)put("recent_files",json.encodeToString((listOf(record)+recent.filterNot{it.taskKey==record.taskKey}).take(5)))
    }
    @Synchronized fun copies(track: NativeTrack): List<PublishedAudio> = readableDatabase.rawQuery("SELECT payload FROM files WHERE track_id=? ORDER BY rowid DESC LIMIT 8",arrayOf(track.id)).use{c->buildList{
        while(c.moveToNext()){val row=json.decodeFromString<PublishedAudio>(c.getString(0));if(row.track==track)add(row)}
    }}
    @Synchronized fun savedLibraryCopies(track: NativeTrack,owner: Long=0): List<PublishedAudio> = readableDatabase.rawQuery(
        "SELECT payload FROM files WHERE track_id=? ORDER BY rowid DESC",arrayOf(track.id)).use{c->buildList{
        while(c.moveToNext()) {
            val row=json.decodeFromString<PublishedAudio>(c.getString(0))
            if(LibraryRemovalProtocol.libraryCopy(owner,track,row))add(row)
        }
    }}
    @Synchronized fun savedFiles(): List<PublishedAudio> = readableDatabase.rawQuery("SELECT payload FROM files ORDER BY rowid",null).use{c->buildList{
        while(c.moveToNext())add(json.decodeFromString<PublishedAudio>(c.getString(0)))
    }}
    /** Same URI and task identity; update integrity checkpoints without reviving cleared history. */
    @Synchronized fun retagged(before: PublishedAudio,after: PublishedAudio)=transaction {
        musicCheck(before.uri==after.uri&&before.taskKey==after.taskKey&&before.track==after.track,MusicError.STORAGE)
        val existing=existing(before.taskKey)
        if(existing==after)return@transaction
        musicCheck(existing==before,MusicError.STORAGE)
        val done=readableDatabase.rawQuery("SELECT state FROM tasks WHERE task_key=?",arrayOf(before.taskKey)).use{it.moveToFirst()&&it.getString(0)=="DONE"}
        if(done)put("covered_count",((get("covered_count")?.toIntOrNull()?:0)+(if(after.coverEmbedded)1 else 0)-(if(before.coverEmbedded)1 else 0)).toString())
        writableDatabase.execSQL("UPDATE files SET payload=? WHERE task_key=?",arrayOf(json.encodeToString(after),before.taskKey))
        val prepared=readableDatabase.rawQuery("SELECT prepared FROM tasks WHERE task_key=?",arrayOf(before.taskKey)).use{if(it.moveToFirst()&&!it.isNull(0))json.decodeFromString<PublishedAudio>(it.getString(0))else null}
        if(prepared?.uri==before.uri)writableDatabase.execSQL("UPDATE tasks SET prepared=? WHERE task_key=?",arrayOf(json.encodeToString(after),before.taskKey))
        put("recent_files",json.encodeToString(recentFiles().map{if(it.uri==before.uri)after else it}))
    }
    /** Compare-and-swap a location or explicitly confirmed replacement. Never changes queue states. */
    @Synchronized fun relinked(before: PublishedAudio,after: PublishedAudio)=transaction {
        musicCheck(before.taskKey==after.taskKey&&before.track==after.track,MusicError.STORAGE)
        if(existing(before.taskKey)==after)return@transaction
        musicCheck(existing(before.taskKey)==before&&!uriUsed(after.uri,before.taskKey),MusicError.STORAGE)
        val recent=recentFiles()
        val done=readableDatabase.rawQuery("SELECT state FROM tasks WHERE task_key=?",arrayOf(before.taskKey)).use{it.moveToFirst()&&it.getString(0)=="DONE"}
        if(done)put("covered_count",((get("covered_count")?.toIntOrNull()?:0)+(if(after.coverEmbedded)1 else 0)-(if(before.coverEmbedded)1 else 0)).coerceAtLeast(0).toString())
        writableDatabase.execSQL("UPDATE files SET payload=? WHERE task_key=?",arrayOf(json.encodeToString(after),before.taskKey))
        writableDatabase.execSQL("UPDATE tasks SET prepared=NULL WHERE task_key=?",arrayOf(before.taskKey))
        put("recent_files",json.encodeToString(recent.map{if(it.taskKey==before.taskKey)after else it}))
    }
    @Synchronized fun uriUsed(uri: String,exceptKey: String)=savedFiles().any{it.uri==uri&&it.taskKey!=exceptKey}
    /** Forget only this stale index entry. Never delete a MediaStore file, VK track or queue item. */
    @Synchronized fun forgetFile(before: PublishedAudio)=transaction {
        musicCheck(existing(before.taskKey)==before,MusicError.STORAGE)
        val recent=recentFiles()
        val done=readableDatabase.rawQuery("SELECT state FROM tasks WHERE task_key=?",arrayOf(before.taskKey)).use{it.moveToFirst()&&it.getString(0)=="DONE"}
        if(done&&before.coverEmbedded)put("covered_count",((get("covered_count")?.toIntOrNull()?:0)-1).coerceAtLeast(0).toString())
        writableDatabase.delete("files","task_key=?",arrayOf(before.taskKey))
        writableDatabase.execSQL("UPDATE tasks SET prepared=NULL WHERE task_key=?",arrayOf(before.taskKey))
        put("recent_files",json.encodeToString(recent.filterNot{it.taskKey==before.taskKey}))
    }
    @Synchronized fun failed(task: AudioExportTask,error: MusicError){writableDatabase.execSQL("UPDATE tasks SET state='FAILED',error=? WHERE task_key=?",arrayOf(error.name,task.key))}
    @Synchronized fun releaseRunning(){writableDatabase.execSQL("UPDATE tasks SET state='PENDING' WHERE state='RUNNING'")}
    @Synchronized fun unavailableTracks(): List<NativeTrack> = readableDatabase.rawQuery(
        "SELECT payload FROM tasks WHERE state='FAILED' AND error IN ('UNAVAILABLE','DENIED') ORDER BY ordinal",null).use{c->buildList{
            while(c.moveToNext())add(json.decodeFromString<AudioExportTask>(c.getString(0)).track)
        }}.distinctBy{it.id}
    @Synchronized fun retryFailed(){writableDatabase.execSQL("UPDATE tasks SET state='PENDING',error=NULL WHERE state='FAILED'")}
    @Synchronized fun clearQueue()=transaction {
        writableDatabase.delete("tasks",null,null);writableDatabase.delete("source_failures",null,null)
        status("EMPTY");put("source_issues","0");put("covered_count","0");put("queue_account","0")
    }
    @Synchronized fun catalogKinds(account: Long): Map<String,PlaylistKind> {
        musicCheck(account>0,MusicError.STORAGE)
        val prefix="catalog_kind:$account:"
        return readableDatabase.rawQuery("SELECT name,value FROM meta WHERE substr(name,1,?)=?",arrayOf(prefix.length.toString(),prefix)).use{c->buildMap{
            while(c.moveToNext()) {
                val kind=runCatching{PlaylistKind.valueOf(c.getString(1))}.getOrNull()
                if(kind!=null&&kind!=PlaylistKind.UNKNOWN)put(c.getString(0).removePrefix(prefix),kind)
            }
        }}
    }
    @Synchronized fun catalogKind(account: Long,list: NativePlaylist,kind: PlaylistKind)=transaction {
        musicCheck(account>0,MusicError.STORAGE)
        val name="catalog_kind:$account:${list.key}"
        if(kind==PlaylistKind.UNKNOWN)writableDatabase.delete("meta","name=?",arrayOf(name)) else put(name,kind.name)
    }
    private fun parseError(value: String?)=runCatching{MusicError.valueOf(value.orEmpty())}.getOrNull()
}
