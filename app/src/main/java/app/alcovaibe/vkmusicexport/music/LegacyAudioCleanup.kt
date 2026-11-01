package app.alcovaibe.vkmusicexport.music

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

/** Only abandoned transfer bytes are disposable. Finished legacy audio and every index copy are retained. */
internal class LegacyAudioCleanup(context: Context) {
    private val marker=AtomicFile(File(context.noBackupFilesDir,"legacy-transfer-cleanup-v2"))
    private val retired=listOf(File(context.cacheDir,"native-audio-transfer"))
    fun run(): Int = synchronized(lock) {
        if(runCatching{marker.openRead().use{it.readAtMost(3).contentEquals(byteArrayOf(49,10))}}.getOrDefault(false))return@synchronized 0
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
