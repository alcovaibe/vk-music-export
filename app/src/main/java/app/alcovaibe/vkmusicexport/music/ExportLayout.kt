package app.alcovaibe.vkmusicexport.music

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.text.Normalizer

@Serializable internal data class ExportSource(val account: Long,val kind: String,val id: String) {
    val key get()=MessageDigest.getInstance("SHA-256").digest(listOf(account.toString(),kind,id)
        .joinToString("\u0000").toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
}
@Serializable internal data class AudioExportTask(val key: String,val track: NativeTrack,val folders: List<String>,val position: Int,
    val source: ExportSource?=null,val occurrence: Int=1)
@Serializable internal data class PublishedAudio(val taskKey: String,val track: NativeTrack,val uri: String,val displayName: String,
    val relativePath: String,val extension: String,val mime: String,val durationMs: Long,val bytes: Long,val sha256: String,val coverEmbedded: Boolean=false,val recoveredLocation: Boolean=false,
    val source: ExportSource?=null,val occurrence: Int=1,
    val catalogueVerified: Boolean=relativePath!=ExportLayout.ROOT+"Восстановленные/",val textTitle: String?=null)

@Serializable internal data class ExportSourceFailure(val source: ExportSource,val title: String,val error: MusicError,
    val category: PlaylistKind?=null)

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
    fun task(track: NativeTrack,folders: List<String>,position: Int,
        source: ExportSource=ExportSource(0,"legacy",folders.joinToString("/")),occurrence: Int=1): AudioExportTask {
        musicCheck(position>0&&occurrence>0&&source.account>=0&&folders.size in 1..3&&folders.all{it==component(it,180)},MusicError.STORAGE)
        val raw="v2\u0000"+source.key+"\u0000"+track.id+"\u0000"+occurrence
        val key=MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
        return AudioExportTask(key,track,folders,position,source,occurrence)
    }
    fun name(task: AudioExportTask,extension: String,duplicate: Int=1)=name(task.track,extension,duplicate)
    fun name(track: NativeTrack,extension: String,duplicate: Int=1): String {
        musicCheck(extension in setOf("mp3","m4a"),MusicError.FORMAT)
        musicCheck(duplicate>0,MusicError.STORAGE)
        val suffix=if(duplicate==1)"" else " ($duplicate)"
        // Reserve room for the version even when a long artist/title needs truncating.
        val version=track.subtitle?.takeIf{track.displayTitle!=track.title}?.let{" (${component(it,96)})"}.orEmpty()
        val base=if(version.isEmpty())component(track.artist+" — "+track.displayTitle,180)
            else component(track.artist+" — "+track.title,180-version.toByteArray(Charsets.UTF_8).size)+version
        return "$base$suffix.$extension"
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
    fun library(page: NativePage,albums: Boolean=true,account: Long=0): List<AudioExportTask> {
        val tasks=mutableListOf<AudioExportTask>();val albumPositions=mutableMapOf<String,Int>()
        val occurrences=mutableMapOf<String,Int>()
        page.tracks.forEachIndexed{i,track->
            val occurrence=(occurrences[track.id]?:0)+1;occurrences[track.id]=occurrence
            tasks.add(task(track,listOf("Моя музыка"),i+1,ExportSource(account,"library","my"),occurrence))
            if(albums)track.album?.let{album->
                val n=(albumPositions[album.id]?:0)+1;albumPositions[album.id]=n
                tasks.add(task(track,listOf("Альбомы",component(album.title)+" ["+component(album.id,48)+"]"),n,
                    ExportSource(account,"library_album",album.id),occurrence))
            }
        }
        return tasks
    }
    fun playlist(page: NativePage,list: NativePlaylist,kind: PlaylistKind=list.kind,account: Long=0): List<AudioExportTask> {
        val folder=component(list.title)+" [${list.owner}_${list.id}]"
        val occurrences=mutableMapOf<String,Int>()
        return page.tracks.mapIndexed{i,track->
            val occurrence=(occurrences[track.id]?:0)+1;occurrences[track.id]=occurrence
            task(track,listOf(kind.folder,folder),i+1,ExportSource(account,"catalog",list.key),occurrence)
        }
    }

    /** Old directory identities retained source IDs in a final suffix, even after visible names changed. */
    fun legacySource(folders: List<String>,source: ExportSource): Boolean=when(source.kind) {
        "library"->folders==listOf("Моя музыка")
        "catalog"->{
            val ids=Regex("playlist:(-?[0-9]+):([0-9]+)").matchEntire(source.id)
            ids!=null&&folders.size==2&&folders.first() in setOf("Плейлисты","Альбомы")&&
                folders.last().endsWith(" [${ids.groupValues[1]}_${ids.groupValues[2]}]")
        }
        "library_album"->folders.size==2&&folders.first()=="Альбомы"&&folders.last().endsWith(" [${component(source.id,48)}]")
        else->false
    }
}
