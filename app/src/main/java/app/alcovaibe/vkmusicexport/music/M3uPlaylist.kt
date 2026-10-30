package app.alcovaibe.vkmusicexport.music

import java.nio.file.Paths
import java.util.Locale

/** Playlist order is metadata, independent of the stable identity and visible audio filenames. */
internal object M3uPlaylist {
    fun reference(directory: String,path: String): String {
        musicCheck(directory.startsWith("Music/")&&path.startsWith("Music/")&&path.none{it=='\n'||it=='\r'||it=='\u0000'||it=='\\'},MusicError.STORAGE)
        val target=Paths.get(path).normalize()
        musicCheck(!target.isAbsolute&&target.startsWith("Music"),MusicError.STORAGE)
        return "./"+Paths.get(directory).relativize(target).toString().replace('\\','/')
    }
    fun render(directory: String,selected: List<AudioExportTask>,files: Map<String,PublishedAudio>): String=buildString {
        append("#EXTM3U\n#VKME-SELECTED:").append(selected.size).append('\n')
        val ordered=selected.sortedBy{it.position}
        val saved=ordered.count{it.key in files}
        append("#VKME-SAVED:").append(saved).append('\n')
        if(saved!=selected.size)append("#VKME-INCOMPLETE\n")
        for(task in ordered) {
            val file=files[task.key]?:continue
            musicCheck(file.relativePath.startsWith("Music/")&&!file.displayName.contains('/'),MusicError.STORAGE)
            val relative=reference(directory,file.relativePath+file.displayName)
            append("#EXTINF:").append(String.format(Locale.ROOT,"%.3f",file.durationMs/1000.0)).append(',')
                .append((task.track.artist+" - "+task.track.title).replace('\n',' ').replace('\r',' ')).append('\n')
            append(relative).append('\n')
            musicCheck(length<=4*1024*1024,MusicError.TOO_LARGE)
        }
    }
}
