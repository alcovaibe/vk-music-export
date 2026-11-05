package app.alcovaibe.vkmusicexport.music

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

internal data class ExportFailure(val task: AudioExportTask,val error: MusicError,val details: ExportFailureDetails?=null)
internal data class ExportSnapshot(val status: String="EMPTY",val total: Int=0,val done: Int=0,val pending: Int=0,
    val failed: Int=0,val sourceIssues: Int=0,val error: MusicError?=null,val recent: List<PublishedAudio> = emptyList(),
    val covered: Int=0,val progress: AudioProgress?=null,val currentTask: AudioExportTask?=null,
    val account: Long=0,val queueId: String="",val revision: Long=0) {
    val unfinished get()=pending>0
}

/** SQLite checkpoints each item. Only canonical track/folder metadata and local media URIs; no VK secrets. IO only. */
internal class AudioExportStore(context: Context,private val queueQuery: ((String)->Unit)?=null): SQLiteOpenHelper(context,File(context.noBackupFilesDir,"audio-export.db").path,null,6) {
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
        TrackMetadataCheckpoints.create(db)
        SavedFileIndex.create(db)
        QueueReadSchema.create(db)
    }
    private fun createDirectories(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE directories(identity TEXT PRIMARY KEY,relative_path TEXT NOT NULL,comparison_key TEXT NOT NULL UNIQUE)")
    }
    private fun createSourceFailures(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE source_failures(source_key TEXT PRIMARY KEY,payload TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase,oldVersion: Int,newVersion: Int) {
        check(oldVersion in 1..5&&newVersion==6){"Unsupported export schema"}
        if(oldVersion<2)createDirectories(db)
        if(oldVersion<3)createSourceFailures(db)
        if(oldVersion<4)TrackMetadataCheckpoints.create(db) // Preserve tasks/files and old checkpoints.
        if(oldVersion<5) {
        SavedFileIndex.create(db)
        db.rawQuery("SELECT task_key,payload FROM files ORDER BY rowid",null).use{c->
            while(c.moveToNext()) {
                val row=json.decodeFromString<PublishedAudio>(c.getString(1))
                db.execSQL("UPDATE files SET uri=? WHERE task_key=?",arrayOf(row.uri,c.getString(0)))
            }
        }
        db.rawQuery("SELECT uri FROM files GROUP BY uri ORDER BY MIN(rowid)",null).use{c->
            while(c.moveToNext())SavedFileIndex(db).sync(c.getString(0))
        }
        }
        QueueReadSchema.create(db)
    }
    private fun put(name: String,value: String){writableDatabase.execSQL("INSERT OR REPLACE INTO meta(name,value) VALUES(?,?)",arrayOf(name,value))}
    private fun get(name: String)=readableDatabase.rawQuery("SELECT value FROM meta WHERE name=?",arrayOf(name)).use{if(it.moveToFirst())it.getString(0)else null}
    private var savedChanged=false
    private inline fun <T> transaction(block:()->T): T {
        val db=writableDatabase;val outer=!db.inTransaction();if(outer)savedChanged=false
        db.beginTransaction();var committed=false;var failure: Throwable?=null
        try{val result=block();db.setTransactionSuccessful();committed=true;return result}
        catch(e: Throwable){failure=e;throw e}
        finally{
            try{db.endTransaction()}catch(cleanup: Throwable){if(failure!=null)failure.addSuppressed(cleanup)else throw cleanup}
            if(outer&&committed&&savedChanged)SavedLibraryChanges.changed()
        }
    }
    private fun syncFiles(vararg uris: String) {
        uris.distinct().forEach{SavedFileIndex(writableDatabase).sync(it)};savedChanged=true
    }
    private fun writeFile(row: PublishedAudio) {
        val old=existing(row.taskKey)
        writableDatabase.execSQL("INSERT OR REPLACE INTO files(task_key,track_id,payload,uri) VALUES(?,?,?,?)",
            arrayOf(row.taskKey,row.track.id,json.encodeToString(row),row.uri))
        syncFiles(*listOfNotNull(old?.uri,row.uri).toTypedArray())
    }
    @Synchronized fun savedRevision()=SavedFileIndex(readableDatabase).revision()
    @Synchronized fun savedPage(before: Long=Long.MAX_VALUE,limit: Int=100)=SavedFileIndex(readableDatabase).page(before,limit)
    @Synchronized fun savedChanges(after: Long,limit: Int=100)=SavedFileIndex(readableDatabase).changes(after,limit)
    @Synchronized fun aliases(uri: String): List<PublishedAudio> = readableDatabase.rawQuery(
        "SELECT payload FROM files WHERE uri=? ORDER BY rowid",arrayOf(uri)).use{c->buildList{
            while(c.moveToNext())add(json.decodeFromString<PublishedAudio>(c.getString(0)))
        }}
    private fun recordedDirectory(identity: String)=readableDatabase.rawQuery(
        "SELECT relative_path FROM directories WHERE identity=?",arrayOf(identity)).use{
        if(it.moveToFirst())it.getString(0)else null
    }
    private fun directoryReserved(path: String)=readableDatabase.rawQuery(
        "SELECT 1 FROM directories WHERE comparison_key=?",arrayOf(path.lowercase(Locale.ROOT))).use{it.moveToFirst()}
    private fun recordDirectory(identity: String,path: String,replace: Boolean=false) {
        writableDatabase.execSQL(if(replace)
            "UPDATE directories SET relative_path=?,comparison_key=? WHERE identity=?"
            else "INSERT INTO directories(identity,relative_path,comparison_key) VALUES(?,?,?)",
            if(replace)arrayOf(path,path.lowercase(Locale.ROOT),identity)
            else arrayOf(identity,path,path.lowercase(Locale.ROOT)))
    }
    /**
     * Friend display roots are reserved by account+friend VK ID, not by names.
     * Two friends named alike get separate stable roots; each friend's playlists
     * share their own root. The ID itself remains in the private SQLite identity.
     * Legacy paths are not silently moved: their saved media URIs remain intact.
     */
    private fun friendDirectory(task: AudioExportTask): String? {
        val source=task.source?:return null
        val friendId=ExportLayout.friendId(source)?:return null
        if(task.folders.firstOrNull()!="Друзья"||task.folders.size !in 2..3)return null
        val display=ExportLayout.displayFolders(task)
        val rootIdentity="friend:v1:${source.account}:${friendId}"
        var root=recordedDirectory(rootIdentity)
        if(root==null) {
            val base=display[1]
            var duplicate=1
            do {
                val label=if(duplicate==1)base else "${ExportLayout.component(base,150)} ($duplicate)"
                root=ExportLayout.ROOT+"Друзья/"+label+"/"
                duplicate++
                musicCheck(duplicate<=1_000_000,MusicError.STORAGE)
            }while(directoryReserved(root!!))
            recordDirectory(rootIdentity,root!!)
        }
        if(task.folders.size==2)return root
        val identity="source:v2:"+source.key
        val old=recordedDirectory(identity)
        if(old!=null&&old.startsWith(root!!))return old
        val name=display[2]
        var duplicate=1
        var candidate: String
        do {
            val label=if(duplicate==1)name else "${ExportLayout.component(name,150)} ($duplicate)"
            candidate=root+label+"/"
            duplicate++
            musicCheck(duplicate<=1_000_000,MusicError.STORAGE)
        }while(directoryReserved(candidate))
        recordDirectory(identity,candidate,replace=old!=null)
        return candidate
    }
    /** Source identity stays private; equal visible titles get stable, short folder suffixes. */
    @Synchronized fun directory(task: AudioExportTask): String=transaction {
        friendDirectory(task)?.let{return@transaction it}
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
        put("queue_identity",java.util.UUID.randomUUID().toString())
        writableDatabase.execSQL("DELETE FROM meta WHERE name GLOB 'failure_detail:*'")
        writableDatabase.delete("source_failures",null,null)
        for(task in tasks)writableDatabase.insertOrThrow("tasks",null,ContentValues().apply{
            put("task_key",task.key);put("payload",json.encodeToString(task));put("state","PENDING")
        })
        sources.forEach{sourceFailure(it)}
        put("queue_account",account.toString())
        put("covered_count","0");put("status",if(tasks.isEmpty()){
            if(sourceIssues>0||sources.isNotEmpty())"FINISHED_WITH_ERRORS" else "FINISHED"
        } else "READY");put("source_issues",sourceIssues.toString());put("error","")
    }
    @Synchronized fun snapshot(): ExportSnapshot=transaction {QueueReadIndex(readableDatabase,queueQuery).snapshot()}
    @Synchronized fun queuePage(tab: DownloadsTab,offset: Int=0,limit: Int=50): ExportQueuePage=transaction {
        QueueReadIndex(readableDatabase,queueQuery).page(tab,offset,limit)
    }
    @Synchronized fun forEachReportRow(consume:(ExportQueueRow)->Unit)=QueueReadIndex(readableDatabase,queueQuery).forEachReportRow(consume)
    @Synchronized fun forEachReportSource(consume:(ExportSourceFailure)->Unit)=QueueReadIndex(readableDatabase,queueQuery).forEachSource(consume)
    @Synchronized fun taskStates(): Map<String,String> = readableDatabase.rawQuery("SELECT task_key,state FROM tasks",null).use{c->
        buildMap{while(c.moveToNext())put(c.getString(0),c.getString(1))}
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
    @Synchronized fun sourceFailure(value: ExportSourceFailure)=transaction {
        val key=value.source.key;val payload=json.encodeToString(value)
        val changed=writableDatabase.update("source_failures",ContentValues().apply{put("payload",payload)},"source_key=?",arrayOf(key))
        if(changed==0)writableDatabase.execSQL("INSERT INTO source_failures(source_key,payload) VALUES(?,?)",arrayOf(key,payload))
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
    @Synchronized fun metadataRun(owner: Long): TrackMetadataRun?=TrackMetadataCheckpoints(writableDatabase).load(owner)
    @Synchronized fun metadataRunState(owner: Long): TrackMetadataState?=TrackMetadataCheckpoints(writableDatabase).state(owner)
    @Synchronized fun metadataRun(run: TrackMetadataRun){TrackMetadataCheckpoints(writableDatabase).save(run)}
    @Synchronized fun metadataProgress(before: TrackMetadataRun,after: TrackMetadataRun){TrackMetadataCheckpoints(writableDatabase).advance(before,after)}
    private fun metadataChoiceKey(owner: Long,trackId: String,uri: String)=
        "track-metadata-choice:$owner:"+java.security.MessageDigest.getInstance("SHA-256").digest((trackId+"\u0000"+uri).toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
    @Synchronized fun metadataReplacement(owner: Long,row: PublishedAudio): TrackMetadataReplacement? = get(metadataChoiceKey(owner,row.track.id,row.uri))?.let{
        json.decodeFromString<TrackMetadataReplacement>(it).also{choice->musicCheck(choice.owner==owner&&choice.trackId==row.track.id&&choice.uri==row.uri,MusicError.STORAGE)}
    }
    @Synchronized fun metadataReplacement(choice: TrackMetadataReplacement) {
        musicCheck(choice.owner>0&&Regex("-?[1-9][0-9]*_[1-9][0-9]*").matches(choice.referenceId),MusicError.STORAGE)
        put(metadataChoiceKey(choice.owner,choice.trackId,choice.uri),json.encodeToString(choice))
    }
    @Synchronized fun metadataTarget(target: TrackMetadataTarget): PublishedAudio? {
        existing(target.taskKey)?.takeIf{it.track.id==target.trackId&&it.uri==target.uri}?.let{return it}
        return readableDatabase.rawQuery("SELECT payload FROM files WHERE track_id=? ORDER BY rowid",arrayOf(target.trackId)).use{c->
            while(c.moveToNext()) {
                val row=json.decodeFromString<PublishedAudio>(c.getString(0))
                if(row.uri==target.uri)return@use row
            };null
        }
    }
    @Synchronized fun checkpoints(prefix: String): List<Pair<String,String>> = readableDatabase.rawQuery(
        "SELECT name,value FROM meta WHERE name GLOB ? ORDER BY rowid",arrayOf(prefix+"*")).use{c->buildList{
            while(c.moveToNext())add(c.getString(0) to c.getString(1))
        }}
    @Synchronized fun removeCheckpoint(name: String){writableDatabase.delete("meta","name=?",arrayOf(name))}
    @Synchronized fun status(status: String,error: MusicError?=null)=transaction {put("status",status);put("error",error?.name?:"")}
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
        val knownUri=readableDatabase.rawQuery("SELECT 1 FROM files WHERE uri=? LIMIT 1",arrayOf(record.uri)).use{it.moveToFirst()}
        val wasDone=readableDatabase.rawQuery("SELECT state FROM tasks WHERE task_key=?",arrayOf(task.key)).use{it.moveToFirst()&&it.getString(0)=="DONE"}
        val delta=(if(record.coverEmbedded)1 else 0)-(if(wasDone&&previous?.coverEmbedded==true)1 else 0)
        put("covered_count",((get("covered_count")?.toIntOrNull()?:0)+delta).toString())
        val recent=recentFiles()
        writeFile(record)
        writableDatabase.execSQL("UPDATE tasks SET state='DONE',error=NULL,prepared=NULL WHERE task_key=?",arrayOf(task.key))
        writableDatabase.delete("meta","name=?",arrayOf("failure_detail:"+task.key))
        // Reusing an existing file completes the queue item without reviving cleared history.
        if(previous!=record&&!knownUri)put("recent_files",json.encodeToString((listOf(record)+recent.filterNot{it.taskKey==record.taskKey}).distinctBy{it.uri}.take(5)))
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
        val direct=existing(task.key)?.takeIf{it.track.id==task.track.id&&AudioCompleteness.matchesCatalogue(it.durationMs,task.track.duration,it.track.duration)}
        val source=task.source?:return listOfNotNull(direct)
        val path=directory(task)
        return (listOfNotNull(direct)+readableDatabase.rawQuery("SELECT payload FROM files WHERE track_id=? ORDER BY rowid",arrayOf(task.track.id)).use{c->buildList{
            while(c.moveToNext()) {
                val row=json.decodeFromString<PublishedAudio>(c.getString(0))
                if(!AudioCompleteness.matchesCatalogue(row.durationMs,task.track.duration,row.track.duration))continue
                if(row.track.duration!=task.track.duration&&task.track.duration>0)continue
                if(row.source==source&&row.occurrence==task.occurrence||row.source==null&&row.relativePath==path)add(row)
            }
        }}).distinctBy{it.taskKey}
    }
    @Synchronized fun adopt(task: AudioExportTask,before: PublishedAudio,expectedTarget: PublishedAudio?=null): PublishedAudio=transaction {
        musicCheck(existing(before.taskKey)==before&&before.track.id==task.track.id,MusicError.INCOMPLETE)
        val after=before.copy(taskKey=task.key,source=task.source,occurrence=task.occurrence)
        if(before==after)return@transaction before
        musicCheck(existing(task.key)==expectedTarget||before.taskKey==task.key,MusicError.STORAGE)
        musicCheck(expectedTarget==null||expectedTarget.track.id==task.track.id,MusicError.STORAGE)
        val wasDone=readableDatabase.rawQuery("SELECT state FROM tasks WHERE task_key=?",arrayOf(task.key)).use{it.moveToFirst()&&it.getString(0)=="DONE"}
        if(wasDone)put("covered_count",((get("covered_count")?.toIntOrNull()?:0)+
            (if(after.coverEmbedded)1 else 0)-(if(expectedTarget?.coverEmbedded==true)1 else 0)).coerceAtLeast(0).toString())
        val recent=recentFiles()
        writableDatabase.delete("files","task_key=?",arrayOf(before.taskKey))
        writeFile(after)
        syncFiles(before.uri)
        put("recent_files",json.encodeToString(recent.map{if(it.taskKey==before.taskKey||it.taskKey==task.key)after else it}.distinctBy{it.taskKey}))
        after
    }
    @Synchronized fun imported(record: PublishedAudio)=transaction {
        val previous=existing(record.taskKey)
        val recent=recentFiles()
        writeFile(record)
        if(previous!=record)put("recent_files",json.encodeToString((listOf(record)+recent.filterNot{it.taskKey==record.taskKey}).take(5)))
    }
    @Synchronized fun copies(track: NativeTrack): List<PublishedAudio> = readableDatabase.rawQuery("SELECT payload FROM files WHERE track_id=? ORDER BY rowid DESC",arrayOf(track.id)).use{c->buildList{
        while(c.moveToNext()){val row=json.decodeFromString<PublishedAudio>(c.getString(0))
            if(AudioCompleteness.matchesCatalogue(row.durationMs,track.duration,row.track.duration))add(row)}
    }}
    /** A source/position is a reference, not ownership of a separate physical file. */
    @Synchronized fun link(task: AudioExportTask,before: PublishedAudio): PublishedAudio=transaction {
        musicCheck(existing(before.taskKey)==before&&before.track.id==task.track.id&&
            AudioCompleteness.matchesCatalogue(before.durationMs,task.track.duration,before.track.duration),MusicError.INCOMPLETE)
        // done() atomically records this reference and its queue counters. A crash here
        // leaves the original record available for the next attempt, with no file writes.
        before.copy(taskKey=task.key,track=if(task.track.metadataError!=null)before.track else if(task.track.duration>0)task.track else task.track.copy(duration=before.track.duration),
            source=task.source,occurrence=task.occurrence)
    }
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
        updateReferences(before,after)
    }
    /** Exact saved ID/URI only; source memberships and cleared display history are retained. */
    @Synchronized fun metadataUpdated(before: PublishedAudio,after: PublishedAudio)=transaction {
        musicCheck(before.uri==after.uri&&before.taskKey==after.taskKey&&before.track.id==after.track.id&&
            before.track.duration==after.track.duration&&before.track.artist==after.track.artist&&
            before.extension==after.extension&&before.mime==after.mime&&before.durationMs==after.durationMs&&
            before.relativePath==after.relativePath,MusicError.STORAGE)
        updateReferences(before,after,metadata=true)
    }
    /** Compare-and-swap a location or explicitly confirmed replacement. Never changes queue states. */
    @Synchronized fun relinked(before: PublishedAudio,after: PublishedAudio)=transaction {
        musicCheck(before.taskKey==after.taskKey&&before.track==after.track,MusicError.STORAGE)
        musicCheck(canShareLocation(before,after),MusicError.STORAGE)
        updateReferences(before,after)
    }
    private fun sameFile(a: PublishedAudio,b: PublishedAudio)=a.track.id==b.track.id&&a.bytes==b.bytes&&
        a.sha256==b.sha256&&a.durationMs==b.durationMs&&a.extension==b.extension&&a.mime==b.mime
    @Synchronized fun canShareLocation(before: PublishedAudio,after: PublishedAudio)=
        (if(after.uri==before.uri)emptyList()else aliases(after.uri)).all{sameFile(it,after)}
    /** Moving or tagging one physical file must update every membership in one transaction. */
    private fun updateReferences(before: PublishedAudio,after: PublishedAudio,metadata: Boolean=false) {
        if(existing(before.taskKey)==after)return
        musicCheck(existing(before.taskKey)==before,MusicError.STORAGE)
        val aliases=aliases(before.uri)
        musicCheck(aliases.all{sameFile(it,before)&&AudioCompleteness.matchesCatalogue(after.durationMs,it.track.duration,it.track.duration)},MusicError.INCOMPLETE)
        fun changedTrack(track: NativeTrack)=if(metadata)track.copy(title=after.track.title,subtitle=after.track.subtitle)else track
        fun changed(row: PublishedAudio)=after.copy(taskKey=row.taskKey,track=changedTrack(row.track),source=row.source,occurrence=row.occurrence,
            catalogueVerified=row.catalogueVerified&&after.catalogueVerified)
        val recent=recentFiles()
        val doneKeys=readableDatabase.rawQuery("SELECT t.task_key FROM tasks t JOIN files f ON f.task_key=t.task_key WHERE f.uri=? AND t.state='DONE'",arrayOf(before.uri)).use{c->buildSet{while(c.moveToNext())add(c.getString(0))}}
        val delta: Int=aliases.filter{it.taskKey in doneKeys}.sumOf{(if(after.coverEmbedded)1 else 0)-(if(it.coverEmbedded)1 else 0)}
        put("covered_count",((get("covered_count")?.toIntOrNull()?:0)+delta).coerceAtLeast(0).toString())
        for(alias in aliases) {
            writableDatabase.execSQL("UPDATE files SET payload=?,uri=? WHERE task_key=?",arrayOf(json.encodeToString(changed(alias)),after.uri,alias.taskKey))
            val prepared=readableDatabase.rawQuery("SELECT prepared FROM tasks WHERE task_key=?",arrayOf(alias.taskKey)).use{c->
                if(c.moveToFirst()&&!c.isNull(0))json.decodeFromString<PublishedAudio>(c.getString(0))else null
            }
            if(prepared?.uri==before.uri)writableDatabase.execSQL("UPDATE tasks SET prepared=? WHERE task_key=?",
                arrayOf(json.encodeToString(changed(prepared)),alias.taskKey))
            if(metadata) {
                val task=readableDatabase.rawQuery("SELECT payload FROM tasks WHERE task_key=?",arrayOf(alias.taskKey)).use{c->
                    if(c.moveToFirst())json.decodeFromString<AudioExportTask>(c.getString(0))else null
                }
                if(task!=null&&task.track.id==before.track.id)writableDatabase.execSQL("UPDATE tasks SET payload=? WHERE task_key=?",
                    arrayOf(json.encodeToString(task.copy(track=changedTrack(task.track))),alias.taskKey))
            }
        }
        syncFiles(before.uri,after.uri)
        if(before.uri!=after.uri)SavedFileIndex(writableDatabase).inheritOrder(before.uri,after.uri)
        put("recent_files",json.encodeToString(recent.map{if(it.uri==before.uri)changed(it)else it}.distinctBy{it.uri}))
        if(before.relativePath+before.displayName!=after.relativePath+after.displayName||metadata) {
            val move=PlaylistRelocation(before.relativePath+before.displayName,after.relativePath+after.displayName,
                after.durationMs,aliases.mapNotNull{it.source?.key}.distinct(),
                if(metadata)after.track.artist+" - "+after.track.displayTitle else null)
            put("m3u-relocation:"+java.util.UUID.randomUUID(),json.encodeToString(move))
        }
    }
    /** Forget only this stale index entry. Never delete a MediaStore file, VK track or queue item. */
    @Synchronized fun forgetFile(before: PublishedAudio)=transaction {
        musicCheck(existing(before.taskKey)==before,MusicError.STORAGE)
        val recent=recentFiles()
        val done=readableDatabase.rawQuery("SELECT state FROM tasks WHERE task_key=?",arrayOf(before.taskKey)).use{it.moveToFirst()&&it.getString(0)=="DONE"}
        if(done&&before.coverEmbedded)put("covered_count",((get("covered_count")?.toIntOrNull()?:0)-1).coerceAtLeast(0).toString())
        writableDatabase.delete("files","task_key=?",arrayOf(before.taskKey))
        syncFiles(before.uri)
        writableDatabase.execSQL("UPDATE tasks SET prepared=NULL WHERE task_key=?",arrayOf(before.taskKey))
        put("recent_files",json.encodeToString(recent.filterNot{it.taskKey==before.taskKey}))
    }
    @Synchronized fun failed(task: AudioExportTask,error: MusicError,details: ExportFailureDetails?=null)=transaction {
        val wasDone=readableDatabase.rawQuery("SELECT state FROM tasks WHERE task_key=?",arrayOf(task.key)).use{it.moveToFirst()&&it.getString(0)=="DONE"}
        if(wasDone&&existing(task.key)?.coverEmbedded==true)put("covered_count",((get("covered_count")?.toIntOrNull()?:0)-1).coerceAtLeast(0).toString())
        writableDatabase.execSQL("UPDATE tasks SET state='FAILED',error=? WHERE task_key=?",arrayOf(error.name,task.key))
        if(details!=null)put("failure_detail:"+task.key,json.encodeToString(details))
        else writableDatabase.delete("meta","name=?",arrayOf("failure_detail:"+task.key))
    }
    @Synchronized fun releaseRunning(){writableDatabase.execSQL("UPDATE tasks SET state='PENDING' WHERE state='RUNNING'")}
    @Synchronized fun unavailableTracks(): List<NativeTrack> = readableDatabase.rawQuery(
        "SELECT payload FROM tasks WHERE state='FAILED' AND error IN ('UNAVAILABLE','DENIED') ORDER BY ordinal",null).use{c->buildList{
            while(c.moveToNext())add(json.decodeFromString<AudioExportTask>(c.getString(0)).track)
        }}.distinctBy{it.id}
    @Synchronized fun retryFailed(){writableDatabase.execSQL("UPDATE tasks SET state='PENDING',error=NULL WHERE state='FAILED'")}
    @Synchronized fun retryMissing(task: AudioExportTask)=transaction {
        failed(task,MusicError.INCOMPLETE)
        writableDatabase.execSQL("UPDATE tasks SET state='PENDING',error=NULL WHERE task_key=?",arrayOf(task.key))
    }
    @Synchronized fun clearQueue()=transaction {
        writableDatabase.delete("tasks",null,null);writableDatabase.delete("source_failures",null,null)
        put("queue_identity",java.util.UUID.randomUUID().toString())
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
