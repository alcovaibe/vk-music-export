package app.alcovaibe.vkmusicexport.music

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest

/** IO-only checkpoint of completed checks, bound to one account and an exact ordered library snapshot.
 * Neither tokens nor CDN URLs are retained. Receipts survive eviction of the optional temporary audio.
 * This is discovery evidence only: removal always downloads both records afresh.
 */
internal class DuplicateScanCache(context: Context,private val wall: ()->Long=System::currentTimeMillis,
    private val audioLimit: Long=256L*1024*1024) {
    private val directory=File(context.noBackupFilesDir,"duplicate-scan")
    private val journal=AtomicFile(File(directory,"checks.json"))
    private data class Receipt(val codec: String,val sha256: String)
    private var owner=0L;private var snapshot="";private var created=0L
    private val receipts=linkedMapOf<String,Receipt>()
    private fun hash(value: String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun key(track: NativeTrack)=hash(Json.encodeToString(track))
    init { synchronized(lock){load()} }
    private fun load() {
        try {
            if(!journal.baseFile.exists()&&!File(journal.baseFile.path+".bak").exists())return
            val bytes=journal.openRead().use{it.readNBytes(MAX_JSON+1)}
            require(bytes.size<=MAX_JSON)
            val data=Json.parseToJsonElement(strictUtf8(bytes)).jsonObject
            require(data.getValue("schema").jsonPrimitive.int==1)
            owner=data.getValue("owner").jsonPrimitive.long
            snapshot=data.getValue("snapshot").jsonPrimitive.content
            created=data.getValue("created").jsonPrimitive.long
            require(owner>0&&hex.matches(snapshot)&&wall()>=created&&wall()-created<TTL)
            val rows=data.getValue("checks").jsonObject
            require(rows.size<=MAX_CHECKS)
            rows.forEach{(id,value)->
                val row=value.jsonObject;val codec=row.getValue("codec").jsonPrimitive.content;val sha=row.getValue("sha256").jsonPrimitive.content
                require(hex.matches(id)&&codec in setOf("mp3","m4a")&&hex.matches(sha))
                receipts[id]=Receipt(codec,sha)
            }
        }catch(_: Exception){clear()}
    }
    fun count(): Int=synchronized(lock){if(wall()>=created&&wall()-created<TTL)receipts.size else 0}
    fun begin(owner: Long,tracks: List<NativeTrack>)=synchronized(lock) {
        musicCheck(owner>0,MusicError.ACCESS)
        val fingerprint=hash(Json.encodeToString(tracks))
        if(this.owner!=owner||snapshot!=fingerprint||wall()<created||wall()-created>=TTL) {
            clear();this.owner=owner;snapshot=fingerprint;created=wall();write()
        }
        // AudioTransfer owns its transient cacheDir; these are only completed optional copies.
        directory.listFiles().orEmpty().filter{it.extension in setOf("mp3","m4a")&&it.nameWithoutExtension !in receipts}.forEach{it.delete()}
        trimAudio()
    }
    fun get(track: NativeTrack): DuplicateSignature?=synchronized(lock) {
        if(count()==0)return@synchronized null
        receipts[key(track)]?.let{DuplicateSignature(it.codec,it.sha256)}
    }
    fun save(track: NativeTrack,signature: DuplicateSignature,audio: File)=synchronized(lock) {
        musicCheck(owner>0&&snapshot.isNotEmpty()&&signature.codec in setOf("mp3","m4a")&&hex.matches(signature.sha256),MusicError.STORAGE)
        val id=key(track)
        musicCheck(directory.isDirectory||directory.mkdirs(),MusicError.STORAGE)
        // A missing/evicted audio file does not erase the already verified payload digest.
        val retained=File(directory,"$id.${signature.codec}")
        musicCheck(audio.renameTo(retained),MusicError.STORAGE)
        receipts[id]=Receipt(signature.codec,signature.sha256)
        while(receipts.size>MAX_CHECKS)receipts.remove(receipts.keys.first())
        write();trimAudio()
    }
    fun clear()=synchronized(lock) {
        directory.deleteRecursively();receipts.clear();owner=0;snapshot="";created=0
    }
    private fun trimAudio() {
        val files=directory.listFiles().orEmpty().filter{it.extension in setOf("mp3","m4a")}.sortedBy{it.lastModified()}
        var bytes=files.sumOf{it.length()}
        for(file in files){if(bytes<=audioLimit)break;val size=file.length();if(file.delete())bytes-=size}
    }
    private fun write() {
        musicCheck(directory.isDirectory||directory.mkdirs(),MusicError.STORAGE)
        val bytes=buildJsonObject {
            put("schema",1);put("owner",owner);put("snapshot",snapshot);put("created",created)
            putJsonObject("checks"){receipts.forEach{(key,row)->putJsonObject(key){put("codec",row.codec);put("sha256",row.sha256)}}}
        }.toString().toByteArray()
        musicCheck(bytes.size<=MAX_JSON,MusicError.STORAGE)
        val output=journal.startWrite()
        try{output.write(bytes);journal.finishWrite(output)}catch(e: Exception){journal.failWrite(output);throw e}
    }
    private companion object {
        val lock=Any();val hex=Regex("[0-9a-f]{64}")
        const val TTL=24L*60*60*1000;const val MAX_JSON=8*1024*1024;const val MAX_CHECKS=20000
    }
}
