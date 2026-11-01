package app.alcovaibe.vkmusicexport.music

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

internal data class LegacyArchiveFile(val name: String,val bytes: Long)
internal data class LegacyArchiveState(val files: List<LegacyArchiveFile> = emptyList(),val migrated: Int=0,val message: String?=null)
internal object LegacyArchiveRuntime {
    val mutable=MutableStateFlow(LegacyArchiveState())
    val state=mutable.asStateFlow()
}

/** Migration of 0.8.x's actual index format. Originals are never deleted, including unindexed files. */
internal class LegacyArchiveMigration(private val context: Context,private val store: AudioExportStore,private val shared: SharedAudioStore) {
    @Serializable private data class OldFile(val fileName: String,val track: NativeTrack,val extension: String,
        val mime: String,val durationMs: Long,val bytes: Long,val sha256: String,val savedAt: String="")
    private val json=Json{ignoreUnknownKeys=true;encodeDefaults=true}
    suspend fun migrate() {
        val files=list(context)
        if(files.isEmpty()){LegacyArchiveRuntime.mutable.value=LegacyArchiveState();return}
        LegacyArchiveRuntime.mutable.value=LegacyArchiveState(files,message="Старые аудиофайлы сохранены. Проверяем возможность переноса.")
        var migrated=0
        try {
            val index=File(context.filesDir,"native-audio-index.json")
            musicCheck(listOf(index,File(index.path+".bak"),File(index.path+".new")).none{Files.isSymbolicLink(it.toPath())},MusicError.STORAGE)
            // AtomicFile.openRead may restore/delete .bak/.new. Migration must only read
            // the last committed snapshot and leave every original index variant intact.
            val backup=File(index.path+".bak")
            val committed=if(Files.isRegularFile(backup.toPath(),LinkOption.NOFOLLOW_LINKS))backup else index
            val bytes=committed.inputStream().use{it.readBoundedBytes(131073)}
            musicCheck(bytes.size<=131072,MusicError.STORAGE)
            val rows=json.decodeFromString<List<OldFile>>(strictUtf8(bytes))
            musicCheck(rows.size<=5,MusicError.STORAGE)
            for(row in rows) {
                currentCoroutineContext().ensureActive()
                val input=original(context,row.fileName)
                musicCheck(row.extension in setOf("mp3","m4a")&&input.extension==row.extension&&
                    row.mime==(if(row.extension=="mp3")"audio/mpeg" else "audio/mp4"),MusicError.FORMAT)
                musicCheck(row.bytes>0&&row.durationMs>0&&
                    Regex("[0-9a-f]{64}").matches(row.sha256)&&input.length()==row.bytes&&fileSha256(input)==row.sha256,MusicError.INCOMPLETE)
                val task=ExportLayout.task(row.track,listOf("Восстановленные","Из старой версии"),1,
                    ExportSource(0,"legacy_archive",row.fileName+":"+row.sha256))
                val checkpoint="legacy-import:"+task.key
                val prior=store.checkpoint(checkpoint)?.let{runCatching{json.decodeFromString<PublishedAudio>(it)}.getOrNull()}
                val finished=prior?.let{shared.completed(it)}
                val output=if(finished!=null)finished.also{store.checkpoint(checkpoint,json.encodeToString(it))} else {
                    prior?.let{shared.removePending(it.uri)}
                    val covered=runCatching{EmbeddedArtwork.hasCover(input,row.extension)}.getOrDefault(false)
                    shared.publish(task,TransferResult(input,ProcessedAudio(row.extension,row.mime,row.durationMs),covered),store.directory(task)){
                        store.checkpoint(checkpoint,json.encodeToString(it))
                    }
                }
                shared.requireValid(output);store.imported(output);migrated++
                LegacyArchiveRuntime.mutable.value=LegacyArchiveState(files,migrated,
                    "Перенесено: $migrated. Оригиналы старой версии сохранены.")
            }
            LegacyArchiveRuntime.mutable.value=LegacyArchiveState(files,migrated,
                "Перенесено в Music/VK Music Export/Восстановленные/Из старой версии: $migrated. Оригиналы сохранены; любую старую запись можно скопировать отдельно.")
        }catch(e: CancellationException){throw e}
         catch(_: Exception){LegacyArchiveRuntime.mutable.value=LegacyArchiveState(files,migrated,
            "Перенесено: $migrated. Остальные старые файлы и индекс сохранены без изменений. Освободи место и повтори запуск либо сохрани файлы отдельно.")}
    }
    companion object {
        private val allowed=Regex("[A-Za-z0-9_. -]{1,180}\\.(mp3|m4a)",RegexOption.IGNORE_CASE)
        fun list(context: Context): List<LegacyArchiveFile> {
            val root=File(context.filesDir,"native-audio")
            if(!Files.isDirectory(root.toPath(),LinkOption.NOFOLLOW_LINKS))return emptyList()
            return root.listFiles().orEmpty().filter{allowed.matches(it.name)&&Files.isRegularFile(it.toPath(),LinkOption.NOFOLLOW_LINKS)}
                .map{LegacyArchiveFile(it.name,it.length())}.sortedBy{it.name}
        }
        private fun original(context: Context,name: String): File {
            musicCheck(allowed.matches(name),MusicError.STORAGE)
            val root=File(context.filesDir,"native-audio")
            val file=File(root,name)
            musicCheck(Files.isDirectory(root.toPath(),LinkOption.NOFOLLOW_LINKS)&&
                Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS)&&file.canonicalFile.parentFile==root.canonicalFile,MusicError.STORAGE)
            return file
        }
        suspend fun export(context: Context,name: String,uri: Uri) {
            musicCheck(uri.scheme=="content",MusicError.STORAGE)
            val source=original(context,name)
            val expected=source.length()
            musicCheck(expected>0,MusicError.INCOMPLETE)
            val output=context.contentResolver.openOutputStream(uri,"wt")?:throw MusicFailure(MusicError.STORAGE)
            output.use{out->source.inputStream().use{input->
                val buffer=ByteArray(65536);var count=0L
                while(true){currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break
                    count+=n;musicCheck(count<=expected,MusicError.INCOMPLETE);out.write(buffer,0,n)}
                musicCheck(count==expected,MusicError.INCOMPLETE);out.flush()
            }}
        }
    }
}
