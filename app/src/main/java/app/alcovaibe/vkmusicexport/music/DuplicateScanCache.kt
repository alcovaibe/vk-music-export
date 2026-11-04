package app.alcovaibe.vkmusicexport.music

import android.content.Context
import java.io.Closeable
import java.io.File

/** IO-only checkpoint of completed checks, bound to one account and per-track audio metadata.
 * Neither tokens nor CDN URLs nor complete audio files are retained.
 * This is discovery evidence only: removal always downloads both records afresh.
 */
internal class DuplicateScanCache(context: Context,private val wall: ()->Long=System::currentTimeMillis,
    private val store: DuplicateReceiptStore=DuplicateReceiptStore(context)): Closeable {
    private var lease: DuplicateCacheLease?=null
    fun count(): Int=synchronized(lock){store.count(wall(),lease)}
    fun begin(owner: Long,tracks: List<NativeTrack>)=synchronized(lock) {
        musicCheck(owner>0,MusicError.ACCESS)
        lease=store.begin(owner,tracks,wall())
    }
    fun get(track: NativeTrack): DuplicateSignature?=synchronized(lock) {
        val current=lease?:return@synchronized null
        store.get(current,DuplicateReceiptKeys.key(track),wall())
    }
    fun save(track: NativeTrack,signature: DuplicateSignature,audio: File)=synchronized(lock) {
        musicCheck(signature.version==2&&signature.codec in setOf("mp3","m4a")&&
            DuplicateReceiptKeys.hex.matches(signature.sha256)&&audio.isFile,MusicError.STORAGE)
        val current=lease?:throw MusicFailure(MusicError.STORAGE)
        store.save(current,DuplicateReceiptKeys.key(track),signature,wall())
    }
    fun clear()=synchronized(lock){store.clear();lease=null}
    override fun close()=synchronized(lock){store.close()}
    private companion object {val lock=Any()}
}
