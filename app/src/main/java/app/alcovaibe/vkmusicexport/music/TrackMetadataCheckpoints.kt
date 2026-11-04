package app.alcovaibe.vkmusicexport.music

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.io.ByteArrayOutputStream

/** Small durable state; targets and issue payloads never belong in a per-item cursor. */
@Serializable internal data class TrackMetadataProgress(val next: Int,val updated: Int,val current: Int,
    val unavailable: Int,val skipped: Int,val failed: Int,val issueCount: Int,val error: MusicError?) {
    fun state(total: Int,issues: List<TrackMetadataIssue>,visible: Boolean=true,running: Boolean=false)=
        TrackMetadataState(visible=visible,running=running,total=total,processed=next,updated=updated,current=current,
            unavailable=unavailable,skipped=skipped,failed=failed,issues=issues,resumable=next<total,
            message=if(next<total)"Проверка сохранена. Можно продолжить с необработанной записи." else "Проверка завершена. Звук и обложки сохранены.")
    companion object {
        fun of(run: TrackMetadataRun)=TrackMetadataProgress(run.next,run.updated,run.current,run.unavailable,
            run.skipped,run.failed,run.issues.size,run.error)
    }
}

/** IO only. Every snapshot/cursor/issue transition is one SQLite transaction.
 * Full saves validate immutable targets; the append-only batch path serializes only its delta.
 */
