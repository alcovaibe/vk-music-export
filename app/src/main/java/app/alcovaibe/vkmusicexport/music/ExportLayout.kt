package app.alcovaibe.vkmusicexport.music

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.text.Normalizer

@Serializable internal data class AudioExportTask(val key: String,val track: NativeTrack,val folders: List<String>,val position: Int)
@Serializable internal data class PublishedAudio(val taskKey: String,val track: NativeTrack,val uri: String,val displayName: String,
    val relativePath: String,val extension: String,val mime: String,val durationMs: Long,val bytes: Long,val sha256: String,val coverEmbedded: Boolean=false,val recoveredLocation: Boolean=false)

internal object ExportLayout {
    const val ROOT="Music/VK Music Export/"
    fun component(raw: String,maxBytes: Int=110): String {
        val clean=Normalizer.normalize(raw,Normalizer.Form.NFC).filter{
            !Character.isISOControl(it)&&Character.getType(it)!=Character.FORMAT.toInt()&&it !in "/\\:*?\"<>|"
        }.replace(Regex("\\s+")," ").trim(' ','.')
        val result=StringBuilder()
        for(point in clean.codePoints().toArray()) {
            val part=String(Character.toChars(point));if((result.toString()+part).toByteArray(Charsets.UTF_8).size>maxBytes)break
            result.append(part)
        }
        return result.toString().trimEnd(' ','.').ifEmpty{"Без названия"}
    }
    fun task(track: NativeTrack,folders: List<String>,position: Int): AudioExportTask {
        musicCheck(position>0&&folders.size in 1..3&&folders.all{it==component(it,180)},MusicError.STORAGE)
        val raw=folders.joinToString("/")+"\u0000"+track.id+"\u0000"+position
        val key=MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
        return AudioExportTask(key,track,folders,position)
    }
    fun name(task: AudioExportTask,extension: String,duplicate: Int=1): String {
        musicCheck(extension in setOf("mp3","m4a"),MusicError.FORMAT)
        musicCheck(duplicate>0,MusicError.STORAGE)
        val suffix=if(duplicate==1)"" else " ($duplicate)"
        return "${component(task.track.artist+" — "+task.track.title,180)}$suffix.$extension"
    }
    // Keep the old task identity, including its source ID, for saved-file reuse and old queues.
    // Only remove our final generated suffix; brackets/numbers in the original title remain.
    fun displayFolders(task: AudioExportTask): List<String> = task.folders.mapIndexed{i,folder->
        if(i==1&&task.folders.size==2&&task.folders.first() in setOf("Альбомы","Плейлисты"))
            component(folder.replace(Regex(" \\[(?:-?\\d+_\\d+|album\\d+)\\]$"),""),180)
        else folder
    }
    fun relative(task: AudioExportTask,duplicate: Int=1): String {
        musicCheck(duplicate>0,MusicError.STORAGE)
        val folders=displayFolders(task).toMutableList()
        if(duplicate>1)folders[folders.lastIndex]="${component(folders.last(),150)} ($duplicate)"
        return ROOT+folders.joinToString("/")+"/"
    }
    fun library(page: NativePage,albums: Boolean=true): List<AudioExportTask> {
        val tasks=mutableListOf<AudioExportTask>();val albumPositions=mutableMapOf<String,Int>()
        page.tracks.forEachIndexed{i,track->
            tasks.add(task(track,listOf("Моя музыка"),i+1))
            if(albums)track.album?.let{album->
                val n=(albumPositions[album.id]?:0)+1;albumPositions[album.id]=n
                tasks.add(task(track,listOf("Альбомы",component(album.title)+" ["+component(album.id,48)+"]"),n))
            }
        }
        return tasks
    }
    fun playlist(page: NativePage,list: NativePlaylist,kind: PlaylistKind=list.kind): List<AudioExportTask> {
        musicCheck(kind!=PlaylistKind.UNKNOWN,MusicError.COLLECTION_TYPE)
        val folder=component(list.title)+" [${list.owner}_${list.id}]"
        return page.tracks.mapIndexed{i,track->task(track,listOf(kind.folder,folder),i+1)}
    }
}
