package app.alcovaibe.vkmusicexport.music

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Never infer a version from a collision suffix, duration or a similar search result. */
internal object TrackMetadata {
    fun merge(saved: NativeTrack,fresh: NativeTrack): NativeTrack {
        musicCheck(saved.id==fresh.id&&fresh.duration>0&&saved.duration==fresh.duration,MusicError.INCOMPLETE)
        return saved.copy(title=fresh.title,subtitle=fresh.subtitle)
    }
}

/** Durable backup before changing bytes/name. Recovery finishes a verified edit or restores the original. */
internal class TrackMetadataRepair(context: Context,private val store: AudioExportStore,private val shared: SharedAudioStore,
    private val beforeWrite:suspend()->Unit={},private val replace:suspend(PublishedAudio,File)->Unit=shared::overwriteMetadata,
    private val afterIndex:()->Unit={}) {
    private val dir=File(context.noBackupFilesDir,"track-metadata-repair").apply{mkdirs()}
    private val backup=File(dir,"original.audio")
    private val tagged=File(dir,"tagged.audio")
    private val journal=AtomicFile(File(dir,"transaction.json"))
    private val json=Json{encodeDefaults=true}
    @Serializable private data class Edit(val before: PublishedAudio,val after: PublishedAudio)
    private fun pending()=journal.baseFile.exists()||File(journal.baseFile.path+".bak").exists()
    private fun clear(){journal.delete();backup.delete();tagged.delete()}
    private fun indexed(edit: Edit): PublishedAudio {
        val current=store.existing(edit.before.taskKey)?:throw MusicFailure(MusicError.STORAGE)
        musicCheck(current==edit.before||current.copy(displayName=edit.after.displayName)==edit.after,MusicError.STORAGE)
        return current
    }
    suspend fun recover() {
        if(!pending()){backup.delete();tagged.delete();return}
        val edit=json.decodeFromString<Edit>(journal.openRead().use{it.readBytes().toString(Charsets.UTF_8)})
        if(shared.metadataEditing(edit.before)) {
            val check=shared.inspect(edit.after,pending=true)
            val written=check.reason in setOf(SavedFileReason.OK,SavedFileReason.LOCATION_CHANGED)
            val record=if(written)edit.after else edit.before
            if(!written) {
                musicCheck(backup.length()==edit.before.bytes&&fileSha256(backup)==edit.before.sha256,MusicError.STORAGE)
                shared.overwriteMetadata(edit.before,backup)
            }
            val finished=shared.finishMetadataEdit(record,record.displayName)
            store.metadataUpdated(indexed(edit),finished)
        }else {
            // Publication and even the DB commit may have happened before process death.
            val completed=shared.completed(edit.after)
            if(completed!=null)store.metadataUpdated(indexed(edit),completed)
            else musicCheck(shared.valid(edit.before)&&store.existing(edit.before.taskKey)==edit.before,MusicError.STORAGE)
        }
        clear()
    }
    suspend fun update(row: PublishedAudio,fresh: NativeTrack): Boolean {
        recover()
        musicCheck(row.catalogueVerified&&store.existing(row.taskKey)==row,MusicError.INCOMPLETE)
        shared.requireValid(row)
        val track=TrackMetadata.merge(row.track,fresh)
        val name=shared.metadataName(row,track)
        if(row.track==track&&row.textTitle==track.displayTitle&&row.displayName==name)return false
        AudioStorage.requireSpace(dir,row.bytes,2)
        try {
            shared.copyTo(row,backup)
            musicCheck(fileSha256(backup)==row.sha256,MusicError.INCOMPLETE)
            val ctx=currentCoroutineContext()
            EmbeddedArtwork.writeTextMetadata(backup,tagged,track,row.extension){ctx.ensureActive()}
            musicCheck(tagged.length()>0&&shared.valid(row)&&store.existing(row.taskKey)==row,MusicError.INCOMPLETE)
            val after=row.copy(track=track,displayName=name,bytes=tagged.length(),sha256=fileSha256(tagged),textTitle=track.displayTitle)
            beforeWrite();ctx.ensureActive()
            val stream=journal.startWrite()
            try{stream.write(json.encodeToString(Edit(row,after)).toByteArray());journal.finishWrite(stream)}
            catch(e: Exception){journal.failWrite(stream);throw e}
            try {
                // Complete or restore this one file before releasing the shared editing lock.
                withContext(NonCancellable+Dispatchers.IO) {
                    shared.beginMetadataEdit(row)
                    replace(row,tagged)
                    val check=shared.inspect(after,pending=true)
                    musicCheck(check.reason in setOf(SavedFileReason.OK,SavedFileReason.LOCATION_CHANGED),MusicError.STORAGE)
                    val renamed=shared.finishMetadataEdit(after,name)
                    store.metadataUpdated(row,renamed);afterIndex();clear()
                }
            }catch(e: Exception){withContext(NonCancellable+Dispatchers.IO){recover()};throw e}
            return true
        }finally{if(!pending()){backup.delete();tagged.delete()}}
    }
}