internal class TrackMetadataCheckpoints(private val db: SQLiteDatabase) {
    private data class Header(val id: String,val count: Int,val digest: String,val progress: TrackMetadataProgress)
    private fun legacy(owner: Long)="track-metadata-run:$owner"
    private fun hash(raw: String)=MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
    private inline fun <reified T> decode(raw: String): T=try{json.decodeFromString<T>(raw)}
        catch(_: Exception){throw MusicFailure(MusicError.STORAGE)}
    private inline fun <T> atomic(block:()->T): T {
        db.beginTransaction()
        try{val result=block();db.setTransactionSuccessful();return result}finally{db.endTransaction()}
    }
    private fun validate(run: TrackMetadataRun) {
        musicCheck(run.owner>0&&run.next in 0..run.targets.size&&idPattern.matches(run.runId)&&
            run.updated>=0&&run.current>=0&&run.unavailable>=0&&run.skipped>=0&&run.failed>=0,MusicError.STORAGE)
    }
    private fun header(owner: Long): Header?=db.rawQuery(
        "SELECT run_id,target_count,targets_sha256,progress FROM metadata_runs WHERE owner=?",arrayOf(owner.toString())).use{c->
        if(!c.moveToFirst())null else Header(c.getString(0),c.getInt(1),c.getString(2),decode(c.getString(3))).also {
            val p=it.progress
            musicCheck(idPattern.matches(it.id)&&it.count>=0&&digestPattern.matches(it.digest)&&p.next in 0..it.count&&
                p.issueCount>=0&&p.updated>=0&&p.current>=0&&p.unavailable>=0&&p.skipped>=0&&p.failed>=0,MusicError.STORAGE)
        }
    }
    private fun headerOrMigrate(owner: Long): Header? {
        musicCheck(owner>0,MusicError.STORAGE)
        header(owner)?.let{return it}
        // A legacy JSON cell can exceed CursorWindow's row budget. Read bytes
        // in bounded chunks before decoding UTF-8, including split code points.
        val length=db.rawQuery("SELECT length(CAST(value AS BLOB)) FROM meta WHERE name=?",arrayOf(legacy(owner))).use{
            if(it.moveToFirst())it.getLong(0)else null
        }?:return null
        musicCheck(length in 1..MAX_LEGACY_BYTES.toLong(),MusicError.STORAGE)
        val bytes=ByteArrayOutputStream(length.toInt())
        var offset=0
        while(offset<length) {
            val part=db.rawQuery("SELECT substr(CAST(value AS BLOB),?,?) FROM meta WHERE name=?",
                arrayOf((offset+1).toString(),"65536",legacy(owner))).use{
                musicCheck(it.moveToFirst(),MusicError.STORAGE);it.getBlob(0)
            }
            musicCheck(part.isNotEmpty()&&part.size<=length-offset,MusicError.STORAGE)
            bytes.write(part);offset+=part.size
        }
        val raw=try{strictUtf8(bytes.toByteArray())}catch(_: Exception){throw MusicFailure(MusicError.STORAGE)}
        val old=decode<TrackMetadataRun>(raw)
        musicCheck(old.owner==owner,MusicError.STORAGE)
        writeFull(old) // Delete the old key only in the same successful transaction.
        return header(owner)?:throw MusicFailure(MusicError.STORAGE)
    }
    private fun issues(owner: Long,count: Int): List<TrackMetadataIssue> {
        musicCheck(count>=0,MusicError.STORAGE)
        return db.rawQuery("SELECT ordinal,payload FROM metadata_issues WHERE owner=? ORDER BY ordinal",arrayOf(owner.toString())).use{c->
            buildList {
                while(c.moveToNext()) {
                    musicCheck(c.getInt(0)==size&&size<count,MusicError.STORAGE)
                    add(decode<TrackMetadataIssue>(c.getString(1)))
                }
                musicCheck(size==count,MusicError.STORAGE)
            }
        }
    }
    fun load(owner: Long): TrackMetadataRun?=atomic {
        val h=headerOrMigrate(owner)?:return@atomic null
        val targets=db.rawQuery("SELECT ordinal,payload FROM metadata_targets WHERE owner=? ORDER BY ordinal",arrayOf(owner.toString())).use{c->
            buildList<TrackMetadataTarget> {
                while(c.moveToNext()) {
                    musicCheck(c.getInt(0)==size&&size<h.count,MusicError.STORAGE)
                    add(decode(c.getString(1)))
                }
            }
        }
        musicCheck(targets.size==h.count&&hash(json.encodeToString(targets))==h.digest,MusicError.STORAGE)
        val p=h.progress
        TrackMetadataRun(owner,targets,p.next,p.updated,p.current,p.unavailable,p.skipped,p.failed,
            issues(owner,p.issueCount),p.error,h.id).also(::validate)
    }
    /** UI recovery does not deserialize the immutable target list. */
    fun state(owner: Long): TrackMetadataState?=atomic {
        val h=headerOrMigrate(owner)?:return@atomic null
        musicCheck(h.progress.next in 0..h.count,MusicError.STORAGE)
        h.progress.state(h.count,issues(owner,h.progress.issueCount))
    }
    fun save(run: TrackMetadataRun)=atomic{writeFull(run)}
    private fun writeFull(run: TrackMetadataRun) {
        validate(run)
        val payload=json.encodeToString(run.targets);val digest=hash(payload)
        val old=header(run.owner)
        if(old?.id==run.runId) {
            musicCheck(old.count==run.targets.size&&old.digest==digest&&run.next>=old.progress.next,MusicError.STORAGE)
        }else {
            db.delete("metadata_targets","owner=?",arrayOf(run.owner.toString()))
            run.targets.forEachIndexed{i,target->db.execSQL("INSERT INTO metadata_targets(owner,ordinal,payload) VALUES(?,?,?)",
                arrayOf<Any>(run.owner,i,rowPayload(target)))}
        }
        if(old?.id==run.runId) {
            val existing=db.rawQuery("SELECT ordinal,payload FROM metadata_issues WHERE owner=? ORDER BY ordinal",arrayOf(run.owner.toString())).use{c->
                buildList<String>{while(c.moveToNext()){
                    musicCheck(c.getInt(0)==size,MusicError.STORAGE);add(c.getString(1))
                }}
            }
            musicCheck(existing.size==old.progress.issueCount,MusicError.STORAGE)
            run.issues.forEachIndexed{i,issue->
                val encoded=rowPayload(issue)
                if(i>=existing.size)insertIssue(run.owner,i,issue)
                else if(existing[i]!=encoded)db.execSQL("UPDATE metadata_issues SET payload=? WHERE owner=? AND ordinal=?",arrayOf<Any>(encoded,run.owner,i))
            }
            if(run.issues.size<existing.size)db.delete("metadata_issues","owner=? AND ordinal>=?",arrayOf(run.owner.toString(),run.issues.size.toString()))
        }else {
            db.delete("metadata_issues","owner=?",arrayOf(run.owner.toString()))
            run.issues.forEachIndexed{i,issue->insertIssue(run.owner,i,issue)}
        }
        db.execSQL("INSERT OR REPLACE INTO metadata_runs(owner,run_id,target_count,targets_sha256,progress) VALUES(?,?,?,?,?)",
            arrayOf<Any>(run.owner,run.runId,run.targets.size,digest,json.encodeToString(TrackMetadataProgress.of(run))))
        db.delete("meta","name=?",arrayOf(legacy(run.owner)))
    }
    private inline fun <reified T> rowPayload(value: T): String=json.encodeToString(value).also {
        musicCheck(it.toByteArray().size<=MAX_ROW_BYTES,MusicError.STORAGE)
    }
    private fun insertIssue(owner: Long,index: Int,issue: TrackMetadataIssue) {
        db.execSQL("INSERT INTO metadata_issues(owner,ordinal,payload) VALUES(?,?,?)",arrayOf<Any>(owner,index,rowPayload(issue)))
    }
    /** Batch-only append contract: targets stay identical; old issues are not edited.
     * Explicit manual resolutions go through save(), outside this hot path.
     */
    fun advance(before: TrackMetadataRun,after: TrackMetadataRun)=atomic {
        validate(before);validate(after)
        val step=after.next-before.next
        val counted=(after.updated.toLong()+after.current+after.unavailable+after.skipped+after.failed)-
            (before.updated.toLong()+before.current+before.unavailable+before.skipped+before.failed)
        musicCheck(before.owner==after.owner&&before.runId==after.runId&&before.targets===after.targets&&
            step in 0..1&&counted==step.toLong()&&after.updated>=before.updated&&after.current>=before.current&&
            after.unavailable>=before.unavailable&&after.skipped>=before.skipped&&after.failed>=before.failed&&
            after.issues.size-before.issues.size in 0..step,MusicError.STORAGE)
        val h=header(before.owner)?:throw MusicFailure(MusicError.STORAGE)
        musicCheck(h.id==before.runId&&h.count==before.targets.size&&h.progress==TrackMetadataProgress.of(before),MusicError.STORAGE)
        for(i in before.issues.size until after.issues.size)insertIssue(after.owner,i,after.issues[i])
        db.execSQL("UPDATE metadata_runs SET progress=? WHERE owner=? AND run_id=?",
            arrayOf<Any>(json.encodeToString(TrackMetadataProgress.of(after)),after.owner,after.runId))
    }
    companion object {
        private val json=Json{encodeDefaults=true}
        private val idPattern=Regex("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}")
        private val digestPattern=Regex("[0-9a-f]{64}")
        private const val MAX_LEGACY_BYTES=64*1024*1024
        private const val MAX_ROW_BYTES=65536
        fun create(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS metadata_runs(owner INTEGER PRIMARY KEY,run_id TEXT NOT NULL,target_count INTEGER NOT NULL,targets_sha256 TEXT NOT NULL,progress TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS metadata_targets(owner INTEGER NOT NULL,ordinal INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(owner,ordinal)) WITHOUT ROWID")
            db.execSQL("CREATE TABLE IF NOT EXISTS metadata_issues(owner INTEGER NOT NULL,ordinal INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(owner,ordinal)) WITHOUT ROWID")
        }
    }
}
