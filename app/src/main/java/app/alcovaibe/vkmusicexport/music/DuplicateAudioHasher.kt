package app.alcovaibe.vkmusicexport.music

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.File

/** Only temporary downloads of name/artist matches. Never reads, publishes or deletes shared music. */
internal class DuplicateAudioHasher(context: Context,private val api: MusicApi,
    private val transfer: AudioTransfer=AudioTransfer(),private val progress:(NativeTrack,AudioProgress)->Unit={_,_->}): DuplicateHasher,Closeable {
    private val directory=File(context.cacheDir,"duplicate-check").apply{deleteRecursively();mkdirs()}
    override suspend fun signature(access: MusicAccess,track: NativeTrack): DuplicateSignature {
        musicCheck(directory.usableSpace>AudioTransfer.MAX_BYTES*2+8*1024*1024,MusicError.STORAGE)
        var result: TransferResult?=null
        try {
            val url=api.media(access,track)
            result=transfer.download(url,track,directory){progress(track,it)}
            val ctx=currentCoroutineContext()
            val digest=EmbeddedArtwork.payloadDigest(result.file,result.media.extension){ctx.ensureActive()}
            ctx.ensureActive()
            return DuplicateSignature(result.media.extension,digest)
        }finally{result?.file?.delete()}
    }
    override fun close(){directory.deleteRecursively()}
}
