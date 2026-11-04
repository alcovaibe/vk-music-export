package app.alcovaibe.vkmusicexport.music

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.AtomicFile
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

internal data class DuplicateCacheLease(val owner: Long,val generation: String)

/** Separate derived-data DB: clearing receipts cannot delete the export library.
 * Callers serialize access; every durable change is an atomic transaction.
 */
internal class DuplicateReceiptStore(context: Context): SQLiteOpenHelper(context,
    File(context.noBackupFilesDir,"duplicate-scan.db").path,null,1) {
    private val directory=File(context.noBackupFilesDir,"duplicate-scan")
    private val legacy=AtomicFile(File(directory,"checks.json"))
    private var initialized=false
    private data class Header(val owner: Long,val generation: String,val snapshot: String,val created: Long,
        val size: Int,val sequence: Long,val legacyKeys: Boolean) {
        val lease get()=DuplicateCacheLease(owner,generation)
    }
    private data class Receipt(val key: String,val codec: String,val sha256: String)
    private data class Legacy(val owner: Long,val snapshot: String,val created: Long,val rows: List<Receipt>)
    init{setWriteAheadLoggingEnabled(true)}
    override fun onConfigure(db: SQLiteDatabase){db.execSQL("PRAGMA synchronous=FULL")}
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE duplicate_state(singleton INTEGER PRIMARY KEY CHECK(singleton=1),owner INTEGER NOT NULL,generation TEXT NOT NULL,snapshot TEXT NOT NULL,created INTEGER NOT NULL,receipt_count INTEGER NOT NULL,next_ordinal INTEGER NOT NULL,legacy_keys INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE duplicate_receipts(owner INTEGER NOT NULL,track_key TEXT NOT NULL,ordinal INTEGER NOT NULL,codec TEXT NOT NULL,sha256 TEXT NOT NULL,signature_version INTEGER NOT NULL,PRIMARY KEY(owner,track_key)) WITHOUT ROWID")
        db.execSQL("CREATE UNIQUE INDEX duplicate_receipt_order ON duplicate_receipts(owner,ordinal)")
        db.execSQL("CREATE TABLE duplicate_import(singleton INTEGER PRIMARY KEY CHECK(singleton=1),status TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase,oldVersion: Int,newVersion: Int){error("Unsupported receipt schema")}
    private inline fun <T> atomic(block:(SQLiteDatabase)->T): T {
        val db=writableDatabase;db.beginTransaction();var failure: Throwable?=null
        try{val result=block(db);db.setTransactionSuccessful();return result}
        catch(e: Throwable){failure=e;throw e}
        finally{try{db.endTransaction()}catch(cleanup: Throwable){if(failure!=null)failure.addSuppressed(cleanup)else throw cleanup}}
    }
    private fun header(db: SQLiteDatabase): Header?=db.rawQuery(
        "SELECT owner,generation,snapshot,created,receipt_count,next_ordinal,legacy_keys FROM duplicate_state WHERE singleton=1",null).use{c->
        if(!c.moveToFirst())null else Header(c.getLong(0),c.getString(1),c.getString(2),c.getLong(3),c.getInt(4),c.getLong(5),c.getInt(6)==1).also {
            musicCheck(it.owner>0&&UUID_PATTERN.matches(it.generation)&&DuplicateReceiptKeys.hex.matches(it.snapshot)&&
                it.created>=0&&it.size in 0..DuplicateReceiptKeys.MAX_CHECKS&&it.sequence>=it.size&&c.getInt(6) in 0..1,MusicError.STORAGE)
        }
    }
    private fun writeHeader(db: SQLiteDatabase,h: Header) {
        db.execSQL("INSERT OR REPLACE INTO duplicate_state(singleton,owner,generation,snapshot,created,receipt_count,next_ordinal,legacy_keys) VALUES(1,?,?,?,?,?,?,?)",
            arrayOf<Any>(h.owner,h.generation,h.snapshot,h.created,h.size,h.sequence,if(h.legacyKeys)1 else 0))
    }
    private fun importStatus(db: SQLiteDatabase)=db.rawQuery("SELECT status FROM duplicate_import WHERE singleton=1",null).use{if(it.moveToFirst())it.getString(0)else null}
    private fun parseLegacy(bytes: ByteArray): Legacy?=try {
        require(bytes.size<=MAX_JSON)
        val obj=Json.parseToJsonElement(strictUtf8(bytes)).jsonObject
        require(obj.getValue("schema").jsonPrimitive.int==2)
        val owner=obj.getValue("owner").jsonPrimitive.long
        val snapshot=obj.getValue("snapshot").jsonPrimitive.content
        val created=obj.getValue("created").jsonPrimitive.long
        require(owner>0&&created>=0&&DuplicateReceiptKeys.hex.matches(snapshot))
        val checks=obj.getValue("checks").jsonObject;require(checks.size<=DuplicateReceiptKeys.MAX_CHECKS)
        val rows=checks.map{(key,value)->
            val row=value.jsonObject
            Receipt(key,row.getValue("codec").jsonPrimitive.content,row.getValue("sha256").jsonPrimitive.content).also {
                require(DuplicateReceiptKeys.hex.matches(it.key)&&it.codec in setOf("mp3","m4a")&&DuplicateReceiptKeys.hex.matches(it.sha256))
            }
        }
        Legacy(owner,snapshot,created,rows)
    }catch(_: Exception){null} // Parsing only. IO and SQLite failures remain retryable.
    private fun ensureImported(db: SQLiteDatabase,now: Long) {
        if(importStatus(db)!=null)return
        var status="absent"
        if(legacy.baseFile.exists()||File(legacy.baseFile.path+".bak").exists()) {
            val bytes=legacy.openRead().use{it.readAtMost(MAX_JSON+1)}
            val old=parseLegacy(bytes)
            status=when{old==null->"invalid";!DuplicateReceiptKeys.fresh(old.created,now)->"expired";else->"imported"}
            if(old!=null&&status=="imported") {
                old.rows.forEachIndexed{i,row->insert(db,old.owner,row.key,i.toLong(),DuplicateSignature(row.codec,row.sha256))}
                writeHeader(db,Header(old.owner,UUID.randomUUID().toString(),old.snapshot,old.created,old.rows.size,old.rows.size.toLong(),true))
            }
        }
        db.execSQL("INSERT INTO duplicate_import(singleton,status) VALUES(1,?)",arrayOf(status))
    }
    /** Commit precedes deletion. An interruption leaves an ignored old copy, never a partial import. */
    private inline fun <T> ready(now: Long,block:(SQLiteDatabase)->T): T {
        val result=atomic{db->if(!initialized)ensureImported(db,now);block(db)}
        if(!initialized) {
            val status=importStatus(readableDatabase)
            if(status=="imported"||status=="expired"){legacy.delete();directory.delete()}
            initialized=true
        }
        return result
    }
    fun count(now: Long,lease: DuplicateCacheLease?): Int=ready(now){db->
        val h=header(db)
        if(h!=null&&DuplicateReceiptKeys.fresh(h.created,now)&&(lease==null||h.lease==lease))h.size else 0
    }
    fun begin(owner: Long,tracks: List<NativeTrack>,now: Long): DuplicateCacheLease {
        musicCheck(owner>0&&now>=0,MusicError.STORAGE)
        val fingerprint=DuplicateReceiptKeys.snapshot(tracks)
        val result=ready(now){db->
            val old=header(db)
            if(old==null||old.owner!=owner||!DuplicateReceiptKeys.fresh(old.created,now)) {
                db.delete("duplicate_receipts",null,null)
                Header(owner,UUID.randomUUID().toString(),fingerprint,now,0,0,false).also{writeHeader(db,it)}.lease
            }else if(old.snapshot!=fingerprint||old.legacyKeys) {
                val keys=db.rawQuery("SELECT track_key FROM duplicate_receipts WHERE owner=?",arrayOf(owner.toString())).use{c->buildSet{while(c.moveToNext())add(c.getString(0))}}
                val allowed=HashSet<String>()
                for(track in tracks) {
                    val key=DuplicateReceiptKeys.key(track);allowed.add(key)
                    if(old.legacyKeys&&track.album!=null) {
                        val previous=DuplicateReceiptKeys.legacy(track)
                        if(previous!=key&&previous in keys) {
                            db.execSQL("UPDATE OR IGNORE duplicate_receipts SET track_key=? WHERE owner=? AND track_key=?",arrayOf<Any>(key,owner,previous))
                            db.delete("duplicate_receipts","owner=? AND track_key=?",arrayOf(owner.toString(),previous))
                        }
                    }
                }
                val remaining=db.rawQuery("SELECT track_key FROM duplicate_receipts WHERE owner=?",arrayOf(owner.toString())).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
                for(key in remaining)if(key !in allowed)db.delete("duplicate_receipts","owner=? AND track_key=?",arrayOf(owner.toString(),key))
                old.copy(generation=UUID.randomUUID().toString(),snapshot=fingerprint,size=remaining.count{it in allowed},legacyKeys=false).also{writeHeader(db,it)}.lease
            }else old.lease
        }
        directory.listFiles().orEmpty().filter{it.extension in setOf("mp3","m4a")}.forEach{it.delete()}
        directory.delete()
        return result
    }
    fun get(lease: DuplicateCacheLease,key: String,now: Long): DuplicateSignature?=ready(now){db->
        val h=header(db)
        if(h==null||h.lease!=lease||!DuplicateReceiptKeys.fresh(h.created,now))return@ready null
        val result=db.rawQuery("SELECT codec,sha256,signature_version FROM duplicate_receipts WHERE owner=? AND track_key=?",arrayOf(lease.owner.toString(),key)).use{c->
            if(!c.moveToFirst())return@ready null else {
                val codec=c.getString(0);val sha=c.getString(1)
                if(codec in setOf("mp3","m4a")&&DuplicateReceiptKeys.hex.matches(sha)&&c.getInt(2)==2)DuplicateSignature(codec,sha) else null
            }
        }
        if(result==null) {
            db.delete("duplicate_receipts","owner=? AND track_key=?",arrayOf(lease.owner.toString(),key))
            db.execSQL("UPDATE duplicate_state SET receipt_count=? WHERE singleton=1",arrayOf(maxOf(0,h.size-1)))
        }
        result
    }
    private fun insert(db: SQLiteDatabase,owner: Long,key: String,ordinal: Long,signature: DuplicateSignature) {
        db.execSQL("INSERT INTO duplicate_receipts(owner,track_key,ordinal,codec,sha256,signature_version) VALUES(?,?,?,?,?,2)",
            arrayOf<Any>(owner,key,ordinal,signature.codec,signature.sha256))
    }
    fun save(lease: DuplicateCacheLease,key: String,signature: DuplicateSignature,now: Long)=ready(now){db->
        val h=header(db)?:throw MusicFailure(MusicError.STORAGE)
        musicCheck(h.lease==lease&&DuplicateReceiptKeys.fresh(h.created,now),MusicError.STORAGE)
        val updated=db.compileStatement("UPDATE duplicate_receipts SET codec=?,sha256=?,signature_version=2 WHERE owner=? AND track_key=?").use{
            it.bindString(1,signature.codec);it.bindString(2,signature.sha256);it.bindLong(3,lease.owner);it.bindString(4,key);it.executeUpdateDelete()
        }
        if(updated==0) {
            musicCheck(h.sequence<Long.MAX_VALUE,MusicError.STORAGE)
            insert(db,h.owner,key,h.sequence,signature)
            val size=h.size+1
            if(size>DuplicateReceiptKeys.MAX_CHECKS)db.execSQL("DELETE FROM duplicate_receipts WHERE owner=? AND ordinal=(SELECT MIN(ordinal) FROM duplicate_receipts WHERE owner=?)",arrayOf<Any>(h.owner,h.owner))
            db.execSQL("UPDATE duplicate_state SET receipt_count=?,next_ordinal=? WHERE singleton=1",arrayOf<Any>(minOf(size,DuplicateReceiptKeys.MAX_CHECKS),h.sequence+1))
        }
    }
    fun clear() {
        atomic{db->db.delete("duplicate_receipts",null,null);db.delete("duplicate_state",null,null)
            db.execSQL("INSERT OR REPLACE INTO duplicate_import(singleton,status) VALUES(1,'absent')")}
        initialized=true;directory.deleteRecursively()
    }
    private companion object {
        const val MAX_JSON=8*1024*1024
        val UUID_PATTERN=Regex("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}")
    }
}
