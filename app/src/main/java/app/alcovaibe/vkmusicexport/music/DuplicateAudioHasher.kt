package app.alcovaibe.vkmusicexport.music

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.File

/** Only temporary downloads of name/artist matches. Never reads, publishes or deletes shared music. */
internal class DuplicateAudioHasher(context: Context,private val api: MusicApi,
    private val transfer: AudioTransfer=AudioTransfer(),private val checkpoint: DuplicateScanCache?=null,private val progress:(NativeTrack,AudioProgress)->Unit={_,_->}): DuplicateHasher,Closeable {
    private val directory=File(context.cacheDir,"duplicate-check").apply{deleteRecursively();mkdirs()}
    override fun beginScan(owner: Long,tracks: List<NativeTrack>){checkpoint?.begin(owner,tracks)}
    override fun cached(track: NativeTrack)=checkpoint?.get(track)
    override suspend fun signature(access: MusicAccess,track: NativeTrack): DuplicateSignature {
        musicCheck(directory.usableSpace>AudioTransfer.MAX_BYTES*2+8*1024*1024,MusicError.STORAGE)
        var result: TransferResult?=null
        try {
            progress(track,AudioProgress("resolve_media"))
            val url=api.media(access,track)
            result=transfer.download(url,track,directory){progress(track,it)}
            val ctx=currentCoroutineContext()
            progress(track,AudioProgress("payload_hash",result.file.length()))
            val digest=EmbeddedArtwork.payloadDigest(result.file,result.media.extension){ctx.ensureActive()}
            ctx.ensureActive()
            val signature=DuplicateSignature(result.media.extension,digest)
            checkpoint?.save(track,signature,result.file)
            return signature
        }finally{result?.file?.delete()}
    }
    override fun close(){directory.deleteRecursively()}
}
