package app.alcovaibe.vkmusicexport.music

import java.nio.file.Paths
import java.util.Locale

/** Playlist order is metadata, independent of the stable identity and visible audio filenames. */
internal object M3uPlaylist {
    fun render(directory: String,selected: List<AudioExportTask>,files: Map<String,PublishedAudio>): String=buildString {
        append("#EXTM3U\n#VKME-SELECTED:").append(selected.size).append('\n')
        val ordered=selected.sortedBy{it.position}
        val saved=ordered.count{it.key in files}
        append("#VKME-SAVED:").append(saved).append('\n')
        if(saved!=selected.size)append("#VKME-INCOMPLETE\n")
        for(task in ordered) {
            val file=files[task.key]?:continue
            musicCheck(file.relativePath.startsWith("Music/")&&!file.displayName.contains('/'),MusicError.STORAGE)
            val relative=Paths.get(directory).relativize(Paths.get(file.relativePath+file.displayName)).toString().replace('\\','/')
            append("#EXTINF:").append(String.format(Locale.ROOT,"%.3f",file.durationMs/1000.0)).append(',')
                .append((task.track.artist+" - "+task.track.title).replace('\n',' ').replace('\r',' ')).append('\n')
            append("./").append(relative).append('\n')
            musicCheck(length<=4*1024*1024,MusicError.TOO_LARGE)
        }
    }
}
