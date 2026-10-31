package app.alcovaibe.vkmusicexport.music

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.File

/** Hash verified app-owned copies or temporary downloads. Never changes or deletes shared music. */
internal class DuplicateAudioHasher(private val context: Context,private val api: MusicApi,
    private val transfer: AudioTransfer=AudioTransfer(),private val checkpoint: DuplicateScanCache?=null,private val progress:(NativeTrack,AudioProgress)->Unit={_,_->},private val reuseSaved: Boolean=false): DuplicateHasher,Closeable {
    private val directory=File(context.cacheDir,"duplicate-check").apply{deleteRecursively();mkdirs()}
    private var scanOwner=0L
    override fun beginScan(owner: Long,tracks: List<NativeTrack>){scanOwner=owner;checkpoint?.begin(owner,tracks)}
    override fun cached(track: NativeTrack)=checkpoint?.get(track)
    override suspend fun signature(access: MusicAccess,track: NativeTrack): DuplicateSignature {
        if(reuseSaved&&scanOwner>0)savedSignature(track)?.let{return it}
        AudioStorage.requireSpace(directory)
        var result: TransferResult?=null
        try {
            progress(track,AudioProgress("resolve_media"))
            val resolved=api.resolve(access,track)
            result=transfer.download(resolved.url,resolved.track,directory){progress(track,it)}
            val ctx=currentCoroutineContext()
            progress(track,AudioProgress("payload_hash",result.file.length()))
            val digest=EmbeddedArtwork.audioFingerprint(result.file,result.media.extension){ctx.ensureActive()}
            ctx.ensureActive()
            val signature=DuplicateSignature(result.media.extension,digest)
            checkpoint?.save(track,signature,result.file)
            return signature
        }finally{result?.file?.delete()}
    }
    private suspend fun savedSignature(track: NativeTrack): DuplicateSignature? {
        val shared=SharedAudioStore(context)
        val rows=AudioExportStore(context).use{it.savedLibraryCopies(track,scanOwner)}.distinctBy{it.uri}
        for(row in rows) {
            currentCoroutineContext().ensureActive()
            val check=shared.inspect(row)
            if(check.reason !in setOf(SavedFileReason.OK,SavedFileReason.LOCATION_CHANGED))continue
            val actual=check.actual?:row
            val file=File(directory,"saved.${row.extension}")
            try {
                AudioStorage.requireSpace(directory)
                progress(track,AudioProgress("saved_hash"))
                shared.copyTo(actual,file)
                // Validate the copied bytes too, so a concurrent edit cannot become a receipt.
                musicCheck(fileSha256(file)==row.sha256,MusicError.INCOMPLETE)
                val ctx=currentCoroutineContext()
                val digest=EmbeddedArtwork.audioFingerprint(file,row.extension){ctx.ensureActive()}
                ctx.ensureActive()
                val signature=DuplicateSignature(row.extension,digest)
                checkpoint?.save(track,signature,file)
                return signature
            }finally{file.delete()}
        }
        return null
    }
    override fun close(){directory.deleteRecursively()}
}
