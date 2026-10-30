package app.alcovaibe.vkmusicexport.music

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

internal enum class SavedFileReason(val label: String) {
    OK("Файл проверен."),MISSING("Запись файла не найдена в доступной медиатеке."),NO_ACCESS("Android не разрешил прочитать файл."),
    OWNER_CHANGED("Файл больше не принадлежит приложению."),PENDING("Публикация файла не завершена."),
    OUTSIDE_FOLDER("Файл перемещён за пределы разрешённой папки."),SIZE_CHANGED("Размер файла изменился."),
    HASH_CHANGED("Содержимое файла изменилось: SHA-256 не совпал."),READ_ERROR("Не удалось прочитать файл."),
    LOCATION_CHANGED("Имя или расположение файла изменилось."),AMBIGUOUS("Найдено несколько одинаковых копий. Выбери файл вручную."),
    NOT_FOUND("Точное совпадение среди доступных файлов приложения не найдено."),IN_USE("Эта копия уже связана с другой записью.")
}
internal data class SavedFileCheck(val reason: SavedFileReason,val actual: PublishedAudio?=null)
internal class SavedFileFailure(val reason: SavedFileReason): MusicFailure(MusicError.INCOMPLETE)
internal data class SavedFileProblem(val row: PublishedAudio,val reason: SavedFileReason,val searchReason: SavedFileReason?=null)
internal data class SavedFilePreview(val before: PublishedAudio,val name: String,val bytes: Long,val sha256: String,
    val media: ProcessedAudio,val covered: Boolean,val exact: Boolean,val references: Int=1)
internal data class SavedFilesState(val visible: Boolean=false,val running: Boolean=false,val processed: Int=0,val total: Int=0,
    val restored: Int=0,val problems: List<SavedFileProblem> = emptyList(),val message: String?=null,
    val selecting: PublishedAudio?=null,val preview: SavedFilePreview?=null,val forget: PublishedAudio?=null)

/** Local reconciliation only. Read-only scan, strict byte identity, CAS index, no remote VK calls. */
internal class SavedFileRecovery(private val context: Context,private val store: AudioExportStore,private val shared: SharedAudioStore,private val onPublished: ()->Unit={}) {
    private val dir=File(context.noBackupFilesDir,"saved-file-recovery").apply{mkdirs()}
    private val selected=File(dir,"selected.audio")
    private val journal=AtomicFile(File(dir,"transaction.json"))
    private val json=Json{encodeDefaults=true}
    @Serializable private data class Import(val before: PublishedAudio,val after: PublishedAudio)
    private fun pending()=journal.baseFile.exists()||File(journal.baseFile.path+".bak").exists()
    /** The checkpoint precedes publication. A full copy is adopted; pending copies are removed, source untouched. */
    suspend fun recover() {
        if(!pending())return
        val edit=json.decodeFromString<Import>(journal.openRead().use{it.readBytes().toString(Charsets.UTF_8)})
        val finished=shared.completed(edit.after)
        if(finished!=null) {
            val current=store.existing(edit.before.taskKey)
            if(current!=null&&(current==edit.before||current==edit.after||current==finished))store.relinked(current,finished)
            // A stale operation cannot restore a forgotten/replaced entry. The published copy is left on disk.
        }else shared.removePending(edit.after.uri)
        journal.delete()
    }
    suspend fun search(before: PublishedAudio): SavedFileReason {
        recover()
        musicCheck(store.existing(before.taskKey)==before,MusicError.STORAGE)
        val matches=mutableListOf<PublishedAudio>()
        for(candidate in shared.recoveryCandidates(before)) {
            currentCoroutineContext().ensureActive()
            if(shared.valid(candidate))matches.add(candidate)
            if(matches.size>1)return SavedFileReason.AMBIGUOUS
        }
        val match=matches.singleOrNull()?:return SavedFileReason.NOT_FOUND
        if(!store.canShareLocation(before,match))return SavedFileReason.IN_USE
        // Recheck after the scan; never accept a file changed during hashing.
        shared.requireValid(match);store.relinked(before,match)
        return SavedFileReason.OK
    }
    suspend fun preview(before: PublishedAudio,uri: Uri): SavedFilePreview {
        recover();selected.delete()
        musicCheck(uri.scheme=="content"&&store.existing(before.taskKey)==before,MusicError.STORAGE)
        val name=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{c->
            if(c.moveToFirst())c.getString(0) else null
        }?:"Выбранный аудиофайл"
        AudioStorage.requireSpace(dir,before.bytes,2)
        try {
            val input=context.contentResolver.openInputStream(uri)?:throw SavedFileFailure(SavedFileReason.NO_ACCESS)
            selected.outputStream().use{out->input.use{source->
                val buffer=ByteArray(65536);var count=0L
                while(true){currentCoroutineContext().ensureActive();val n=source.read(buffer);if(n<0)break;count+=n
                    AudioStorage.requireSpace(dir,count,2);out.write(buffer,0,n)}
                out.flush();out.fd.sync()
            }}
            val hash=fileSha256(selected)
            val exact=selected.length()==before.bytes&&hash==before.sha256
            val checked=File(dir,"checked.audio")
            val media=try{AndroidAudioProcessor().process(selected,checked,before.track,null)}finally{checked.delete()}
            // Preserve the chosen bytes/tags. Parsing validates actual audio and duration; no remux is published.
            val covered=EmbeddedArtwork.hasCover(selected,media.extension)
            return SavedFilePreview(before,name,selected.length(),hash,media,covered,exact,
                store.savedFiles().count{it.uri==before.uri})
        }catch(e: Exception){selected.delete();throw e}
    }
    fun discard(){selected.delete()}
    suspend fun confirm(preview: SavedFilePreview): PublishedAudio {
        recover()
        musicCheck(store.existing(preview.before.taskKey)==preview.before&&selected.length()==preview.bytes&&fileSha256(selected)==preview.sha256,MusicError.INCOMPLETE)
        // Always create an owned copy for manual selection, never overwrite an arbitrary document provider.
        val task=AudioExportTask(preview.before.taskKey,preview.before.track,listOf("Моя музыка"),1,
            preview.before.source,preview.before.occurrence)
        val row=shared.publish(task,TransferResult(selected,preview.media,preview.covered),ExportLayout.ROOT+"Восстановленные/",
            catalogueVerified=preview.exact&&preview.before.catalogueVerified){after->
            val stream=journal.startWrite()
            try{stream.write(json.encodeToString(Import(preview.before,after)).toByteArray());journal.finishWrite(stream)}
            catch(e: Exception){journal.failWrite(stream);throw e}
        }
        onPublished()
        withContext(NonCancellable+Dispatchers.IO){
            shared.requireValid(row);store.relinked(preview.before,row);journal.delete();selected.delete()
        }
        return row
    }
}
