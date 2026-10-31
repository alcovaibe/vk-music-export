package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable internal data class TrackMetadataTarget(val taskKey: String,val uri: String,val trackId: String,val name: String)
@Serializable internal data class TrackMetadataRun(val owner: Long,val targets: List<TrackMetadataTarget>,val next: Int=0,
    val updated: Int=0,val current: Int=0,val unavailable: Int=0,val skipped: Int=0,val failed: Int=0,
    val issues: List<TrackMetadataIssue> = emptyList(),val error: MusicError?=null) {
    val unfinished get()=next<targets.size
    fun state(visible: Boolean=true,running: Boolean=false)=TrackMetadataState(visible=visible,running=running,total=targets.size,
        processed=next,updated=updated,current=current,unavailable=unavailable,skipped=skipped,failed=failed,
        issues=issues,resumable=unfinished,message=if(unfinished)"Проверка сохранена. Можно продолжить с необработанной записи." else "Проверка завершена. Звук и обложки сохранены.")
    companion object {
        fun start(owner: Long,files: List<PublishedAudio>)=TrackMetadataRun(owner,files.distinctBy{it.uri}.map{
            TrackMetadataTarget(it.taskKey,it.uri,it.track.id,it.displayName)
        })
    }
}

/** One durable cursor per account. Authentication/network failures leave the current item pending. */
internal class TrackMetadataBatch(private val api: MusicApi,private val access:suspend()->MusicAccess,
    private val find:(TrackMetadataTarget)->PublishedAudio?,private val verify:suspend(PublishedAudio)->Unit,
    private val update:suspend(PublishedAudio,NativeTrack)->Boolean,private val recover:suspend()->Unit,
    private val save:(TrackMetadataRun)->Unit,private val progress:(TrackMetadataRun,String?)->Unit={_,_->},
    private val metadataSource:(PublishedAudio)->String?={null}) {
    suspend fun run(initial: TrackMetadataRun): TrackMetadataRun {
        musicCheck(initial.owner>0&&initial.next in 0..initial.targets.size,MusicError.STORAGE)
        var run=initial.copy(error=null)
        save(run);progress(run,null)
        val metadata=mutableMapOf<String,NativeTrack?>()
        while(run.unfinished) {
            val target=run.targets[run.next]
            try {
                access();progress(run,target.name)
                val row=find(target)
                val issue=when {
                    row==null->"Запись файла больше не учитывается приложением."
                    !row.catalogueVerified->"Локальная замена: соответствие записи ВК не подтверждено."
                    row.source?.account?.let{it>0&&it!=run.owner}==true->"Файл сохранён из другого аккаунта ВК."
                    else->null
                }
                if(issue!=null)run=run.copy(skipped=run.skipped+1,issues=run.issues+TrackMetadataIssue(target.name,issue))
                else {
                    val saved=row!!
                    musicCheck(saved.track.id==target.trackId,MusicError.INCOMPLETE)
                    verify(saved)
                    val replacement=metadataSource(saved)
                    val reference=replacement?:saved.track.id
                    if(!metadata.containsKey(reference))metadata[reference]=try{api.metadata(access(),reference)}
                        catch(e: MusicFailure){if(e.code in setOf(MusicError.UNAVAILABLE,MusicError.DENIED))null else throw e}
                    access() // Follow verified renewals; reject a new login before touching the file.
                    val fresh=metadata[reference]?.let{musicCheck(it.id==reference,MusicError.RESPONSE);if(replacement!=null)MetadataReplacementText.merge(saved.track,it)else it}
                    run=if(fresh==null)run.copy(unavailable=run.unavailable+1,issues=run.issues+TrackMetadataIssue(target.name,
                        "ВК не вернул запись по сохранённому ID или не разрешил доступ к ней.",target,replacement))
                    else if(update(saved,fresh))run.copy(updated=run.updated+1)else run.copy(current=run.current+1)
                }
            }catch(e: SavedFileFailure) {
                recover();run=run.copy(skipped=run.skipped+1,issues=run.issues+TrackMetadataIssue(target.name,e.reason.label))
            }catch(e: CancellationException){throw e}
             catch(e: Exception) {
                recover()
                val code=(e as? MusicFailure)?.code?:MusicError.STORAGE
                if(code in setOf(MusicError.ACCESS,MusicError.INVALID_ACCESS,MusicError.ACCOUNT_CHANGED,MusicError.METHOD,MusicError.CAPTCHA,MusicError.VALIDATION,
                        MusicError.RATE_LIMIT,MusicError.STORAGE,MusicError.NETWORK,MusicError.HTTP,MusicError.TLS,MusicError.TIMEOUT)||e is java.io.IOException) {
                    run=run.copy(error=code);save(run);progress(run,target.name);throw e
                }
                run=run.copy(failed=run.failed+1,issues=run.issues+TrackMetadataIssue(target.name,code.label))
            }
            run=run.copy(next=run.next+1);save(run);progress(run,null)
        }
        return run
    }
}
