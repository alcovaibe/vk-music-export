package app.alcovaibe.vkmusicexport.music

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Independent derived state; never upgrades or deletes the user's audio/export database. */
internal class PlaybackHistoryStore(context: Context,private val observe: (String)->Unit={}) :
    SQLiteOpenHelper(context,File(context.noBackupFilesDir,"playback-history.db").absolutePath,null,1) {
    private val json=Json{encodeDefaults=true}
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE playback_state (singleton INTEGER PRIMARY KEY CHECK(singleton=1), queue_id TEXT NOT NULL, session_id TEXT, cursor TEXT NOT NULL)")
        db.execSQL("CREATE TABLE playback_entries (ordinal INTEGER PRIMARY KEY, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE playback_order (ordinal INTEGER PRIMARY KEY, item_index INTEGER NOT NULL UNIQUE)")
    }
    override fun onUpgrade(db: SQLiteDatabase,old: Int,new: Int){error("Unsupported playback history upgrade")}
    private inline fun <T> transaction(block:(SQLiteDatabase)->T): T {
        val db=writableDatabase;db.beginTransaction()
        try{val result=block(db);db.setTransactionSuccessful();return result}finally{db.endTransaction()}
    }
    fun replace(record: PlaybackCheckpoint)=transaction {db->
        record.validate()
        observe("replace");db.delete("playback_entries",null,null);db.delete("playback_order",null,null);db.delete("playback_state",null,null)
        db.compileStatement("INSERT INTO playback_entries VALUES (?,?)").use {statement->
            record.entries.forEachIndexed{i,entry->
                val payload=json.encodeToString(entry);require(payload.toByteArray().size<=64*1024)
                statement.bindLong(1,i.toLong());statement.bindString(2,payload);statement.executeInsert()
            }
        }
        writeOrder(db,record.order)
        db.insertOrThrow("playback_state",null,ContentValues().apply{
            put("singleton",1);put("queue_id",record.id);put("session_id",record.session);put("cursor",json.encodeToString(record.cursor))
        });Unit
    }
    private fun writeOrder(db: SQLiteDatabase,order: List<Int>) {
        observe("order");db.delete("playback_order",null,null)
        db.compileStatement("INSERT INTO playback_order VALUES (?,?)").use {statement->order.forEachIndexed{i,index->
            statement.bindLong(1,i.toLong());statement.bindLong(2,index.toLong());statement.executeInsert()
        }}
    }
    fun update(id: String,cursor: PlaybackCursor,order: List<Int>?=null): Boolean=transaction {db->
        require(cursor.index>=0&&cursor.positionMs>=0&&cursor.repeat in 0..2)
        val changed=db.update("playback_state",ContentValues().apply{put("cursor",json.encodeToString(cursor))},
            "queue_id=? AND EXISTS (SELECT 1 FROM playback_entries WHERE ordinal=?)",arrayOf(id,cursor.index.toString()))==1
        if(changed){
            observe("cursor")
            if(order!=null) {
                require(order.size in 1..100_000&&order.toSet()==order.indices.toSet())
                require(db.rawQuery("SELECT COUNT(*) FROM playback_entries",null).use{it.moveToFirst();it.getInt(0)}==order.size)
                writeOrder(db,order)
            }
        }
        changed
    }
    fun clear(expectedId: String?=null)=transaction {db->
        val changed=db.delete("playback_state",if(expectedId==null)null else "queue_id=?",if(expectedId==null)null else arrayOf(expectedId))
        if(expectedId==null||changed>0) {
            db.delete("playback_entries",null,null);db.delete("playback_order",null,null);observe("clear")
        };Unit
    }
    fun read(): PlaybackCheckpoint?=transaction {db->
        var id: String?=null;var session: String?=null;var cursor: PlaybackCursor?=null
        db.rawQuery("SELECT queue_id,session_id,cursor FROM playback_state WHERE singleton=1",null).use{c->if(c.moveToFirst()) {
            id=c.getString(0);session=c.getString(1);cursor=json.decodeFromString<PlaybackCursor>(c.getString(2))
        }}
        if(id==null)return@transaction null
        val entries=db.rawQuery("SELECT ordinal,payload FROM playback_entries ORDER BY ordinal LIMIT 100001",null).use{c->buildList{
            while(c.moveToNext()){require(c.getInt(0)==size);val raw=c.getString(1);require(raw.toByteArray().size<=64*1024);add(json.decodeFromString<PlaybackEntry>(raw))}
        }}
        val order=db.rawQuery("SELECT ordinal,item_index FROM playback_order ORDER BY ordinal LIMIT 100001",null).use{c->buildList{
            while(c.moveToNext()){require(c.getInt(0)==size);add(c.getInt(1))}
        }}
        PlaybackCheckpoint(checkNotNull(id),entries,session,checkNotNull(cursor),order).also{it.validate()}
    }
}
