package app.alcovaibe.vkmusicexport.music

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@Serializable internal data class SavedAudio(val fileName: String,val track: NativeTrack,val extension: String,
    val mime: String,val durationMs: Long,val bytes: Long,val sha256: String,val savedAt: String)

/** Only canonical metadata is persisted. Media URLs and playlist access keys stay in memory. IO only. */
internal class NativeFiles(context: Context) {
    private val root=File(context.filesDir,"native-audio")
    private val index=AtomicFile(File(context.filesDir,"native-audio-index.json"))
    private val operation=AtomicFile(File(context.noBackupFilesDir,"native-music-operation.json"))
    val temporary=File(context.cacheDir,"native-audio-transfer")
    private val json=Json{encodeDefaults=true;ignoreUnknownKeys=false}
    fun read(): List<SavedAudio> = synchronized(lock) {
        if(!index.baseFile.exists()&&!File(index.baseFile.path+".bak").exists())return@synchronized emptyList()
        val bytes=index.openRead().use{it.readNBytes(131073)};musicCheck(bytes.size<=131072,MusicError.STORAGE)
        val rows=json.decodeFromString(ListSerializer(SavedAudio.serializer()),strictUtf8(bytes))
        musicCheck(rows.size<=5&&rows.sumOf{it.bytes}<=256L*1024*1024,MusicError.STORAGE)
        rows.forEach{file(it);musicCheck(it.bytes in 1..AudioTransfer.MAX_BYTES&&Regex("[0-9a-f]{64}").matches(it.sha256),MusicError.STORAGE)}
        rows.filter{file(it).isFile}
    }
    fun recover(): Boolean = synchronized(lock) {
        if(recovered)return@synchronized false
        root.mkdirs();temporary.mkdirs()
        // A single ViewModel owns active transfers; startup is before any operation is accepted.
        temporary.listFiles()?.filter{it.isFile}?.forEach{it.delete()}
        val o=readOperation();val interrupted=o?.get("state")?.jsonPrimitive?.content=="running"
        if(interrupted)writeOperation(o!!.getValue("id").jsonPrimitive.content,"interrupted")
        // Remove files left between the audio rename and atomic index commit after a process kill.
        val known=read().map{it.fileName}.toSet()
        root.listFiles()?.filter{it.isFile&&it.name !in known}?.forEach{it.delete()}
        recovered=true;interrupted
    }
    private fun readOperation(): JsonObject? {
        if(!operation.baseFile.exists()&&!File(operation.baseFile.path+".bak").exists())return null
        return runCatching{val bytes=operation.openRead().use{it.readNBytes(4097)};musicCheck(bytes.size<=4096,MusicError.STORAGE);Json.parseToJsonElement(strictUtf8(bytes)).jsonObject}.getOrNull()
    }
    private fun writeOperation(id: String,state: String) = write(operation,buildJsonObject{put("id",id);put("state",state)}.toString())
    fun begin(id: String) = synchronized(lock){writeOperation(id,"running")}
    fun finish(id: String,state: String) = synchronized(lock) {
        if(readOperation()?.get("id")?.jsonPrimitive?.content==id)writeOperation(id,state)
    }
    fun commit(result: TransferResult,track: NativeTrack,active:()->Unit): SavedAudio = synchronized(lock) {
        active();val media=result.media
        musicCheck(media.extension in setOf("mp3","m4a")&&media.durationMs>0,MusicError.FORMAT)
        val row=SavedAudio("audio-${UUID.randomUUID()}.${media.extension}",track,media.extension,media.mime,
            media.durationMs,result.file.length(),hash(result.file,active),Instant.now().toString())
        val old=read();val target=file(row);var committed=false
        try {
            active();musicCheck(result.file.renameTo(target),MusicError.STORAGE)
            val retained=mutableListOf(row);var bytes=row.bytes
            for(r in old)if(retained.size<5&&bytes+r.bytes<=256L*1024*1024){retained.add(r);bytes+=r.bytes}
            active();write(index,json.encodeToString(ListSerializer(SavedAudio.serializer()),retained));committed=true
            old.filter{it.fileName !in retained.map(SavedAudio::fileName)}.forEach{file(it).delete()}
            row
        }finally{if(!committed)target.delete();result.file.delete()}
    }
    fun file(row: SavedAudio): File {
        musicCheck(Regex("audio-[0-9a-f-]{36}\\.(mp3|m4a)").matches(row.fileName)&&row.fileName.endsWith(".${row.extension}"),MusicError.STORAGE)
        return File(root,row.fileName).also{musicCheck(it.canonicalFile.parentFile==root.canonicalFile,MusicError.STORAGE)}
    }
    fun verified(row: SavedAudio,active:()->Unit): File = synchronized(lock) {
        val f=file(row);musicCheck(f.isFile&&f.length()==row.bytes&&hash(f,active)==row.sha256,MusicError.INCOMPLETE);f
    }
    private fun hash(f: File,active:()->Unit): String {
        val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(32768)
        f.inputStream().use{input->while(true){active();val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
        return digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    private fun write(file: AtomicFile,text: String) {
        val out=file.startWrite();try{out.write(text.toByteArray(Charsets.UTF_8));file.finishWrite(out)}catch(e: Exception){file.failWrite(out);throw e}
    }
    private companion object {val lock=Any();var recovered=false}
}
