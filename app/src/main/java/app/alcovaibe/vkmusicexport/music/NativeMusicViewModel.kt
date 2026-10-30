package app.alcovaibe.vkmusicexport.music

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private object NativeMusicGate {val mutex=Mutex()}

internal data class NativeMusicState(val initialized: Boolean=false,val configured: Boolean=false,val busy: Boolean=false,
    val stage: String="",val error: MusicError?=null,val message: String?=null,val probe: NativeProbe?=null,
    val catalog: NativeCatalog?=null,val catalogError: MusicError?=null,val page: NativePage?=null,val selected: NativePlaylist?=null,
    val authPhase: DeviceAuthPhase?=null,val authIssue: DeviceAuthIssue?=null,
    val authCodeIssue: DeviceCodeIssue?=null,val authCodePending: Boolean=false,
    val stopping: Boolean=false,val waitingForSlot: Boolean=false,val pageOffset: Int=0,val catalogOffset: Int=0,
    val preparing: String?=null,val catalogSection: CatalogSection=CatalogSection.LIBRARY,
    val catalogKinds: Map<String,PlaylistKind> = emptyMap(),val accessDamaged: Boolean=false)

internal class NativeMusicViewModel @JvmOverloads constructor(application: Application,
    private val api: MusicApi=VkAudioClient(sessions=MusicSessions.get(application)),private val deviceAuth: DeviceAuthApi=VkDeviceAuthClient(),
    private val authClock: ()->Long={android.os.SystemClock.elapsedRealtime()},private val authPollMs: Long=5000,
    private val authBrowser: (android.content.Context,String)->Boolean=::openDeviceAuthChrome,
    private val lookupImages:((ArtworkMatch)->ArtworkSource)?=null): AndroidViewModel(application) {
    private val vault=MusicAccessStore(application)
    private val sessions=if(api is VkAudioClient)MusicSessions.get(application) else null
    val sessionState=sessions?.state?:MutableStateFlow(MusicSessionState()).asStateFlow()
    private var sessionMonitor: Job?=null
    private val operations=NativeOperationJournal(application)
    private val _state=MutableStateFlow(NativeMusicState());val state=_state.asStateFlow()
    private val _downloadsOpen=MutableStateFlow(false);val downloadsOpen=_downloadsOpen.asStateFlow()
    private val _queueAccount=MutableStateFlow<Long?>(null);val queueAccount=_queueAccount.asStateFlow()
    private var pendingQueueRetry=false
    fun dismissQueueAccount(){_queueAccount.value=null;pendingQueueRetry=false}
    fun confirmQueueAccount() {
        val owner=_queueAccount.value?:return
        _queueAccount.value=null;val retry=pendingQueueRetry;pendingQueueRetry=false
        run("queue_bind"){g->withContext(Dispatchers.IO){
            active(g);AudioExportRuntime.bindAccount(getApplication(),owner);active(g);startDownloads(retry)
        }}
    }
    fun showDownloads(){Diagnostics.button("audio_downloads_open");_downloadsOpen.value=true}
    fun dismissDownloads(){Diagnostics.button("audio_downloads_dismiss");_downloadsOpen.value=false}
    private suspend fun startDownloads(retry: Boolean=false) {
        // Presentation state only: dismissing the sheet never controls the UIDT job.
        _downloadsOpen.value=true
        AudioExportRuntime.start(getApplication(),retry)
    }
    private val _artwork=MutableStateFlow(ArtworkState());val artwork=_artwork.asStateFlow()
    fun showArtwork(){if(_state.value.initialized&&!_state.value.busy)_artwork.update{it.copy(visible=true)}}
    fun dismissArtwork(){_artwork.update{it.copy(visible=false)}}
    fun addSavedArtwork() {
        if(!_artwork.value.visible||_artwork.value.running)return
        run("artwork"){g->
            val diagnostic=ArtworkDiagnostics{type,fields->Diagnostics.event(type,fields)}
            var outcome="STOPPED"
            _artwork.value=ArtworkState(visible=true,running=true)
            try {
                withContext(Dispatchers.IO) {
                    AudioExportRuntime.editSaved(getApplication()) {
                        AudioExportStore(getApplication()).use{store->
                            val shared=SharedAudioStore(getApplication())
                            val repair=ArtworkRepair(getApplication(),store,shared,onStep={diagnostic.step=it})
                            diagnostic.step=ArtworkStep.RECOVERY;repair.recover()
                            val saved=store.savedFiles()
                            val ready=saved.filter{it.coverEmbedded}.map{it.uri}.toSet()
                            val files=saved.filter{it.uri !in ready}.distinctBy{it.uri}
                            _artwork.update{it.copy(total=files.size,readySkipped=ready.size)}
                            val source=ArtworkLoader(api,onAttempt=diagnostic::candidate,onStep={diagnostic.step=it})
                            for((index,row) in files.withIndex()) {
                                active(g);diagnostic.item=index+1;diagnostic.step=ArtworkStep.VERIFY_FILE
                                _artwork.update{it.copy(current="${row.track.artist} — ${row.track.title}")}
                                try {
                                    val changed=repair.add(row,source){sessions?.current()?:access()}
                                    val marked=store.existing(row.taskKey)?.coverEmbedded==true
                                    _artwork.update{when{changed->it.copy(added=it.added+1);marked->it.copy(existing=it.existing+1);else->it.copy(missing=it.missing+1,skipped=it.skipped+ArtworkSkip(index+1,row.track.artist,row.track.title,ArtworkSkipReason.NO_COVER))}}
                                    diagnostic.result(if(changed)"ADDED" else if(marked)"EXISTING" else "NO_COVER")
                                }catch(e: CancellationException){throw e}
                                 catch(e: Exception) {
                                    val unavailable=(e as? MusicFailure)?.code==MusicError.UNAVAILABLE&&diagnostic.step==ArtworkStep.RESOLVE_COVER
                                    if(unavailable) {
                                        _artwork.update{it.copy(missing=it.missing+1,skipped=it.skipped+ArtworkSkip(index+1,row.track.artist,row.track.title,ArtworkSkipReason.TRACK_UNAVAILABLE,row.taskKey))};diagnostic.result("UNAVAILABLE",e)
                                    }else {
                                        val issue=artworkIssue(index+1,row.displayName,diagnostic.step,e,row.track.artist,row.track.title)
                                        _artwork.update{it.copy(issues=it.issues+issue)}
                                        diagnostic.result("FAILED",e)
                                        try{repair.recover()}catch(recovery: Exception){diagnostic.step=ArtworkStep.RECOVERY;throw recovery}
                                        if((e as? MusicFailure)?.code in setOf(MusicError.ACCESS,MusicError.INVALID_ACCESS,MusicError.DENIED,MusicError.CAPTCHA,MusicError.VALIDATION,MusicError.RATE_LIMIT,MusicError.STORAGE,MusicError.NETWORK,MusicError.TLS,MusicError.TIMEOUT)||e is java.io.IOException)throw e
                                        _artwork.update{it.copy(failed=it.failed+1)}
                                    }
                                }
                                _artwork.update{it.copy(processed=it.processed+1)}
                                AudioExportRuntime.refresh()
                            }
                        }
                    }
                }
                active(g);outcome="COMPLETED"
                _artwork.update{it.copy(message="Обработка завершена. Музыка повторно не скачивалась.")}
            }catch(e: CancellationException){outcome="INTERRUPTED";_artwork.update{it.copy(message="Обработка прервана. Оставь музыкальный экран открытым для продолжения. Готовые обложки будут пропущены.")};throw e}
             catch(e: Exception){diagnostic.result("STOPPED",e);_artwork.update{it.copy(message="Обработка остановлена (${(e as? MusicFailure)?.code?.name?:"STORAGE"}). Уже готовые файлы сохранены; при незавершённой записи резервная копия остаётся до восстановления.")}}
            finally{_artwork.update{it.copy(running=false,current=null)};diagnostic.summary(outcome,_artwork.value)}
        }
    }
    private val _artworkLookup=MutableStateFlow(ArtworkLookupState());val artworkLookup=_artworkLookup.asStateFlow()
    private fun lookupNow()=System.nanoTime()/1_000_000
    private fun lookupUpdate(id: String,block:(ArtworkLookupState)->ArtworkLookupState){_artworkLookup.update{if(it.id==id)block(it)else it}}
    fun showArtworkLookup(taskKey: String) {
        if(!_state.value.initialized||_state.value.busy||_artwork.value.running||
            _artwork.value.skipped.none{it.taskKey==taskKey&&it.reason==ArtworkSkipReason.TRACK_UNAVAILABLE})return
        val id=UUID.randomUUID().toString()
        _artworkLookup.value=ArtworkLookupState(visible=true,id=id,phase=LookupPhase.OPENING)
        lookupWork("open",id){g->
            AudioExportRuntime.editSaved(getApplication()) {
                AudioExportStore(getApplication()).use{store->
                    val shared=SharedAudioStore(getApplication());ArtworkRepair(getApplication(),store,shared).recover()
                    val row=store.existing(taskKey)?:throw MusicFailure(MusicError.INCOMPLETE)
                    musicCheck(!row.coverEmbedded,MusicError.INCOMPLETE);shared.requireValid(row);active(g)
                    lookupUpdate(id){it.copy(phase=LookupPhase.READY,target=row,query=(row.track.artist+" "+row.track.title).take(500))}
                }
            }
        }
    }
    fun dismissArtworkLookup(){if(_artworkLookup.value.busy)cancel();_artworkLookup.value=ArtworkLookupState()}
    fun artworkLookupQuery(value: String) {
        if(_artworkLookup.value.busy)return
        _artworkLookup.update{it.copy(query=value.take(500),results=null,choice=null,credentialId="",phase=LookupPhase.READY,message=null)}
    }
    fun findArtworkReplacement(offset: Int=0) {
        val s=_artworkLookup.value
        if(!s.visible||s.target==null||s.busy||_state.value.busy)return
        _artworkLookup.update{it.copy(phase=LookupPhase.SEARCHING,choice=null,message=null)}
        lookupWork("search",s.id){g->
            val a=sessions?.current()?:access()
            suspend fun guard(){active(g);musicCheck(vault.read()?.credentialId==a.credentialId,MusicError.ACCESS)}
            val service=ArtworkLookup(api,source={error("Search never loads images")},guard=::guard)
            val page=service.find(a,s.query,offset)
            lookupUpdate(s.id){it.copy(phase=LookupPhase.READY,results=page,resultsAt=lookupNow(),credentialId=a.credentialId,
                message=if(page.matches.isEmpty())if(page.byLink)"ВК не вернул запись по этой ссылке." else "ВК не нашёл вариантов. Измени запрос или вставь ссылку на отдельную песню." else "Выбери запись и посмотри её обложку перед подтверждением.")}
            Diagnostics.event("audio_artwork_lookup",mapOf("operation_id" to s.id,"phase" to "results","by_link" to page.byLink,"count" to page.matches.size,"offset" to page.offset))
        }
    }
    fun previewArtworkReplacement(match: ArtworkMatch) {
        val s=_artworkLookup.value;val row=s.target?:return
        if(!s.visible||s.busy||_state.value.busy||match !in s.results?.matches.orEmpty()||!match.selectable)return
        _artworkLookup.update{it.copy(phase=LookupPhase.PREVIEWING,choice=null,message=null)}
        lookupWork("preview",s.id){g->
            val a=sessions?.current()?:access()
            suspend fun guard(){active(g);musicCheck(vault.read()?.credentialId==a.credentialId&&s.credentialId==a.credentialId,MusicError.ACCESS)
                if(lookupNow()-s.resultsAt>=10*60*1000)throw ArtworkLookupFailure(LookupReason.EXPIRED)}
            val service=ArtworkLookup(api,source={selected->
                lookupImages?.invoke(selected)?:ArtworkLoader(object: MusicApi by api {override suspend fun artworkCandidates(access: MusicAccess,track: NativeTrack)=selected.candidates},
                    onAttempt={attempt->Diagnostics.event("audio_artwork_lookup_candidate",mapOf("operation_id" to s.id,"candidate" to attempt.candidate,
                        "total_candidates" to attempt.total,"origin" to attempt.origin.name,"size" to attempt.size,"hop" to attempt.hop,"outcome" to attempt.outcome.name,"http_status" to attempt.httpStatus))})
            },guard=::guard)
            val choice=service.preview(a,row,match)
            lookupUpdate(s.id){it.copy(phase=LookupPhase.CONFIRM,choice=choice,message=null)}
        }
    }
    fun backArtworkReplacement(){if(!_artworkLookup.value.busy)_artworkLookup.update{it.copy(phase=LookupPhase.READY,choice=null,message=null)}}
    fun confirmArtworkReplacement() {
        val s=_artworkLookup.value;val choice=s.choice?:return
        if(!s.visible||s.phase!=LookupPhase.CONFIRM||_state.value.busy)return
        _artworkLookup.update{it.copy(phase=LookupPhase.APPLYING,message=null)}
        lookupWork("apply",s.id){g->
            val a=sessions?.current()?:access()
            suspend fun guard(){active(g);musicCheck(authForeground.value&&vault.read()?.credentialId==a.credentialId&&a.credentialId==choice.credentialId,MusicError.ACCESS)}
            val service=ArtworkLookup(api,source={error("Confirmed image already loaded")},guard=::guard)
            val changed=service.apply(a,choice){row,image->
                AudioExportRuntime.editSaved(getApplication()) {
                    AudioExportStore(getApplication()).use{store->
                        val repair=ArtworkRepair(getApplication(),store,SharedAudioStore(getApplication()),beforeWrite=::guard)
                        repair.add(row,ArtworkSource{_,_->image}){guard();a}
                    }
                }
            }
            active(g);AudioExportRuntime.refresh()
            _artwork.update{old->
                val removed=old.skipped.count{it.taskKey==choice.target.taskKey}
                old.copy(skipped=old.skipped.filterNot{it.taskKey==choice.target.taskKey},missing=(old.missing-removed).coerceAtLeast(0),
                    added=old.added+if(changed)1 else 0,existing=old.existing+if(changed)0 else 1)
            }
            lookupUpdate(s.id){it.copy(phase=LookupPhase.DONE,choice=null,results=null,
                message=if(changed)"Выбранная обложка добавлена. Аудиоданные, имя и папка сохранены." else "В файле уже есть обложка. Замена не выполнялась.")}
            Diagnostics.event("audio_artwork_lookup",mapOf("operation_id" to s.id,"phase" to "applied","added" to changed))
        }
    }
    private fun lookupWork(step: String,id: String,block:suspend(Int)->Unit) {
        run("cover_lookup_$step"){g->
            try{withContext(Dispatchers.IO){block(g)}}
            catch(e: CancellationException){lookupUpdate(id){it.copy(phase=LookupPhase.READY,choice=null,message="Операция прервана. Выбери обложку заново.")};throw e}
            catch(e: Exception){
                val code=(e as? MusicFailure)?.code?:MusicError.STORAGE
                val message=(e as? ArtworkLookupFailure)?.reason?.label?:when {
                    step=="search"&&code in setOf(MusicError.METHOD,MusicError.DENIED)->"ВК не разрешил этот запрос. Можно попробовать HTTPS-ссылку на отдельную песню."
                    e is ArtworkFetchFailure->artworkIssue(0,"",ArtworkStep.FETCH_COVER,e).reason
                    e is SavedFileFailure->e.reason.label
                    code==MusicError.ACCESS->"Доступ ВК изменился или отклонён. Выполни поиск заново; при необходимости подключись через Chrome."
                    else->code.label
                }
                lookupUpdate(id){it.copy(phase=LookupPhase.READY,choice=null,message=message)}
                Diagnostics.event("audio_artwork_lookup",mapOf("operation_id" to id,"phase" to step,"outcome" to "failed","code" to code.name,
                    "reason" to (e as? ArtworkLookupFailure)?.reason?.name,"vk_code" to (e as? MusicFailure)?.remoteCode))
            }
        }
    }
    private val _duplicates=DuplicateCheckRuntime.mutable;val duplicates=DuplicateCheckRuntime.state
    fun showDuplicates(){
        if(!_state.value.initialized||_state.value.busy&&!_duplicates.value.busy)return
        _duplicates.update{it.copy(visible=true)}
        if(_duplicates.value.busy)return
        viewModelScope.launch {
            val saved=withContext(Dispatchers.IO){runCatching{DuplicateScanCache(getApplication()).count()}.getOrDefault(0)}
            _duplicates.update{it.copy(savedChecks=saved)}
        }
    }
    fun dismissDuplicates(){_duplicates.update{it.copy(visible=false,consent=null)}}
    fun requestDuplicateRemoval(row: MusicDuplicate) {
        val s=_duplicates.value;val report=s.report?:return
        if(s.phase!=DuplicatePhase.READY||_state.value.busy||DuplicateCheckRuntime.transferring||row.status!=DuplicateStatus.PENDING||row !in report.duplicates)return
        _duplicates.update{it.copy(consent=DuplicateConsent(report.owner,report.credentialId,row))}
    }
    fun cancelDuplicateRemoval(){if(!_duplicates.value.busy)_duplicates.update{it.copy(consent=null)}}
    fun skipDuplicate(row: MusicDuplicate) {
        if(_state.value.busy||_duplicates.value.phase!=DuplicatePhase.READY||row.status!=DuplicateStatus.PENDING)return
        _duplicates.update{it.copy(report=it.report?.let{r->r.copy(duplicates=r.duplicates.map{d->if(d.remove.id==row.remove.id)d.copy(status=DuplicateStatus.SKIPPED)else d})},consent=null)}
    }
    fun findDuplicates() {
        if(!_state.value.initialized||_state.value.busy)return
        run("duplicates_start"){withContext(Dispatchers.IO){DuplicateCheckRuntime.start(getApplication())}}
    }
    fun pauseDuplicateCheck(){
        if(_duplicates.value.phase==DuplicatePhase.DELETING){cancel();return}
        val id=_duplicates.value.scanId
        viewModelScope.launch(Dispatchers.IO){runCatching{DuplicateCheckRuntime.pause(getApplication(),id)}}
    }
    fun openDuplicateNotification(){_duplicates.update{it.copy(visible=true,consent=null)}}
    fun confirmDuplicateRemoval() {
        if(_state.value.busy)return
        val s=_duplicates.value;val consent=s.consent?:return
        if(s.phase!=DuplicatePhase.READY)return
        _duplicates.update{it.copy(phase=DuplicatePhase.DELETING,consent=null)}
        duplicateWork(s.scanId,consent)
    }
    private fun duplicateWork(id: String,consent: DuplicateConsent) {
        run("duplicates_delete"){g->
            var attempted=false;var stage="PREPARING"
            fun update(block:(DuplicatesState)->DuplicatesState){_duplicates.update{if(it.scanId==id)block(it)else it}}
            try {
                withContext(Dispatchers.IO){AudioExportRuntime.editSaved(getApplication()){
                    musicCheck(!AudioExportRuntime.state.value.unfinished,MusicError.BUSY)
                    val a=sessions?.current()?:access()
                    suspend fun guard(){
                        active(g)
                        val previousStage=stage;stage="SESSION_GUARD"
                        musicCheck(vault.read()?.credentialId==a.credentialId,MusicError.ACCESS)
                        active(g);stage=previousStage
                    }
                    DuplicateAudioHasher(getApplication(),api,progress={track,p->
                        stage=when(p.stage){"resolve_media"->"RESOLVE_MEDIA";"payload_hash"->"AUDIO_HASH";"verifying"->"AUDIO_FORMAT";else->"AUDIO_TRANSFER"}
                        val label=when(p.stage){"resolve_media"->"Получаем ссылку ВК";"payload_hash"->"Считаем SHA-256 аудиоданных";"verifying"->"Проверяем аудиоформат";else->"Загружаем временную копию для SHA-256"}
                        update{it.copy(progress=it.progress.copy(stage=label,track=track,bytes=p.bytes))}
                    }).use{hasher->
                        val service=MusicDuplicates(api,hasher,guard=::guard,progress={p->
                            stage=if(p.track!=null)"AUDIO_HASH" else "LIBRARY_READ"
                            update{it.copy(progress=p)}
                        })
                        service.remove(a,consent){
                            guard();musicCheck(authForeground.value,MusicError.BUSY);attempted=true
                            _state.update{it.copy(probe=null,page=null,pageOffset=0,selected=null)}
                            update{it.copy(progress=DuplicateProgress("Отправляем одно удаление в ВК",track=consent.duplicate.remove))}
                        }
                        active(g)
                        update{it.copy(phase=DuplicatePhase.READY,report=it.report?.let{r->r.copy(duplicates=r.duplicates.map{d->
                            if(d.remove.id==consent.duplicate.remove.id)d.copy(status=DuplicateStatus.DELETED)else d})},
                            message="ВК подтвердил удаление одной записи. Оставляемая копия и локальные файлы сохранены. После завершения обнови «Мою музыку».")}
                        Diagnostics.event("audio_duplicates_delete",mapOf("scan_id" to id,"outcome" to "CONFIRMED"))
                    }
                }}
            }catch(e: CancellationException){
                update{it.copy(phase=DuplicatePhase.BLOCKED,consent=null,message=if(attempted)
                    "Операция прервана после начала запроса. Результат не подтверждён. Автоматического повтора нет; нужен новый поиск."
                    else "Проверка перед удалением прервана. Удаление не отправлено; нужен новый поиск.",
                    report=it.report?.let{r->r.copy(duplicates=r.duplicates.map{d->if(attempted&&d.remove.id==consent.duplicate.remove.id)d.copy(status=DuplicateStatus.UNKNOWN)else d})})}
                Diagnostics.event("audio_duplicates_delete",mapOf("scan_id" to id,"outcome" to if(attempted)"UNKNOWN" else "CANCELLED"));throw e
            }catch(e: Exception){
                val code=(e as? MusicFailure)?.code?:MusicError.STORAGE
                update{it.copy(phase=DuplicatePhase.BLOCKED,consent=null,message=if(attempted)
                    "Удаление не подтверждено (${code.name}). Не повторяем запрос автоматически. Проверь результат новым поиском."
                    else "Проверка перед удалением остановлена: ${code.label} Нужен новый поиск; удаление не отправлено.",
                    report=it.report?.let{r->r.copy(duplicates=r.duplicates.map{d->if(attempted&&d.remove.id==consent.duplicate.remove.id)d.copy(status=DuplicateStatus.UNKNOWN)else d})})}
                Diagnostics.event("audio_duplicates_failure",mapOf("scan_id" to id,"code" to code.name,"vk_code" to (e as? MusicFailure)?.remoteCode,
                    "stage" to stage,"origin" to if(e is MusicReadChanged)"LOCAL_CREDENTIAL_CHANGED" else "REQUEST_OR_CHECK",
                    "mutation_started" to attempted))
            }
        }
    }
    private val _availability=MutableStateFlow(AvailabilityState());val availability=_availability.asStateFlow()
    fun showAvailability(){if(!_state.value.busy&&!_duplicates.value.busy)_availability.update{it.copy(visible=true)}}
    fun dismissAvailability(){_availability.update{it.copy(visible=false)}}
    fun checkUnavailableTracks() {
        if(!_availability.value.visible||_availability.value.running)return
        run("availability"){g->
            _availability.value=AvailabilityState(visible=true,running=true)
            try{withContext(Dispatchers.IO){
                val a=sessions?.current()?:access()
                suspend fun guard(){active(g);if(sessions!=null)sessions.continueRead(a)else musicCheck(vault.read()?.credentialId==a.credentialId,MusicError.ACCESS)}
                val extra=AudioExportStore(getApplication()).use{it.unavailableTracks()}+_duplicates.value.report?.problems.orEmpty().filter{it.error in setOf(MusicError.UNAVAILABLE,MusicError.DENIED)}.map{it.track}
                AvailabilityCheck(api,::guard).check(a,extra){done,total,row->
                    _availability.update{it.copy(done=done,total=total,results=if(row==null)it.results else it.results+row)}
                }
                active(g)
                _availability.update{it.copy(message="Проверка завершена. Результат относится к текущему аккаунту и соединению; для загрузки повтори пропущенные треки.")}
                Diagnostics.event("audio_availability_result",mapOf("checked" to _availability.value.done,"links" to _availability.value.results.count{it.linkAvailable}))
            }}catch(e: CancellationException){_availability.update{it.copy(message="Проверка прервана. Уже полученные результаты показаны.")};throw e}
             catch(e: Exception){_availability.update{it.copy(message="Проверка остановлена: "+((e as? MusicFailure)?.code?.label?:MusicError.STORAGE.label))}}
            finally{_availability.update{it.copy(running=false)}}
        }
    }
    private val _savedFiles=MutableStateFlow(SavedFilesState());val savedFiles=_savedFiles.asStateFlow()
    fun showSavedFiles(){if(_state.value.initialized&&!_state.value.busy)_savedFiles.update{it.copy(visible=true)}}
    fun dismissSavedFiles(){_savedFiles.update{it.copy(visible=false)}}
    fun selectSavedFile(row: PublishedAudio){if(!_state.value.busy)_savedFiles.update{it.copy(selecting=row)}}
    fun cancelSavedFileSelection(){_savedFiles.update{it.copy(selecting=null)}}
    fun backSavedFiles() {
        if(_state.value.busy)return
        _savedFiles.update{it.copy(preview=null,forget=null,selecting=null)}
    }
    fun requestForgetFile(row: PublishedAudio){if(!_state.value.busy)_savedFiles.update{it.copy(forget=row)}}
    private fun savedFileWork(block: suspend(Int,AudioExportStore,SharedAudioStore,SavedFileRecovery)->Unit) {
        run("saved_files"){g->
            _savedFiles.update{it.copy(running=true,message=null)}
            try {
                withContext(Dispatchers.IO){AudioExportRuntime.editSaved(getApplication()){
                    AudioExportStore(getApplication()).use{store->
                        val shared=SharedAudioStore(getApplication());val recovery=SavedFileRecovery(getApplication(),store,shared)
                        ArtworkRepair(getApplication(),store,shared).recover();recovery.recover()
                        block(g,store,shared,recovery)
                        SharedPlaylistStore(getApplication(),store).repairLocations();AudioExportRuntime.refresh()
                    }
                }}
            }catch(e: CancellationException){_savedFiles.update{it.copy(message="Проверка прервана. Восстановленные связи сохранены.")};throw e}
             catch(e: Exception){
                val code=(e as? MusicFailure)?.code?:MusicError.STORAGE
                _savedFiles.update{it.copy(message=(e as? SavedFileFailure)?.reason?.label?:code.label)}
                Diagnostics.event("audio_file_recovery",mapOf("outcome" to "FAILED","code" to code.name,"file_reason" to (e as? SavedFileFailure)?.reason?.name))
            }finally{_savedFiles.update{it.copy(running=false)}}
        }
    }
    fun checkSavedFiles(search: Boolean=false) {
        savedFileWork{g,store,shared,recovery->
            val rows=store.savedFiles().distinctBy{it.uri};var restored=0
            _savedFiles.update{it.copy(processed=0,total=rows.size,problems=emptyList(),preview=null,forget=null,restored=0)}
            rows.forEachIndexed{index,row->
                active(g)
                var reason=shared.inspect(row).reason
                var searchReason: SavedFileReason?=null
                if(reason!=SavedFileReason.OK&&search) {
                    val result=recovery.search(row)
                    if(result==SavedFileReason.OK){restored++;reason=SavedFileReason.OK}else searchReason=result
                }
                Diagnostics.event("audio_file_check",mapOf("item" to index+1,"reason" to reason.name,"search_reason" to searchReason?.name,"search" to search))
                _savedFiles.update{it.copy(processed=index+1,restored=restored,
                    problems=if(reason==SavedFileReason.OK)it.problems else it.problems+SavedFileProblem(row,reason,searchReason))}
            }
            _savedFiles.update{it.copy(message="Проверка завершена. Файлы не изменялись.")}
        }
    }
    fun previewSavedFile(uri: android.net.Uri) {
        val before=_savedFiles.value.selecting?:return
        _savedFiles.update{it.copy(selecting=null)}
        savedFileWork{g,_,_,recovery->
            val preview=recovery.preview(before,uri);active(g)
            _savedFiles.update{it.copy(preview=preview,forget=null)}
        }
    }
    fun confirmSavedFile() {
        val preview=_savedFiles.value.preview?:return
        savedFileWork{g,_,_,recovery->
            active(g);recovery.confirm(preview)
            _savedFiles.update{it.copy(preview=null,restored=it.restored+1,problems=it.problems.filterNot{p->p.row.taskKey==preview.before.taskKey},
                message="Копия восстановлена в Music/VK Music Export/Восстановленные. Исходный файл сохранён.")}
            Diagnostics.event("audio_file_recovery",mapOf("outcome" to "IMPORTED","exact" to preview.exact))
        }
    }
    fun confirmForgetFile() {
        val row=_savedFiles.value.forget?:return
        savedFileWork{g,store,_,_->
            active(g);store.forgetFile(row)
            _savedFiles.update{it.copy(forget=null,problems=it.problems.filterNot{p->p.row.taskKey==row.taskKey},message="Запись убрана. Файлы на телефоне и музыка ВК сохранены.")}
            Diagnostics.event("audio_file_recovery",mapOf("outcome" to "FORGOTTEN"))
        }
    }
    private val _removal=MutableStateFlow(LibraryRemovalState());val removal=_removal.asStateFlow()
    fun dismissRemoval() {
        if(_removal.value.phase==LibraryRemovalPhase.CHECKING)cancel()
        _removal.update{if(it.phase==LibraryRemovalPhase.SENDING)it.copy(visible=false)else LibraryRemovalState()}
    }
    private fun isLibraryTrack(track: NativeTrack,owner: Long): Boolean {
        val s=_state.value
        return s.probe?.owner==owner&&s.selected==null&&s.catalogSection==CatalogSection.LIBRARY&&s.page?.tracks?.contains(track)==true
    }
    fun inspectRemoval(track: NativeTrack) {
        val s=_state.value;val owner=s.probe?.owner?:return
        if(!s.initialized||s.busy||!isLibraryTrack(track,owner))return
        _removal.value=LibraryRemovalState(true,LibraryRemovalPhase.CHECKING,owner,track)
        run("remove_inspect"){g->
            try {
                LibraryRemovalProtocol.audioId(owner,track)
                val row=withContext(Dispatchers.IO) {
                    val shared=SharedAudioStore(getApplication())
                    val rows=AudioExportStore(getApplication()).use{it.savedLibraryCopies(track,owner)}
                    rows.firstOrNull{shared.valid(it)}
                        ?:throw MusicFailure(MusicError.INCOMPLETE)
                }
                active(g);musicCheck(isLibraryTrack(track,owner),MusicError.ACCESS)
                _removal.update{it.copy(phase=LibraryRemovalPhase.CONFIRM,file=row)}
            }catch(e: CancellationException){_removal.update{it.copy(phase=LibraryRemovalPhase.ERROR,message="Проверка файла прервана. Запрос удаления не отправлен.")};throw e}
             catch(_: Exception){_removal.update{it.copy(phase=LibraryRemovalPhase.ERROR,
                message="Не удалось подтвердить скачанную копию этого трека из «Моей музыки». Сначала скачай и проверь файл. Запрос удаления не отправлен.")}}
        }
    }
    fun confirmRemoval() {
        val ticket=_removal.value
        val track=ticket.track?:return;val row=ticket.file?:return
        if(!ticket.visible||ticket.phase!=LibraryRemovalPhase.CONFIRM||!isLibraryTrack(track,ticket.owner))return
        run("remove_confirm"){g->
            var attempted=false;var confirmed=false
            try {
                _removal.update{it.copy(phase=LibraryRemovalPhase.SENDING)}
                val a=sessions?.current()?:access();active(g)
                musicCheck(isLibraryTrack(track,ticket.owner),MusicError.ACCESS)
                LibraryTrackRemoval(api){file->withContext(Dispatchers.IO){SharedAudioStore(getApplication()).valid(file)}}
                    .remove(a,ticket.owner,track,row){active(g);musicCheck(isLibraryTrack(track,ticket.owner),MusicError.ACCESS);attempted=true}
                confirmed=true;active(g)
                // Offsets have shifted: discard cached pages and restart at zero, never patch counts optimistically.
                _state.update{it.copy(probe=null,page=null,pageOffset=0)}
                var refreshed=false
                try {
                    val fresh=api.library(a);active(g);musicCheck(fresh.owner==ticket.owner,MusicError.ACCESS)
                    _state.update{it.copy(probe=fresh,page=fresh.library,pageOffset=0)};refreshed=true
                }catch(e: CancellationException){throw e}catch(_: Exception){/* Deletion success remains known. */}
                val message="ВК подтвердил удаление из «Моей музыки». Скачанный файл сохранён."+
                    if(refreshed)"" else " Не удалось обновить список; нажми «Проверить музыку»."
                _removal.update{it.copy(phase=LibraryRemovalPhase.DONE,message=message)}
                _state.update{it.copy(message=message)}
            }catch(e: CancellationException) {
                if(attempted)_state.update{it.copy(probe=null,page=null,pageOffset=0)}
                _removal.update{it.copy(phase=if(confirmed)LibraryRemovalPhase.DONE else LibraryRemovalPhase.ERROR,
                    message=if(confirmed)"ВК подтвердил удаление. Скачанный файл сохранён; список нужно обновить."
                        else if(attempted)"Операция прервана; результат удаления не подтверждён. Проверь список в ВК. Автоматического повтора нет. Локальный файл сохранён."
                        else "Запрос удаления не отправлен. Скачанный файл сохранён.")}
                throw e
            }catch(e: Exception) {
                if(attempted)_state.update{it.copy(probe=null,page=null,pageOffset=0)}
                val failure=e as? MusicFailure
                val definite=failure?.code in setOf(MusicError.ACCESS,MusicError.DENIED,MusicError.METHOD,MusicError.CAPTCHA,MusicError.VALIDATION,MusicError.RATE_LIMIT)
                _removal.update{it.copy(phase=LibraryRemovalPhase.ERROR,message=when {
                    !attempted->"Не удалось повторно проверить доступ или сохранённый файл. Запрос удаления не отправлен."
                    definite->"ВК отклонил удаление (${failure?.code?.name}). Скачанный файл сохранён. Обнови список через «Проверить музыку»."
                    else->"Результат удаления не подтверждён. Проверь список в ВК или нажми «Проверить музыку». Автоматического повтора нет. Скачанный файл сохранён."
                })}
            }
        }
    }
    private var work: Job?=null
    private val authForeground=MutableStateFlow(false)
    private val authOpened=MutableStateFlow(false)
    private var authSession: DeviceSession?=null
    private var authCodes: Channel<DeviceConfirmationCode>?=null
    private var operationOwner: Int?=null
    @Volatile private var generation=0
    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val removed=LegacyAudioCleanup(application).run()
                if(removed>0)Diagnostics.event("legacy_audio_cleanup",mapOf("removed_files" to removed))
            }catch(e: CancellationException){throw e}
             catch(_: Exception){Diagnostics.event("legacy_audio_cleanup_failed",mapOf("code" to "STORAGE"))}
            try{AudioExportRuntime.initialize(application)}catch(_: Exception){_state.update{it.copy(error=MusicError.STORAGE)}}
        }
        viewModelScope.launch {
            try {
                val loaded=withContext(Dispatchers.IO){
                    DuplicateCheckRuntime.initialize(application)
                    val interrupted=if(NativeMusicGate.mutex.tryLock())try{operations.recover()}finally{NativeMusicGate.mutex.unlock()}else false
                    val access=runCatching{vault.read()}
                    Triple(access.getOrNull()!=null,interrupted,access.isFailure)
                }
                _state.value=NativeMusicState(initialized=true,configured=loaded.first,
                    accessDamaged=loaded.third,
                    message=if(loaded.third)"Сохранённый доступ не удалось прочитать. Подключи ВК заново или удали только данные доступа. Музыка и очередь сохранены."
                        else if(loaded.second)"Предыдущая операция прервана. Готовые файлы сохранены; проверку можно повторить." else null)
            }catch(_: Exception){_state.value=NativeMusicState(initialized=true,error=MusicError.STORAGE)}
        }
    }
    private fun run(stage: String,block: suspend (Int)->Unit) {
        if(DuplicateCheckRuntime.transferring&&stage!="duplicates_delete"){
            _state.update{it.copy(error=MusicError.BUSY)};return
        }
        if(!_state.value.initialized||_state.value.busy){
            Diagnostics.event("native_music_operation_ignored",mapOf("stage" to stage,"reason" to if(_state.value.busy)"busy" else "initializing"))
            return
        }
        if(stage!="availability")_availability.value=AvailabilityState()
        if(stage in setOf("browser_auth","configure","forget"))_duplicates.value=DuplicatesState()
        if(!stage.startsWith("cover_lookup_"))_artworkLookup.value=ArtworkLookupState()
        if(!stage.startsWith("duplicates_"))_duplicates.update{it.copy(visible=false,consent=null)}
        if(stage !in setOf("remove_inspect","remove_confirm"))_removal.value=LibraryRemovalState()
        val lookupRunId=_artworkLookup.value.id.takeIf{stage.startsWith("cover_lookup_")}
        val duplicateRunId=_duplicates.value.scanId.takeIf{stage=="duplicates_delete"}
        val g=++generation;val id=UUID.randomUUID().toString()
        operationOwner=g
        _state.update{it.copy(busy=true,stage=stage,error=null,message=null,authIssue=null,
            authCodeIssue=null,authCodePending=false,stopping=false,preparing=null,waitingForSlot=NativeMusicGate.mutex.isLocked,
            authPhase=if(stage=="browser_auth")DeviceAuthPhase.PREPARING else null)}
        Diagnostics.event("native_music_operation",mapOf("stage" to stage,"phase" to "start"))
        if(stage=="browser_auth")Diagnostics.event("native_auth_phase",mapOf("phase" to DeviceAuthPhase.PREPARING.name))
        work=viewModelScope.launch(start=CoroutineStart.UNDISPATCHED) {
            var outcome="failed"
            try {
                // An old Activity/operation may still be finishing its non-cancellable cleanup.
                // Wait for that owner instead of failing the user's next attempt with BUSY.
                NativeMusicGate.mutex.withLock {
                    active(g);_state.update{it.copy(waitingForSlot=false)}
                    try {
                        withContext(Dispatchers.IO){operations.begin(id)}
                        block(g);active(g);outcome="completed"
                        Diagnostics.event("native_music_operation",mapOf("stage" to stage,"phase" to "completed"))
                    }catch(e: CancellationException){outcome="interrupted";throw e}
                    finally {
                        withContext(NonCancellable+Dispatchers.IO){runCatching{operations.finish(id,outcome)}}
                    }
                }
            }catch(e: CancellationException){outcome="interrupted";throw e}
             catch(e: DeviceAuthFailure) {
                if(g==generation) {
                    _state.update{it.copy(authPhase=DeviceAuthPhase.FAILED,authIssue=e.issue,error=e.musicError)}
                    Diagnostics.event("native_auth_failed",mapOf("code" to e.issue.name,"vk_code" to e.remoteCode,"music_code" to e.musicError?.name))
                }
             }
             catch(e: Exception){
                if(g==generation) {
                    val error=(e as? MusicFailure)?.code?:MusicError.STORAGE
                    _state.update{it.copy(error=error,authPhase=if(stage=="browser_auth")DeviceAuthPhase.FAILED else it.authPhase,
                        authIssue=if(stage=="browser_auth")DeviceAuthIssue.RESPONSE else it.authIssue)}
                    Diagnostics.event("native_music_operation",mapOf("stage" to stage,"phase" to "failed","code" to error.name,"vk_code" to (e as? MusicFailure)?.remoteCode))
                }
            }finally {
                if(lookupRunId!=null)lookupUpdate(lookupRunId){if(it.busy)it.copy(phase=LookupPhase.READY,choice=null,message="Операция прервана. Повтори поиск.") else it}
                // Cancellation can happen while waiting for the shared gate, before block() starts.
                if(duplicateRunId!=null)_duplicates.update{if(it.scanId==duplicateRunId&&it.busy)
                    it.copy(phase=DuplicatePhase.BLOCKED,consent=null,message="Проверка прервана до завершения. Готовые проверки сохранены; поиск можно продолжить.") else it}
                if(operationOwner==g){
                    operationOwner=null;work=null
                    authSession=null;authOpened.value=false;authCodes?.cancel();authCodes=null
                    _state.update{it.copy(busy=false,stage="",stopping=false,waitingForSlot=false,
                        authCodePending=false,authCodeIssue=null,preparing=null,
                        message=if(it.stopping)"Операция остановлена. Готовые файлы сохранены." else it.message)}
                }
            }
        }
    }
    private suspend fun active(g: Int){currentCoroutineContext().ensureActive();if(g!=generation)throw CancellationException()}
    private suspend fun access(): MusicAccess=withContext(Dispatchers.IO){vault.read()?:throw MusicFailure(MusicError.ACCESS)}
    fun setForeground(value: Boolean) {
        if(authForeground.value==value)return
        authForeground.value=value
        sessionMonitor?.cancel();sessionMonitor=null
        if(value&&sessions!=null)sessionMonitor=viewModelScope.launch {
            while(isActive) {
                try{sessions.current()}catch(e: CancellationException){throw e}catch(_: Exception){/* Sanitized session state describes the result. */}
                delay(60_000)
            }
        }
        if(_state.value.stage=="browser_auth")Diagnostics.event("native_auth_lifecycle",
            mapOf("foreground" to value,"detector" to if(value)"activity_onResume" else "activity_onPause"))
    }
    private fun authPhase(phase: DeviceAuthPhase) {
        if(_state.value.authPhase==phase)return
        _state.update{it.copy(authPhase=phase,
            authCodePending=if(phase==DeviceAuthPhase.AWAITING_APPROVAL)false else it.authCodePending)}
        Diagnostics.event("native_auth_phase",mapOf("phase" to phase.name))
    }
    fun authenticate() {
        Diagnostics.button("native_auth_start")
        run("browser_auth") {g->
            authSession=null;authOpened.value=false
            authPhase(DeviceAuthPhase.PREPARING)
            val device="VK Music Export / "+android.os.Build.MANUFACTURER.take(24)+" "+android.os.Build.MODEL.take(48)
            val session=deviceAuth.begin(device);active(g);authSession=session
            authPhase(DeviceAuthPhase.READY)
            val codes=Channel<DeviceConfirmationCode>(1);authCodes=codes
            val access=try {
                awaitDeviceAccess(deviceAuth,session,authOpened,authForeground,authClock,authPollMs,codes,
                    onIncorrectCode={if(g==generation)_state.update{it.copy(authCodeIssue=DeviceCodeIssue.INCORRECT,authCodePending=false)}},
                    onPhase={if(g==generation)authPhase(it)})
            }finally{codes.cancel();if(authCodes===codes)authCodes=null}
            active(g);authPhase(DeviceAuthPhase.VERIFYING)
            val result=try{api.library(access)}catch(e: CancellationException){throw e}
                catch(e: MusicFailure){throw DeviceAuthFailure(DeviceAuthIssue.MUSIC_CHECK,e.remoteCode,e.code)}
            active(g);authForeground.first{it};active(g)
            if(access.renewal!=null&&access.renewal.owner!=result.owner)
                throw DeviceAuthFailure(DeviceAuthIssue.MUSIC_CHECK,musicError=MusicError.ACCESS)
            // Only a real successful audio.get may replace an already saved credential.
            try{withContext(Dispatchers.IO){vault.save(access.withSession(verifiedAt=System.currentTimeMillis(),accountId=result.owner))}}
            catch(e: CancellationException){throw e}
            catch(_: Exception){throw DeviceAuthFailure(DeviceAuthIssue.STORAGE)}
            active(g)
            sessions?.reload()
            _state.update{it.copy(configured=true,accessDamaged=false)}
            acceptProbe(g,access,result);active(g)
            authPhase(DeviceAuthPhase.CONNECTED)
        }
    }
    fun openAuthChrome(context: android.content.Context) {
        Diagnostics.button("native_auth_open_chrome")
        val s=authSession
        if(!_state.value.busy||_state.value.stopping||_state.value.stage!="browser_auth"||s==null)return
        if(authClock()>=s.expiresAt) {
            _state.update{it.copy(authIssue=DeviceAuthIssue.EXPIRED)}
            return
        }
        val opened=authBrowser(context,s.url)
        Diagnostics.event("native_auth_browser_dispatch",mapOf("accepted" to opened,"auth_status" to "not_proven"))
        if(opened)authOpened.value=true
        _state.update{it.copy(authIssue=if(opened)null else DeviceAuthIssue.BROWSER)}
        if(opened && _state.value.authPhase==DeviceAuthPhase.READY)authPhase(DeviceAuthPhase.WAITING)
    }
    fun submitAuthCode(raw: String) {
        val s=_state.value
        if(!s.busy||s.stopping||s.authPhase!=DeviceAuthPhase.ENTER_CODE||s.authCodePending||!authForeground.value)return
        val code=DeviceConfirmationCode.from(raw)
        if(code==null){_state.update{it.copy(authCodeIssue=DeviceCodeIssue.FORMAT)};return}
        val codes=authCodes?:return
        Diagnostics.button("native_auth_submit_code")
        // Main.immediate may resume the receiver and finish validation inside trySend.
        // Publish pending first so a fast result cannot be overwritten by a stale true.
        _state.update{it.copy(authCodeIssue=null,authCodePending=true)}
        if(!codes.trySend(code).isSuccess)_state.update{it.copy(authCodePending=false)}
    }
    fun configure(token: String,agent: String) {
        Diagnostics.button("native_music_save_access")
        if(!NativeProtocol.validAccess(token,agent)){_state.update{it.copy(error=MusicError.INVALID_ACCESS)};return}
        run("configure") {g->
            val candidate=MusicAccess(token,agent)
            val proof=api.library(candidate);active(g)
            withContext(Dispatchers.IO){
                active(g)
                vault.save(candidate.withSession(verifiedAt=System.currentTimeMillis(),accountId=proof.owner))
            };active(g)
            _state.update{it.copy(configured=true,accessDamaged=false)}
            sessions?.reload()
            acceptProbe(g,candidate,proof)
        }
    }
    fun checkAccess(){Diagnostics.button("native_music_probe");run("probe"){probe(it)}}
    private suspend fun probe(g: Int) {
        _state.update{it.copy(probe=null,page=null,selected=null,catalog=null,catalogError=null,catalogKinds=emptyMap(),catalogSection=CatalogSection.LIBRARY)}
        val access=access();val probe=api.library(access);active(g)
        withContext(Dispatchers.IO){vault.replace(access,access.withSession(verifiedAt=System.currentTimeMillis(),accountId=probe.owner))}
        sessions?.reload()
        acceptProbe(g,access,probe)
    }
    private suspend fun acceptProbe(g: Int,access: MusicAccess,probe: NativeProbe) {
        active(g)
        _state.update{it.copy(probe=probe,page=probe.library,pageOffset=0,catalogOffset=0,selected=null,catalog=null,catalogError=null,catalogKinds=emptyMap(),catalogSection=CatalogSection.LIBRARY)}
        Diagnostics.event("native_music_sample",mapOf("stage" to "library","entries" to probe.library.tracks.size,"expected_count" to probe.library.count,"page_limit" to NativeProtocol.LIMIT,"complete" to false))
        try {
            loadCatalog(g,access,probe.owner)
            _state.update{it.copy(message="Музыкальный доступ работает. Плейлисты и альбомы показаны отдельно.")}
        }catch(e: CancellationException){throw e}
         catch(e: Exception){active(g);val error=(e as? MusicFailure)?.code?:MusicError.RESPONSE;_state.update{it.copy(catalogError=error)}}
    }
    private suspend fun loadCatalog(g: Int,access: MusicAccess,owner: Long) {
        val choices=withContext(Dispatchers.IO){AudioExportStore(getApplication()).use{it.catalogKinds(owner)}}
        active(g)
        val catalog=MusicPagination.catalog({offset->active(g);api.catalogPage(access,owner,offset)}){n,total->
            _state.update{it.copy(preparing="Получено подборок: $n/$total")}
        }
        active(g)
        _state.update{it.copy(catalog=catalog,catalogKinds=choices,catalogOffset=0,catalogError=null)}
        Diagnostics.event("native_music_sample",mapOf("stage" to "catalog","entries" to catalog.lists.size,
            "expected_count" to catalog.count,"complete" to (catalog.lists.size==catalog.count)))
    }
    fun refreshCatalog() {
        val owner=_state.value.probe?.owner?:return
        run("catalog_refresh"){g->
            try{loadCatalog(g,access(),owner)}catch(e: CancellationException){throw e}
            catch(e: Exception){active(g);_state.update{it.copy(catalogError=(e as? MusicFailure)?.code?:MusicError.STORAGE)}}
        }
    }
    fun section(section: CatalogSection) {
        if(_state.value.busy)return
        _state.update{it.copy(catalogSection=section,catalogOffset=0,selected=null,pageOffset=0,
            page=if(section==CatalogSection.LIBRARY)it.probe?.library else null)}
    }
    fun classify(list: NativePlaylist,kind: PlaylistKind) {
        val owner=_state.value.probe?.owner?:return
        if(list.kind!=PlaylistKind.UNKNOWN)return
        run("catalog_kind"){g->
            val choices=withContext(Dispatchers.IO){AudioExportStore(getApplication()).use{
                it.catalogKind(owner,list,kind);it.catalogKinds(owner)
            }}
            active(g)
            _state.update{it.copy(catalogKinds=choices,catalogOffset=0,catalogSection=CatalogSection.forKind(MusicCatalog.kind(list,choices)))}
        }
    }
    fun select(list: NativePlaylist?) {
        Diagnostics.button(if(list==null)"native_music_library" else "native_music_playlist")
        val probe=_state.value.probe?:return
        if(list==null){if(!_state.value.busy)_state.update{it.copy(page=probe.library,pageOffset=0,selected=null,catalogSection=CatalogSection.LIBRARY,catalogOffset=0)};return}
        run("playlist") {g->
            val page=api.playlist(access(),list);active(g);_state.update{it.copy(page=page,pageOffset=0,selected=list,catalogSection=CatalogSection.forKind(MusicCatalog.kind(list,it.catalogKinds)))}
            Diagnostics.event("native_music_sample",mapOf("stage" to "playlist","entries" to page.tracks.size,"expected_count" to page.count,"complete" to false))
        }
    }
    fun page(direction: Int) {
        val s=_state.value;val page=s.page?:return;val owner=s.probe?.owner?:return
        val offset=if(direction<0)(s.pageOffset-NativeProtocol.LIMIT).coerceAtLeast(0) else s.pageOffset+page.tracks.size
        if(offset>=page.count||offset==s.pageOffset)return
        Diagnostics.button("native_music_page")
        run("page") {g->
            val a=access();val next=s.selected?.let{api.playlistPage(a,it,offset)}?:api.libraryPage(a,owner,offset)
            active(g);musicCheck(next.tracks.isNotEmpty()||next.count==0,MusicError.INCOMPLETE)
            _state.update{it.copy(page=next,pageOffset=offset)}
        }
    }
    fun catalogPage(direction: Int) {
        val s=_state.value;if(s.busy)return
        val lists=MusicCatalog.lists(s.catalog,s.catalogSection,s.catalogKinds)
        val offset=if(direction<0)(s.catalogOffset-NativeProtocol.LIMIT).coerceAtLeast(0) else s.catalogOffset+NativeProtocol.LIMIT
        if(offset>=lists.size||offset==s.catalogOffset)return
        _state.update{it.copy(catalogOffset=offset)}
    }
    /** Preparing metadata stays in the visible Activity; audio is handed to a persisted UIDT queue. */
    fun prepare(catalogKind: PlaylistKind?=null,libraryAlbums: Boolean=false) {
        val s=_state.value;val owner=s.probe?.owner?:return
        Diagnostics.button(if(catalogKind!=null)"audio_queue_prepare_category" else "audio_queue_prepare_source")
        run("prepare") {g->
            withContext(Dispatchers.IO) {
                AudioExportRuntime.initialize(getApplication())
                musicCheck(!AudioExportRuntime.state.value.unfinished&&!AudioExportRuntime.active.get(),MusicError.BUSY)
                val a=access();val tasks=mutableListOf<AudioExportTask>();var issues=0;var unknown=0
                val failures=mutableListOf<ExportSourceFailure>()
                val choices=AudioExportStore(getApplication()).use{it.catalogKinds(owner)}
                suspend fun read(list: NativePlaylist?): NativePage = MusicPagination.tracks({offset->
                    active(g);if(list==null)api.libraryPage(a,owner,offset)else api.playlistPage(a,list,offset)
                }){n,total->_state.update{it.copy(preparing="Получено позиций: $n/$total")}}
                if(catalogKind==null) {
                    if(s.selected==null)tasks.addAll(ExportLayout.library(read(null),albums=libraryAlbums,account=owner))
                    else {
                        val list=s.selected;val kind=MusicCatalog.kind(list,choices)
                        musicCheck(kind!=PlaylistKind.UNKNOWN,MusicError.COLLECTION_TYPE)
                        tasks.addAll(ExportLayout.playlist(read(list),list,kind,account=owner))
                    }
                }else {
                    musicCheck(catalogKind!=PlaylistKind.UNKNOWN,MusicError.COLLECTION_TYPE)
                    val catalog=MusicPagination.catalog({offset->active(g);api.catalogPage(a,owner,offset)}){n,total->
                        _state.update{it.copy(preparing="Получено подборок: $n/$total")}
                    }
                    unknown=catalog.lists.count{MusicCatalog.kind(it,choices)==PlaylistKind.UNKNOWN}
                    if(catalog.count!=catalog.lists.size)failures.add(ExportSourceFailure(
                        ExportSource(owner,"catalog_page",catalogKind.name),"Каталог ${catalogKind.folder}: получен не полностью",MusicError.INCOMPLETE,catalogKind))
                    catalog.lists.filter{MusicCatalog.kind(it,choices)==PlaylistKind.UNKNOWN}.forEach{list->
                        failures.add(ExportSourceFailure(ExportSource(owner,"catalog",list.key),list.title,MusicError.COLLECTION_TYPE))
                    }
                    val lists=catalog.lists.filter{MusicCatalog.kind(it,choices)==catalogKind}
                    for((i,list) in lists.withIndex()) {
                        active(g);_state.update{it.copy(preparing="Чтение ${i+1}/${lists.size}: ${catalogKind.folder}")}
                        try{tasks.addAll(ExportLayout.playlist(read(list),list,catalogKind,account=owner))}
                        catch(e: MusicFailure) {
                            if(e.code !in setOf(MusicError.DENIED,MusicError.UNAVAILABLE,MusicError.METHOD,MusicError.INCOMPLETE,MusicError.LIBRARY_CHANGED))throw e
                            failures.add(ExportSourceFailure(ExportSource(owner,"catalog",list.key),list.title,e.code,catalogKind))
                            Diagnostics.event("audio_queue_source_skipped",mapOf("code" to e.code.name))
                        }
                    }
                }
                issues=failures.size
                active(g);AudioExportRuntime.replace(getApplication(),tasks,account=owner,sources=failures)
                active(g);startDownloads()
                _state.update{it.copy(message="Очередь готова. Файлы сохраняются в Music/VK Music Export. Можно свернуть приложение."+
                    (if(unknown>0)" Тип $unknown подборок не определён; они не включены." else "")+
                    (if(issues>0)" Не включены или не прочитаны источники: $issues. См. итог очереди." else ""))}
            }
        }
    }
    fun download(track: NativeTrack,position: Int) {
        Diagnostics.button("native_music_download_one")
        val s=_state.value
        val owner=s.probe?.owner?:return
        run("queue_one") {g->withContext(Dispatchers.IO){
            musicCheck(position>0,MusicError.RESPONSE)
            val a=access()
            // Count preceding occurrences too: a repeated track on page 2 must not reuse occurrence 1.
            val page=MusicPagination.tracks({offset->active(g)
                if(s.selected==null)api.libraryPage(a,owner,offset)else api.playlistPage(a,s.selected,offset)
            }){n,total->_state.update{it.copy(preparing="Проверяем позицию: $n/$total")}}
            val all=if(s.selected==null)ExportLayout.library(page,albums=false,account=owner)
                else ExportLayout.playlist(page,s.selected,MusicCatalog.kind(s.selected,s.catalogKinds),account=owner)
            val selected=all.singleOrNull{it.position==position&&it.track.id==track.id}?:throw MusicFailure(MusicError.LIBRARY_CHANGED)
            val tasks=listOf(selected)
            active(g);AudioExportRuntime.replace(getApplication(),tasks,account=owner);active(g);startDownloads()
        }}
    }
    fun resumeQueue(retry: Boolean=false) {
        Diagnostics.button(if(retry)"audio_queue_retry" else "audio_queue_resume")
        run("queue_resume"){g->withContext(Dispatchers.IO){
            active(g);AudioExportRuntime.initialize(getApplication())
            if(AudioExportRuntime.state.value.account==0L&&AudioExportRuntime.state.value.total>0) {
                val proof=api.library(sessions?.current()?:access());active(g);pendingQueueRetry=retry;_queueAccount.value=proof.owner
            } else startDownloads(retry)
        }}
    }
    fun retrySource(issue: ExportSourceFailure) {
        run("source_retry"){g->withContext(Dispatchers.IO){
            if(issue.source.kind=="playlist_file") {
                AudioExportRuntime.editSaved(getApplication()) {
                    AudioExportStore(getApplication()).use{SharedPlaylistStore(getApplication(),it).publishAll()}
                    AudioExportRuntime.refresh()
                }
                return@withContext
            }
            val a=sessions?.current()?:access()
            val proof=api.library(a);active(g)
            musicCheck(proof.owner==issue.source.account,MusicError.ACCOUNT_CHANGED)
            val choices=AudioExportStore(getApplication()).use{it.catalogKinds(proof.owner)}
            val catalog=MusicPagination.catalog({offset->active(g);api.catalogPage(a,proof.owner,offset)})
            if(issue.source.kind=="catalog_page")musicCheck(catalog.lists.size==catalog.count,MusicError.INCOMPLETE)
            val lists=if(issue.source.kind=="catalog_page")catalog.lists.filter{MusicCatalog.kind(it,choices)==issue.category}
                else listOf(catalog.lists.singleOrNull{it.key==issue.source.id}?:throw MusicFailure(MusicError.UNAVAILABLE))
            val tasks=mutableListOf<AudioExportTask>();val failures=mutableListOf<ExportSourceFailure>()
            for(list in lists) {
                val kind=MusicCatalog.kind(list,choices)
                musicCheck(kind!=PlaylistKind.UNKNOWN,MusicError.COLLECTION_TYPE)
                try {
                    val page=MusicPagination.tracks({offset->active(g);api.playlistPage(a,list,offset)})
                    tasks.addAll(ExportLayout.playlist(page,list,kind,account=proof.owner))
                }catch(e: MusicFailure) {
                    if(e.code !in setOf(MusicError.DENIED,MusicError.UNAVAILABLE,MusicError.METHOD,MusicError.INCOMPLETE,MusicError.LIBRARY_CHANGED))throw e
                    failures.add(ExportSourceFailure(ExportSource(proof.owner,"catalog",list.key),list.title,e.code,kind))
                }
            }
            active(g);AudioExportRuntime.retrySource(getApplication(),issue.source,tasks,failures)
            active(g);startDownloads()
        }}
    }
    fun pauseQueue() {
        Diagnostics.button("audio_queue_pause")
        viewModelScope.launch(Dispatchers.IO){try{AudioExportRuntime.pause(getApplication())}catch(_: Exception){_state.update{it.copy(error=MusicError.STORAGE)}}}
    }
    fun clearQueue() {
        Diagnostics.button("audio_queue_clear")
        run("queue_clear"){g->withContext(Dispatchers.IO){active(g);AudioExportRuntime.clear(getApplication())}}
    }
    fun clearRecentFiles() {
        Diagnostics.button("audio_recent_clear")
        run("recent_clear"){g->withContext(Dispatchers.IO){active(g);AudioExportRuntime.clearRecentFiles(getApplication())}}
    }
    fun shareExportReport(context: android.content.Context) {
        viewModelScope.launch {
            try {
                val intent=withContext(Dispatchers.IO){ExportReportShare.intent(context)}
                context.startActivity(android.content.Intent.createChooser(intent,"Отчёт о сохранении музыки"))
            }catch(e: CancellationException){throw e}
             catch(_: Exception){_state.update{it.copy(error=MusicError.STORAGE)}}
        }
    }
    fun cancel()=stop("native_stop_button")
    fun stopForWindow(){_artworkLookup.value=ArtworkLookupState();stop("native_window_closed")}
    fun stopInBackground() {
        _artworkLookup.value=ArtworkLookupState()
        _duplicates.update{it.copy(consent=null)}
        setForeground(false)
        // Every browser-auth phase survives Chrome, including code validation and music verification.
        if(_state.value.stage=="browser_auth")return
        stop("activity_onStop")
    }
    private fun stop(detector: String) {
        if(!_state.value.busy||_state.value.stopping)return
        if(detector=="native_stop_button")Diagnostics.button("native_music_cancel")
        Diagnostics.event("native_music_cancel",mapOf("detector" to detector))
        generation++;authSession=null;authOpened.value=false;authCodes?.cancel();authCodes=null
        _state.update{it.copy(stopping=true,authPhase=null,authIssue=null,authCodeIssue=null,
            authCodePending=false,message="Завершаем остановку операции…")}
        work?.cancel()
    }
    fun forget() {
        if(_state.value.busy)return
        Diagnostics.button("native_music_forget_access")
        run("forget") {g->
            withContext(Dispatchers.IO){vault.delete();DuplicateCheckRuntime.forget(getApplication())};active(g);sessions?.reload()
            _state.value=NativeMusicState(initialized=true,busy=true,stage="forget",message="Доступ ВК удалён с устройства.")
        }
    }
    override fun onCleared(){work?.cancel();super.onCleared()}
}
