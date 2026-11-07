package app.alcovaibe.vkmusicexport.music

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.Json

/** A files row is a source membership; saved_files separately owns physical URI identity. */
internal object FileMembershipSchema {
    fun create(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE files ADD COLUMN source_account INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE files ADD COLUMN source_kind TEXT")
        db.execSQL("ALTER TABLE files ADD COLUMN source_id TEXT")
        db.execSQL("ALTER TABLE files ADD COLUMN occurrence INTEGER NOT NULL DEFAULT 1")
        db.execSQL("CREATE INDEX file_sources ON files(source_account,source_kind,source_id,track_id,occurrence)")
        val json=Json{ignoreUnknownKeys=true}
        var after=0L
        while(true) {
            val rows=db.rawQuery("SELECT rowid,payload FROM files WHERE rowid>? ORDER BY rowid LIMIT 100",
                arrayOf(after.toString())).use{cursor->buildList {
                    while(cursor.moveToNext())add(cursor.getLong(0) to cursor.getString(1))
                }}
            if(rows.isEmpty())break
            for((id,payload)in rows) {
                val row=try{json.decodeFromString<PublishedAudio>(payload)}
                    catch(_: Exception){throw MusicFailure(MusicError.STORAGE)}
                db.execSQL("UPDATE files SET source_account=?,source_kind=?,source_id=?,occurrence=? WHERE rowid=?",
                    arrayOf<Any?>(row.source?.account?:0,row.source?.kind,row.source?.id,row.occurrence,id))
            }
            after=rows.last().first
        }
    }
}
