package app.alcovaibe.vkmusicexport.music

import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics
import kotlinx.coroutines.*
import java.io.File

internal class AudioExportEngine(private val store: AudioExportStore,private val shared: SharedAudioStore,
    private val temporary: File,private val api: MusicApi=VkAudioClient(),private val transfer: AudioTransfer=AudioTransfer(),
    private val credential: suspend()->MusicAccess,private val artwork: ArtworkSource=ArtworkLoader(api),private val observe:(String,Map<String,Any?>)->Unit={t,f->Diagnostics.event(t,f)},
    private val accountGuard: suspend()->Unit={}) {
    suspend fun recover() {
        ArtworkRepair(shared.context,store,shared).recover()
        SavedFileRecovery(shared.context,store,shared).let{it.recover();it.discard()}
        LegacyArchiveMigration(shared.context,store,shared).migrate()
        temporary.mkdirs();temporary.listFiles()?.filter{it.isFile}?.forEach{it.delete()}
        reconcile()
        store.recoverItems()
    }
    private suspend fun reconcile() {
        for((task,row) in store.prepared()) {
            val finished=shared.completed(row)
            if(AudioCompleteness.matchesCatalogue(row.durationMs,task.track.duration,row.track.duration)&&finished!=null)store.done(task,finished)
            else{shared.removePending(row.uri);store.clearPrepared(task.key)}
        }
        // Older builds could accept a complete preview manifest as a complete catalogue track.
        // Keep those public files, but report them as incomplete and offer an explicit retry.
        for((task,state)in store.taskRows())if(state=="DONE")store.existing(task.key)?.let{row->
            if(!AudioCompleteness.matchesCatalogue(row.durationMs,task.track.duration,row.track.duration))store.failed(task,MusicError.INCOMPLETE)
        }
        shared.removeAbandonedPending()
    }
    suspend fun run(update: suspend(ExportSnapshot)->Unit) {
        if(!store.beginRun())return
        observe("audio_queue_state",mapOf("state" to "RUNNING"))
        var lastProgress=0L
        suspend fun withArtwork(result: TransferResult,track: NativeTrack): TransferResult {
            AudioExportRuntime.progress(AudioProgress("artwork"))
            return DownloadArtwork(artwork).add(result,track,credential)
        }
        try {
            musicCheck(temporary.isDirectory||temporary.mkdirs(),MusicError.STORAGE)
            // Also reconcile a pause in this process, including cancellation just after publication.
            reconcile()
            val reuse=SavedAudioReuse(store,shared)
            val queue=store.taskRows()
            // DONE is a checkpoint, not proof that a user has kept the file at its old URI.
            // A retry validates saved items locally; only missing/changed ones re-enter the queue.
            val completed=queue.filter{it.second=="DONE"}.map{it.first}
            for((index,task)in completed.withIndex()) {
                currentCoroutineContext().ensureActive();if(!store.running())break
                AudioExportRuntime.progress(AudioProgress("checking_saved",segment=index+1,segments=completed.size))
                try {
                    accountGuard()
                    val local=reuse.existing(task)
                    if(local==null)store.retryMissing(task)else store.done(task,local)
                }catch(e: SavedFileFailure){store.failed(task,e.code)}
                 catch(e: MusicFailure){store.status("PAUSED",e.code);return}
                update(store.snapshot())
            }
            while(store.running()) {
                currentCoroutineContext().ensureActive()
                val task=store.claim()?:break
                update(store.snapshot())
                try {
                    accountGuard()
                    AudioExportRuntime.progress(AudioProgress("checking_saved"))
                    val already=reuse.existing(task)
                    if(already!=null) {
                        store.done(task,already);observe("audio_queue_item",mapOf("outcome" to "already_saved"));continue
                    }
                    var result: TransferResult?=null
                    var resolvedTrack=task.track
                    val output=try {
                        for(attempt in 0..2) {
                            try {
                                // Read the current encrypted access for every track/retry; never persist a signed media URL.
                                val resolved=api.resolve(credential(),task.track);resolvedTrack=resolved.track
                                val downloaded=transfer.download(resolved.url,resolved.track,temporary){progress->
                                    val now=System.nanoTime()
                                    if(now-lastProgress>=300_000_000||progress.stage=="verifying") {
                                        lastProgress=now;AudioExportRuntime.progress(progress)
                                    }
                                }
                                result=downloaded
                                AudioCompleteness.requireComplete(downloaded.media.durationMs,
                                    task.track.duration.takeIf{it>0}?:resolvedTrack.duration,null)
                                break
                            }catch(e: MusicFailure) {
                                val retry=e.code in setOf(MusicError.NETWORK,MusicError.RATE_LIMIT,MusicError.HTTP)||
                                    e is MediaHttpFailure&&e.code==MusicError.UNAVAILABLE
                                if(attempt==2||!retry)throw e
                                observe("audio_queue_retry",mapOf("attempt" to attempt+1,"code" to e.code.name))
                                delay(if(e.code==MusicError.RATE_LIMIT)5000L*(attempt+1) else 1500L*(attempt+1))
                            }
                        }
                        result=withArtwork(result?:throw MusicFailure(MusicError.INCOMPLETE),resolvedTrack)
                        val file=result?:throw MusicFailure(MusicError.INCOMPLETE)
                        // Persist the verified metadata when the queued record had no known duration.
                        val verifiedTask=if(task.track.duration<=0)task.copy(track=resolvedTrack)else task
                        shared.publish(verifiedTask,file,store.directory(task)){store.prepared(task,it)}
                    }finally{withContext(NonCancellable+Dispatchers.IO){result?.file?.delete()}}
                    currentCoroutineContext().ensureActive()
                    store.done(task,output)
                    observe("audio_queue_item",mapOf("outcome" to "saved","format" to output.extension,"bytes" to output.bytes))
                }catch(e: CancellationException){throw e}
                 catch(e: Exception) {
                    val failure=e as? MusicFailure;val code=failure?.code?:MusicError.STORAGE
                    observe("audio_queue_item",mapOf("outcome" to "failed","code" to code.name,"vk_code" to failure?.remoteCode,"kind" to failure?.kind?.name))
                    if(code in setOf(MusicError.ACCESS,MusicError.INVALID_ACCESS,MusicError.CAPTCHA,MusicError.VALIDATION,
                            MusicError.NETWORK,MusicError.HTTP,MusicError.RATE_LIMIT,MusicError.TLS,MusicError.STORAGE,MusicError.TIMEOUT,
                            MusicError.ACCOUNT_CHANGED,MusicError.QUEUE_ACCOUNT)&&
                            !(e is MediaHttpFailure&&code!=MusicError.RATE_LIMIT)) {
                        store.status("PAUSED",code);break
                    }
                    store.failed(task,code)
                }finally{store.releaseRunning();update(store.snapshot())}
            }
            if(!store.snapshot().unfinished) {
                try {SharedPlaylistStore(shared.context,store).publishAll()}
                catch(e: CancellationException){throw e}
                catch(e: Exception){store.sourceFailure(ExportSourceFailure(ExportSource(store.account(),"playlist_file","all"),
                    "Файлы порядка M3U8",(e as? MusicFailure)?.code?:MusicError.STORAGE))}
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
