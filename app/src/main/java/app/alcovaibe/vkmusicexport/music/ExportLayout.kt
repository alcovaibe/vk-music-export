package app.alcovaibe.vkmusicexport.music

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.text.Normalizer

@Serializable internal data class AudioExportTask(val key: String,val track: NativeTrack,val folders: List<String>,val position: Int)
@Serializable internal data class PublishedAudio(val taskKey: String,val track: NativeTrack,val uri: String,val displayName: String,
    val relativePath: String,val extension: String,val mime: String,val durationMs: Long,val bytes: Long,val sha256: String)

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
    fun name(task: AudioExportTask,extension: String): String {
        musicCheck(extension in setOf("mp3","m4a"),MusicError.FORMAT)
        val id=component(task.track.id,48)
        return "${task.position.toString().padStart(5,'0')} - ${component(task.track.artist+" — "+task.track.title,140)} [$id].$extension"
    }
    fun relative(task: AudioExportTask)=ROOT+task.folders.joinToString("/")+"/"
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
        val folder=component(list.title)+" [${list.owner}_${list.id}]"
        return page.tracks.mapIndexed{i,track->task(track,listOf(kind.folder,folder),i+1)}
    }
}
