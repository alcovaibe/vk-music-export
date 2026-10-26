package app.alcovaibe.vkmusicexport.music

import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics
import kotlinx.coroutines.*
import java.io.File

internal class AudioExportEngine(private val store: AudioExportStore,private val shared: SharedAudioStore,
    private val temporary: File,private val api: MusicApi=VkAudioClient(),private val transfer: AudioTransfer=AudioTransfer(),
    private val credential: suspend()->MusicAccess,private val artwork: ArtworkSource=ArtworkLoader(api),private val observe:(String,Map<String,Any?>)->Unit={t,f->Diagnostics.event(t,f)}) {
    suspend fun recover() {
        ArtworkRepair(shared.context,store,shared).recover()
        temporary.mkdirs();temporary.listFiles()?.filter{it.isFile}?.forEach{it.delete()}
        reconcile()
        store.recoverItems()
    }
    private suspend fun reconcile() {
        for((task,row) in store.prepared()) {
            if(shared.valid(row))store.done(task,row) else{shared.removePending(row.uri);store.clearPrepared(task.key)}
        }
        shared.removeAbandonedPending()
    }
    suspend fun run(update: suspend(ExportSnapshot)->Unit) {
        if(!store.beginRun())return
        observe("audio_queue_state",mapOf("state" to "RUNNING"))
        var lastProgress=0L
        try {
            // Also reconcile a pause in this process, including cancellation just after publication.
            reconcile()
            while(store.running()) {
                currentCoroutineContext().ensureActive()
                val task=store.claim()?:break
                update(store.snapshot())
                try {
                    if(task.track.restricted||task.track.duration<=0)throw MusicFailure(MusicError.UNAVAILABLE)
                    val already=store.existing(task.key)
                    if(already!=null&&already.track==task.track&&shared.valid(already)) {
                        store.done(task,already);observe("audio_queue_item",mapOf("outcome" to "already_saved"));continue
                    }
                    var published: PublishedAudio?=null
                    for(copy in store.copies(task.track)) {
                        if(!shared.valid(copy))continue
                        if(copy.coverEmbedded)published=shared.copy(task,copy,store.directory(task)){store.prepared(task,it)}
                        else {
                            val local=File(temporary,"local-copy.audio")
                            var result=TransferResult(local,ProcessedAudio(copy.extension,copy.mime,copy.durationMs))
                            try {
                                shared.copyTo(copy,local)
                                result=DownloadArtwork(artwork).add(result,task.track,credential)
                                published=shared.publish(task,result,store.directory(task)){store.prepared(task,it)}
                            }finally{local.delete();result.file.delete()}
                        }
                        observe("audio_queue_item",mapOf("outcome" to "local_copy"));break
                    }
                    if(published==null) {
                        var result: TransferResult?=null
                        try {
                            for(attempt in 0..2) {
                                try {
                                    // Read the current encrypted access for every track/retry; never persist a signed media URL.
                                    val url=api.media(credential(),task.track)
                                    result=transfer.download(url,task.track,temporary){progress->
                                        val now=System.nanoTime()
                                        if(now-lastProgress>=300_000_000||progress.stage=="verifying") {
                                            lastProgress=now;AudioExportRuntime.progress(progress)
                                        }
                                    }
                                    break
                                }catch(e: MusicFailure) {
                                    if(attempt==2||e.code !in setOf(MusicError.NETWORK,MusicError.RATE_LIMIT,MusicError.HTTP))throw e
                                    observe("audio_queue_retry",mapOf("attempt" to attempt+1,"code" to e.code.name))
                                    delay(if(e.code==MusicError.RATE_LIMIT)5000L*(attempt+1) else 1500L*(attempt+1))
                                }
                            }
                            result=DownloadArtwork(artwork).add(result?:throw MusicFailure(MusicError.INCOMPLETE),task.track,credential)
                            val file=result?:throw MusicFailure(MusicError.INCOMPLETE)
                            published=shared.publish(task,file,store.directory(task)){store.prepared(task,it)}
                        }finally{withContext(NonCancellable+Dispatchers.IO){result?.file?.delete()}}
                    }
                    currentCoroutineContext().ensureActive()
                    val output=published?:throw MusicFailure(MusicError.STORAGE)
                    store.done(task,output)
                    observe("audio_queue_item",mapOf("outcome" to "saved","format" to output.extension,"bytes" to output.bytes))
                }catch(e: CancellationException){throw e}
                 catch(e: Exception) {
                    val failure=e as? MusicFailure;val code=failure?.code?:MusicError.STORAGE
                    observe("audio_queue_item",mapOf("outcome" to "failed","code" to code.name,"vk_code" to failure?.remoteCode,"kind" to failure?.kind?.name))
                    if(code in setOf(MusicError.ACCESS,MusicError.INVALID_ACCESS,MusicError.CAPTCHA,MusicError.VALIDATION,
                            MusicError.NETWORK,MusicError.HTTP,MusicError.RATE_LIMIT,MusicError.TLS,MusicError.STORAGE,MusicError.TIMEOUT)) {
                        store.status("PAUSED",code);break
                    }
                    store.failed(task,code)
                }finally{store.releaseRunning();update(store.snapshot())}
            }
            val final=store.snapshot()
            if(!final.unfinished)store.status(if(final.failed>0||final.sourceIssues>0)"FINISHED_WITH_ERRORS" else "FINISHED")
        }catch(e: CancellationException){throw e}
         finally{withContext(NonCancellable+Dispatchers.IO){
             store.releaseRunning();if(store.running())store.status("PAUSED")
             update(store.snapshot());observe("audio_queue_state",mapOf("state" to store.snapshot().status))
         }}
    }
}
