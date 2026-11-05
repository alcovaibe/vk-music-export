package app.alcovaibe.vkmusicexport.music

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json

/** Wake-up only; SQLite revisions are the durable source, including missed/coalesced notifications. */
internal object SavedLibraryChanges {
    val revision=MutableStateFlow(0L)
    fun changed(){revision.update{it+1}}
}
internal data class SavedFileChange(val uri: String,val order: Long,val revision: Long,val file: PublishedAudio?)
/** Physical files and source memberships have separate identities. IO, inside the caller's transaction. */
internal class SavedFileIndex(private val db: SQLiteDatabase) {
    companion object {
        fun create(db: SQLiteDatabase) {
            db.execSQL("ALTER TABLE files ADD COLUMN uri TEXT")
            db.execSQL("CREATE INDEX file_uris ON files(uri)")
            db.execSQL("CREATE TABLE saved_files(uri TEXT PRIMARY KEY,downloaded_order INTEGER NOT NULL,revision INTEGER NOT NULL,payload TEXT)")
            db.execSQL("CREATE INDEX saved_order ON saved_files(downloaded_order DESC) WHERE payload IS NOT NULL")
            db.execSQL("CREATE INDEX saved_revision ON saved_files(revision)")
        }
    }
    private val json=Json{encodeDefaults=true}
    private fun counter(name: String)=db.rawQuery("SELECT value FROM meta WHERE name=?",arrayOf(name)).use{if(it.moveToFirst())it.getString(0).toLong()else 0L}
    private fun next(name: String): Long=(counter(name)+1).also{db.execSQL("INSERT OR REPLACE INTO meta(name,value) VALUES(?,?)",arrayOf(name,it.toString()))}
    fun revision()=counter("saved_revision")
    private fun order(uri: String): Long?=db.rawQuery("SELECT downloaded_order FROM saved_files WHERE uri=?",arrayOf(uri)).use{if(it.moveToFirst())it.getLong(0)else null}
    fun sync(uri: String) {
        val payload=db.rawQuery("SELECT payload FROM files WHERE uri=? ORDER BY rowid LIMIT 1",arrayOf(uri)).use{if(it.moveToFirst())it.getString(0)else null}
        val old=order(uri)
        if(old==null&&payload==null)return
        val active=db.rawQuery("SELECT 1 FROM saved_files WHERE uri=? AND payload IS NOT NULL",arrayOf(uri)).use{it.moveToFirst()}
        val sequence=if(old!=null&&(active||payload==null))old else next("saved_order")
        db.execSQL("INSERT OR REPLACE INTO saved_files(uri,downloaded_order,revision,payload) VALUES(?,?,?,?)",arrayOf(uri,sequence,next("saved_revision"),payload))
    }
    /** Relinking is the same physical file, not a fresh download. The old URI becomes a tombstone. */
    fun inheritOrder(before: String,after: String) {
        val original=order(before)?:return
        val target=order(after)
        if(target==null)db.execSQL("INSERT INTO saved_files(uri,downloaded_order,revision,payload) VALUES(?,?,?,NULL)",arrayOf(after,original,next("saved_revision")))
        else db.execSQL("UPDATE saved_files SET downloaded_order=? WHERE uri=?",arrayOf(minOf(original,target),after))
    }
    private fun rows(sql: String,args: Array<String>)=db.rawQuery(sql,args).use{c->buildList{
        while(c.moveToNext())add(SavedFileChange(c.getString(0),c.getLong(1),c.getLong(2),if(c.isNull(3))null else json.decodeFromString<PublishedAudio>(c.getString(3))))
    }}
    fun page(before: Long,limit: Int): List<SavedFileChange> {
        require(limit in 1..500)
        return rows("SELECT uri,downloaded_order,revision,payload FROM saved_files WHERE payload IS NOT NULL AND downloaded_order<? ORDER BY downloaded_order DESC LIMIT ?",arrayOf(before.toString(),limit.toString()))
    }
    fun changes(after: Long,limit: Int): List<SavedFileChange> {
        require(limit in 1..500)
        return rows("SELECT uri,downloaded_order,revision,payload FROM saved_files WHERE revision>? ORDER BY revision LIMIT ?",arrayOf(after.toString(),limit.toString()))
    }
}
