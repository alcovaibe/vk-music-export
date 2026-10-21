package app.alcovaibe.vkmusicexport.music

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

/** One-time removal of the explicitly retired archive. Never follows links or visits shared Music. IO only. */
internal class LegacyAudioCleanup(context: Context) {
    private val marker=AtomicFile(File(context.noBackupFilesDir,"legacy-audio-cleanup-v1"))
    private val retired=listOf(
        File(context.filesDir,"native-audio"),File(context.cacheDir,"native-audio-transfer"),
        File(context.filesDir,"native-audio-index.json"),File(context.filesDir,"native-audio-index.json.bak"),
        File(context.filesDir,"native-audio-index.json.new")
    )
    fun run(): Int = synchronized(lock) {
        if(runCatching{marker.openRead().use{it.readNBytes(3).contentEquals(byteArrayOf(49,10))}}.getOrDefault(false))return@synchronized 0
        var removed=0
        for(root in retired) {
            if(!Files.exists(root.toPath(),LinkOption.NOFOLLOW_LINKS))continue
            Files.walkFileTree(root.toPath(),object: SimpleFileVisitor<Path>() {
                override fun visitFile(path: Path,attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(path);removed++;return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(path: Path,error: IOException?): FileVisitResult {
                    if(error!=null)throw error
                    Files.delete(path);return FileVisitResult.CONTINUE
                }
            })
        }
        val out=marker.startWrite()
        try{out.write(byteArrayOf(49,10));marker.finishWrite(out)}catch(e: Exception){marker.failWrite(out);throw e}
        removed
    }
    private companion object {val lock=Any()}
}
