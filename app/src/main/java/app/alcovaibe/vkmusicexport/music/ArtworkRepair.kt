package app.alcovaibe.vkmusicexport.music

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

internal fun fileSha256(file: File): String {
    val digest=MessageDigest.getInstance("SHA-256")
    file.inputStream().use{input->val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)}}
    return digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
}

/** One durable transaction, outside cache. A failed restore retains the backup and blocks further editing. */
internal class ArtworkRepair(context: Context,private val store: AudioExportStore,private val shared: SharedAudioStore,
    private val replace: suspend(PublishedAudio,File)->Unit=shared::overwrite) {
    private val dir=File(context.noBackupFilesDir,"artwork-repair").apply{mkdirs()}
    private val backup=File(dir,"original.audio")
    private val tagged=File(dir,"tagged.audio")
    private val journal=AtomicFile(File(dir,"transaction.json"))
    private val json=Json{encodeDefaults=true}
    @Serializable private data class Edit(val before: PublishedAudio,val after: PublishedAudio)
    private fun pending()=journal.baseFile.exists()||File(journal.baseFile.path+".bak").exists()
    private fun clear(){journal.delete();backup.delete();tagged.delete()}
    suspend fun recover() {
        if(!pending()){backup.delete();tagged.delete();return}
        val edit=json.decodeFromString<Edit>(journal.openRead().use{it.readBytes().toString(Charsets.UTF_8)})
        if(shared.valid(edit.after)){store.retagged(edit.before,edit.after);clear();return}
        if(!shared.valid(edit.before)) {
            musicCheck(backup.length()==edit.before.bytes&&fileSha256(backup)==edit.before.sha256,MusicError.STORAGE)
            shared.overwrite(edit.before,backup)
            musicCheck(shared.valid(edit.before),MusicError.STORAGE)
        }
        if(store.existing(edit.before.taskKey)==edit.after)store.retagged(edit.after,edit.before)
        clear()
    }
    suspend fun add(row: PublishedAudio,source: ArtworkSource,access: suspend()->MusicAccess): Boolean {
        recover()
        musicCheck(store.existing(row.taskKey)==row&&shared.valid(row),MusicError.INCOMPLETE)
        musicCheck(dir.usableSpace>row.bytes*2+8*1024*1024,MusicError.STORAGE)
        try {
            shared.copyTo(row,backup)
            musicCheck(fileSha256(backup)==row.sha256,MusicError.INCOMPLETE)
            if(EmbeddedArtwork.hasCover(backup,row.extension)) {
                if(!row.coverEmbedded)store.retagged(row,row.copy(coverEmbedded=true))
                return false
            }
            val image=source.load(access(),row.track)?:return false
            val ctx=currentCoroutineContext()
            EmbeddedArtwork.write(backup,tagged,row.extension,image){ctx.ensureActive()}
            musicCheck(tagged.length()<=AudioTransfer.MAX_BYTES&&shared.valid(row)&&store.existing(row.taskKey)==row,MusicError.INCOMPLETE)
            val after=row.copy(bytes=tagged.length(),sha256=fileSha256(tagged),coverEmbedded=true)
            val stream=journal.startWrite()
            try{stream.write(json.encodeToString(Edit(row,after)).toByteArray());journal.finishWrite(stream)}catch(e: Exception){journal.failWrite(stream);throw e}
            // Cancellation may occur during a write, so restoration and checkpoint completion cannot be cancellable.
            try {
                replace(row,tagged)
                musicCheck(shared.valid(after),MusicError.STORAGE)
                store.retagged(row,after);clear()
            }catch(e: Exception){withContext(NonCancellable+Dispatchers.IO){recover()};throw e}
            return true
        }finally{if(!pending()){backup.delete();tagged.delete()}}
    }
}

/** Tags only a private staging file; an image failure leaves the downloaded audio usable. */
internal class DownloadArtwork(private val source: ArtworkSource) {
    suspend fun add(result: TransferResult,track: NativeTrack,access: suspend()->MusicAccess): TransferResult {
        val next=File(result.file.parentFile,result.file.name+".artwork")
        try {
            if(EmbeddedArtwork.hasCover(result.file,result.media.extension))return TransferResult(result.file,result.media,true)
            val image=source.load(access(),track)?:return result
            val ctx=currentCoroutineContext()
            EmbeddedArtwork.write(result.file,next,result.media.extension,image){ctx.ensureActive()}
            musicCheck(next.length()<=AudioTransfer.MAX_BYTES,MusicError.TOO_LARGE)
            result.file.delete()
            return TransferResult(next,result.media,true)
        }catch(e: CancellationException){next.delete();throw e}
         catch(_: Exception){next.delete();return result}
    }
}
