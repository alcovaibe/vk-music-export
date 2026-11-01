package app.alcovaibe.vkmusicexport.music

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.json.*
import java.io.File

/** Operation ownership is independent of the removed private audio archive. IO only. */
internal class NativeOperationJournal(context: Context) {
    private val file=AtomicFile(File(context.noBackupFilesDir,"native-music-operation.json"))
    fun recover(): Boolean = synchronized(lock) {
        val previous=read()
        val interrupted=previous?.get("state")?.jsonPrimitive?.content=="running"
        if(interrupted)write(previous!!.getValue("id").jsonPrimitive.content,"interrupted")
        interrupted
    }
    fun begin(id: String)=synchronized(lock){write(id,"running")}
    fun finish(id: String,state: String)=synchronized(lock) {
        if(read()?.get("id")?.jsonPrimitive?.content==id)write(id,state)
    }
    private fun read(): JsonObject? {
        if(!file.baseFile.exists()&&!File(file.baseFile.path+".bak").exists())return null
        return runCatching {
            val bytes=file.openRead().use{it.readBoundedBytes(4097)}
            musicCheck(bytes.size<=4096,MusicError.STORAGE)
            Json.parseToJsonElement(strictUtf8(bytes)).jsonObject
        }.getOrNull()
    }
    private fun write(id: String,state: String) {
        val bytes=buildJsonObject{put("id",id);put("state",state)}.toString().toByteArray()
        val out=file.startWrite()
        try{out.write(bytes);file.finishWrite(out)}catch(e: Exception){file.failWrite(out);throw e}
    }
    private companion object {val lock=Any()}
}
