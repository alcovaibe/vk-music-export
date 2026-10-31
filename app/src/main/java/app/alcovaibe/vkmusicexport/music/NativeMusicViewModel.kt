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
    val stopping: Boolean=false,val waitingForSlot: Boolean=false,
    val preparing: String?=null,val catalogSection: CatalogSection=CatalogSection.LIBRARY,
    val catalogKinds: Map<String,PlaylistKind> = emptyMap(),val hiddenCatalog: Set<String> = emptySet(),val accessDamaged: Boolean=false)

internal data class MusicArtistsState(val owner: Long=0,val loaded: Boolean=false,val artists: List<MusicArtist> = emptyList(),
    val selected: MusicArtist?=null,val details: MusicArtistDetails?=null,val error: MusicError?=null,
    val album: NativePlaylist?=null,val albumTracks: NativePage?=null,val requests: Int=0,val message: String?=null,val errorDetails: String?=null)
internal data class SavedLibraryEntry(val file: PublishedAudio,val present: Boolean)

internal class NativeMusicViewModel @JvmOverloads constructor(application: Application,
    private val api: MusicApi=VkAudioClient(sessions=MusicSessions.get(application)),private val deviceAuth: DeviceAuthApi=VkDeviceAuthClient(),
    private val authClock: ()->Long={android.os.SystemClock.elapsedRealtime()},private val authPollMs: Long=5000,
    private val authBrowser: (android.content.Context,String)->Boolean=::openDeviceAuthChrome,
    private val lookupImages:((ArtworkMatch)->ArtworkSource)?=null,private val artistTimeoutMs: Long=45_000,private val duplicateTransfer: AudioTransfer=AudioTransfer(),private val maintenanceLauncher: ((Job)->Unit)?=null): AndroidViewModel(application) {
    private val vault=MusicAccessStore(application)
    private val sessions=if(api is VkAudioClient)MusicSessions.get(application) else null
    val sessionState=sessions?.state?:MutableStateFlow(MusicSessionState()).asStateFlow()
    private var sessionMonitor: Job?=null
    private val operations=NativeOperationJournal(application)
    private val _state=MutableStateFlow(NativeMusicState())
    val state=combine(_state,MaintenanceRuntime.status){own,task->
        if(task.busy)own.copy(busy=true,stage=task.stage,stopping=task.phase==MaintenancePhase.STOPPING)else own
    }.stateIn(viewModelScope,SharingStarted.Eagerly,_state.value)
    private var maintenanceOpen: String?=null
    fun openMaintenanceNotification(stage: String) {
        if(stage !in MaintenanceRuntime.stages)return
        if(!_state.value.initialized){maintenanceOpen=stage;return}
        when(stage){
            "track_metadata"->{_trackMetadata.update{it.copy(visible=true)};if(!MaintenanceRuntime.busy)showTrackMetadata()}
            "artwork"->_artwork.update{it.copy(visible=true)}
            else->_savedFiles.update{it.copy(visible=true)}
        }
    }
    private val _downloadsOpen=MutableStateFlow(false);val downloadsOpen=_downloadsOpen.asStateFlow()
    private val _artists=MutableStateFlow(MusicArtistsState());val artists=_artists.asStateFlow()
    private val _savedLibrary=MutableStateFlow<List<SavedLibraryEntry>>(emptyList());val savedLibrary=_savedLibrary.asStateFlow()
    private val _savedLibraryError=MutableStateFlow(false);val savedLibraryError=_savedLibraryError.asStateFlow()
    private var savedLibraryWork: Job?=null
    fun previewArtwork(track: NativeTrack)=api.previewArtwork(track)
    fun refreshSavedLibrary() {
        savedLibraryWork?.cancel()
        savedLibraryWork=viewModelScope.launch(Dispatchers.IO) {
            try {
                val shared=SharedAudioStore(getApplication())
                val rows=AudioExportStore(getApplication()).use{it.savedFiles()}.distinctBy{it.uri}
                val entries=rows.map{currentCoroutineContext().ensureActive();SavedLibraryEntry(it,shared.present(it))}
                _savedLibrary.value=entries;_savedLibraryError.value=false
            }catch(e: CancellationException){throw e}catch(_: Exception){_savedLibraryError.value=true}
        }
    }
    fun showArtists(refresh: Boolean=false) {
        val owner=_state.value.probe?.owner?:return
        if(_state.value.busy)return
        if(!refresh&&_artists.value.owner==owner&&_artists.value.loaded) {_artists.update{it.copy(selected=null,details=null,error=null,message=null,album=null,albumTracks=null)};return}
        _artists.value=MusicArtistsState(owner=owner)
        run("artists") {g->
            try {
                val a=sessions?.current()?:access()
                musicCheck(a.accountId==null||a.accountId==owner,MusicError.ACCOUNT_CHANGED)
                val rows=withTimeout(artistTimeoutMs){api.followedArtists(a,owner){partial,requests->
                    if(g==generation) {
                        _artists.update{it.copy(artists=partial.distinctBy{artist->artist.id},requests=requests)}
                        _state.update{it.copy(preparing="Подписки: ${partial.size} · запросов: $requests")}
                    }
                }};active(g)
                if(sessions!=null)sessions.continueRead(a)else musicCheck(vault.read()?.credentialId==a.credentialId,MusicError.ACCESS)
                _artists.value=MusicArtistsState(owner,true,rows.distinctBy{it.id})
            }catch(_: TimeoutCancellationException){active(g);_artists.update{it.copy(error=MusicError.TIMEOUT,errorDetails=null,message="ВК не завершил загрузку подписок за отведённое время. Уже полученные исполнители доступны; обнови список для повтора.")}}
            catch(e: CancellationException){_artists.update{it.copy(message="Загрузка подписок приостановлена. Уже полученные исполнители доступны.")};throw e}
            catch(e: Exception){active(g);Diagnostics.event("audio_artists_failure",mapOf("code" to (e as? MusicFailure)?.code?.name,
                "vk_code" to (e as? MusicFailure)?.remoteCode,"http_status" to (e as? MusicFailure)?.httpStatus,
                "reason" to (e as? ArtistLoadingFailure)?.reason?.name,"method" to (e as? ArtistLoadingFailure)?.method));_artists.update{it.copy(error=(e as? MusicFailure)?.code?:MusicError.RESPONSE,errorDetails=ArtistFailureText.describe(e))}}
        }
    }
    fun selectArtist(artist: MusicArtist?) {
        if(_state.value.busy)return
        if(artist==null){_artists.update{it.copy(selected=null,details=null,error=null,message=null,album=null,albumTracks=null)};return}
        val s=_artists.value
        if(s.owner!=_state.value.probe?.owner||s.artists.none{it.id==artist.id})return
        _artists.update{it.copy(selected=artist,details=null,error=null,message=null,album=null,albumTracks=null)}
        run("artist_details") {g->
            try {
                val a=sessions?.current()?:access()
                musicCheck(a.accountId==null||a.accountId==s.owner,MusicError.ACCOUNT_CHANGED)
                val details=withTimeout(artistTimeoutMs){api.artistDetails(a,artist)};active(g)
                if(sessions!=null)sessions.continueRead(a)else musicCheck(vault.read()?.credentialId==a.credentialId,MusicError.ACCESS)
                _artists.update{it.copy(details=details)}
            }catch(_: TimeoutCancellationException){active(g);_artists.update{it.copy(error=MusicError.TIMEOUT,errorDetails=null,message="ВК не завершил запрос вовремя. Повтори загрузку.")}}
            catch(e: CancellationException){throw e}catch(e: Exception){active(g);_artists.update{it.copy(error=(e as? MusicFailure)?.code?:MusicError.RESPONSE,errorDetails=ArtistFailureText.describe(e))}}
        }
    }
    fun backFromArtist() {
        if(_state.value.busy)cancel()
        _artists.update{if(it.album!=null)it.copy(album=null,albumTracks=null,error=null,message=null)else it.copy(selected=null,details=null,error=null,message=null,album=null,albumTracks=null)}
    }
    fun selectArtistAlbum(album: NativePlaylist?) {
        if(_state.value.busy)return
        if(album==null){_artists.update{it.copy(album=null,albumTracks=null,error=null,message=null)};return}
        val s=_artists.value
        if(s.owner!=_state.value.probe?.owner||s.details?.albums?.none{it.key==album.key}!=false)return
        _artists.update{it.copy(album=album,albumTracks=null,error=null,message=null)}
        run("artist_album") {g->
            try {
                val a=sessions?.current()?:access()
                musicCheck(a.accountId==null||a.accountId==s.owner,MusicError.ACCOUNT_CHANGED)
                val page=withTimeout(artistTimeoutMs){MusicPagination.tracks({offset->active(g);api.playlistPage(sessions?.continueRead(a)?:a,album,offset)})}
                active(g);_artists.update{it.copy(albumTracks=page)}
            }catch(_: TimeoutCancellationException){active(g);_artists.update{it.copy(error=MusicError.TIMEOUT,errorDetails=null,message="ВК не завершил запрос вовремя. Повтори загрузку.")}}
            catch(e: CancellationException){throw e}catch(e: Exception){active(g);_artists.update{it.copy(error=(e as? MusicFailure)?.code?:MusicError.RESPONSE,errorDetails=ArtistFailureText.describe(e))}}
        }
    }
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
    fun dismissDownloads(){
        Diagnostics.button("audio_downloads_dismiss");_downloadsOpen.value=false
        if(AudioExportRuntime.state.value.status.startsWith("FINISHED")&&!_state.value.busy&&_state.value.probe!=null)refreshCatalog()
    }
    private suspend fun startDownloads(retry: Boolean=false) {
        // Presentation state only: dismissing the sheet never controls the UIDT job.
        _downloadsOpen.value=true
        AudioExportRuntime.start(getApplication(),retry)
    }
    private val _artwork=MaintenanceRuntime.artwork;val artwork=_artwork.asStateFlow()
    private val _trackMetadata=MaintenanceRuntime.metadata;val trackMetadata=_trackMetadata.asStateFlow()
    fun showTrackMetadata() {
        if(!_state.value.initialized||_state.value.busy)return
        _trackMetadata.update{it.copy(visible=true)}
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val owner=vault.read()?.accountId?:return@launch
                val saved=AudioExportStore(getApplication()).use{it.metadataRun(owner)}?:return@launch
                _trackMetadata.update{if(it.visible&&!it.running&&!_state.value.busy)saved.state()else it}
            }catch(_: Exception){_trackMetadata.update{it.copy(message="Не удалось прочитать сохранённую проверку.")}}
        }
    }
    fun dismissTrackMetadata(){_trackMetadata.update{it.copy(visible=false)}}
    fun updateSavedTrackMetadata() {
        if(!_trackMetadata.value.visible||_trackMetadata.value.running)return
        run("track_metadata"){g->
            _trackMetadata.update{it.copy(running=true,message=null)}
            try {
                withContext(Dispatchers.IO) {
                    AudioExportRuntime.editSaved(getApplication(),maintenance=true) {
                        AudioExportStore(getApplication()).use{store->
                            val shared=SharedAudioStore(getApplication())
                            vault.read()?.accountId?.let{owner->store.metadataRun(owner)}?.let{saved->
                                _trackMetadata.update{saved.state(it.visible,running=true).copy(message=null)}
                            }
                            ArtworkRepair(getApplication(),store,shared).recover()
                            val initial=sessions?.current()?:access()
                            val owner=api.library(initial).owner
                            suspend fun checkedAccess(): MusicAccess {
                                active(g)
                                val current=if(sessions!=null)sessions.continueRead(initial)else access().also {
                                    if(it.credentialId!=initial.credentialId)throw MusicReadChanged()
                                }
                                active(g);musicCheck(current.accountId==null||current.accountId==owner,MusicError.ACCOUNT_CHANGED)
                                return current
                            }
                            suspend fun guard(){checkedAccess();Unit}
                            guard()
                            val repair=TrackMetadataRepair(getApplication(),store,shared,beforeWrite=::guard)
                            val files=store.savedFiles()
                            val pending=store.metadataRun(owner)?.takeIf{it.unfinished}?:TrackMetadataRun.start(owner,files)
                            val batch=TrackMetadataBatch(api,::checkedAccess,find=store::metadataTarget,
                                verify=shared::requireValid,update=repair::update,recover=repair::recover,save={store.metadataRun(it)},
                                metadataSource={store.metadataReplacement(owner,it)?.referenceId},
                                progress={snapshot,name->
                                    _trackMetadata.update{snapshot.state(it.visible,running=true).copy(name=name,message=null)}
                                    MaintenanceRuntime.checkpoint();AudioExportRuntime.refresh()
                                })
                            try {
                                val result=batch.run(pending)
                                _trackMetadata.update{result.state(it.visible,running=true)}
                            }finally {
                                withContext(NonCancellable+Dispatchers.IO) {
                                    SharedPlaylistStore(getApplication(),store).repairLocations()
                                    AudioExportRuntime.refresh()
                                }
                            }
                        }
                    }
                }
            }catch(e: CancellationException){_trackMetadata.update{it.copy(message="Проверка приостановлена. Нажми «Продолжить», чтобы вернуться к необработанной записи.")};throw e}
             catch(e: Exception) {
                MaintenanceRuntime.markPaused()
                val code=(e as? MusicFailure)?.code
                _trackMetadata.update{it.copy(message=when {
                    e is MusicReadChanged->"Доступ ВК изменился. Сохранённую проверку можно продолжить после входа в тот же аккаунт."
                    code in setOf(MusicError.ACCESS,MusicError.INVALID_ACCESS)->"ВК требует повторный вход. Подключись заново и нажми «Продолжить». Готовые позиции сохранены."
                    else->(code?.label?:"Обработка остановлена.")+" Прогресс сохранён; проверку можно продолжить."
                })}
            }finally {
                _trackMetadata.update{it.copy(running=false,name=null)}
                Diagnostics.event("audio_track_metadata",mapOf("updated" to _trackMetadata.value.updated,"unavailable" to _trackMetadata.value.unavailable,
                    "skipped" to _trackMetadata.value.skipped,"failed" to _trackMetadata.value.failed))
            }
        }
    }
    fun showArtwork(){if(_state.value.initialized&&!_state.value.busy)_artwork.update{it.copy(visible=true)}}
    fun dismissArtwork(){_artwork.update{it.copy(visible=false)}}
    fun addSavedArtwork() {
        if(!_artwork.value.visible||_artwork.value.running)return
        run("artwork"){g->
            val diagnostic=ArtworkDiagnostics{type,fields->Diagnostics.event(type,fields)}
            var outcome="STOPPED"
            _artwork.update{it.copy(visible=true,running=true,message=null,current=null)}
            try {
                withContext(Dispatchers.IO) {
                    AudioExportRuntime.editSaved(getApplication(),maintenance=true) {
                        AudioExportStore(getApplication()).use{store->
                            val shared=SharedAudioStore(getApplication())
                            var lease: MusicAccess?=null
                            suspend fun checkedAccess(): MusicAccess {
                                active(g)
                                val first=lease?:throw MusicFailure(MusicError.ACCESS)
                                return (sessions?.continueRead(first)?:access().also{musicCheck(it.credentialId==first.credentialId,MusicError.ACCESS)}).also{active(g)}
                            }
                            val repair=ArtworkRepair(getApplication(),store,shared,onStep={diagnostic.step=it},beforeWrite={checkedAccess();Unit})
                            diagnostic.step=ArtworkStep.RECOVERY;repair.recover()
                            val saved=store.savedFiles()
                            val ready=saved.filter{it.coverEmbedded}.map{it.uri}.toSet()
                            val files=saved.filter{it.uri !in ready}.distinctBy{it.uri}
                            if(files.isNotEmpty())lease=sessions?.current()?:access()
                            val batch=MaintenanceRuntime.batch("artwork",lease?.accountId?:0,files)
                            if(batch.resumed)_artwork.update{it.copy(total=batch.rows.size)}
                            else _artwork.value=ArtworkState(visible=_artwork.value.visible,running=true,total=batch.rows.size,readySkipped=ready.size)
                            MaintenanceRuntime.checkpoint()
                            val source=ArtworkLoader(api,onAttempt=diagnostic::candidate,onStep={diagnostic.step=it})
                            for((index,original) in batch.rows.withIndex()) {
                                if(index<batch.next)continue
                                val row=store.existing(original.taskKey)?:original
                                active(g);diagnostic.item=index+1;diagnostic.step=ArtworkStep.VERIFY_FILE
                                _artwork.update{it.copy(current="${row.track.artist} — ${row.track.displayTitle}")}
                                _artwork.update{it.copy(issues=it.issues.filterNot{issue->issue.item==index+1})}
                                try {
                                    musicCheck(row.uri==original.uri&&row.track.id==original.track.id,MusicError.INCOMPLETE)
                                    val changed=repair.add(row,source,::checkedAccess)
                                    val marked=store.existing(row.taskKey)?.coverEmbedded==true
                                    _artwork.update{when{changed->it.copy(added=it.added+1);marked->it.copy(existing=it.existing+1);else->it.copy(missing=it.missing+1,skipped=it.skipped+ArtworkSkip(index+1,row.track.artist,row.track.displayTitle,ArtworkSkipReason.NO_COVER))}}
                                    diagnostic.result(if(changed)"ADDED" else if(marked)"EXISTING" else "NO_COVER")
                                }catch(e: CancellationException){throw e}
                                 catch(e: Exception) {
                                    val unavailable=(e as? MusicFailure)?.code==MusicError.UNAVAILABLE&&diagnostic.step==ArtworkStep.RESOLVE_COVER
                                    if(unavailable) {
                                        _artwork.update{it.copy(missing=it.missing+1,skipped=it.skipped+ArtworkSkip(index+1,row.track.artist,row.track.displayTitle,ArtworkSkipReason.TRACK_UNAVAILABLE,row.taskKey))};diagnostic.result("UNAVAILABLE",e)
                                    }else {
                                        val issue=artworkIssue(index+1,row.displayName,diagnostic.step,e,row.track.artist,row.track.displayTitle)
                                        _artwork.update{it.copy(issues=it.issues+issue)}
                                        diagnostic.result("FAILED",e)
                                        try{repair.recover()}catch(recovery: Exception){diagnostic.step=ArtworkStep.RECOVERY;throw recovery}
                                        if((e as? MusicFailure)?.code in setOf(MusicError.ACCESS,MusicError.INVALID_ACCESS,MusicError.DENIED,MusicError.CAPTCHA,MusicError.VALIDATION,MusicError.RATE_LIMIT,MusicError.STORAGE,MusicError.NETWORK,MusicError.TLS,MusicError.TIMEOUT)||e is java.io.IOException)throw e
                                        _artwork.update{it.copy(failed=it.failed+1)}
                                    }
                                }
                                _artwork.update{it.copy(processed=index+1)}
                                MaintenanceRuntime.advance(index+1);AudioExportRuntime.refresh()
                            }
                        }
                    }
                }
                active(g);outcome="COMPLETED"
                _artwork.update{it.copy(message="Обработка завершена. Музыка повторно не скачивалась.")}
            }catch(e: CancellationException){outcome="INTERRUPTED";_artwork.update{it.copy(message="Обработка прервана. Продолжи обработку из приложения. Готовые обложки и прогресс сохранены.")};throw e}
             catch(e: Exception){MaintenanceRuntime.markPaused();diagnostic.result("STOPPED",e);_artwork.update{it.copy(message="Обработка остановлена (${(e as? MusicFailure)?.code?.name?:"STORAGE"}). Уже готовые файлы сохранены; при незавершённой записи резервная копия остаётся до восстановления.")}}
            finally{_artwork.update{it.copy(running=false,current=null,resumable=MaintenanceRuntime.isResuming("artwork"))};diagnostic.summary(outcome,_artwork.value)}
        }
    }
    private val _metadataLookup=MutableStateFlow(MetadataLookupState());val metadataLookup=_metadataLookup.asStateFlow()
    private fun metadataLookupUpdate(id: String,block:(MetadataLookupState)->MetadataLookupState){_metadataLookup.update{if(it.id==id)block(it)else it}}
    fun showMetadataLookup(target: TrackMetadataTarget) {
        if(!_state.value.initialized||_state.value.busy||_trackMetadata.value.running||DuplicateCheckRuntime.transferring||
            _trackMetadata.value.issues.none{it.target==target})return
        val id=UUID.randomUUID().toString()
        _metadataLookup.value=MetadataLookupState(visible=true,id=id,phase=LookupPhase.OPENING)
        metadataLookupWork("open",id){g->
            AudioExportRuntime.editSaved(getApplication()) {
                AudioExportStore(getApplication()).use{store->
                    val shared=SharedAudioStore(getApplication())
                    ArtworkRepair(getApplication(),store,shared).recover();TrackMetadataRepair(getApplication(),store,shared).recover()
                    val row=store.metadataTarget(target)?:throw MusicFailure(MusicError.INCOMPLETE)
                    musicCheck(row.catalogueVerified,MusicError.INCOMPLETE);shared.requireValid(row);active(g)
                    metadataLookupUpdate(id){it.copy(phase=LookupPhase.READY,target=row,query=(row.track.artist+" "+row.track.displayTitle).take(500))}
                }
            }
        }
    }
    fun dismissMetadataLookup(){if(_metadataLookup.value.busy)cancel();_metadataLookup.value=MetadataLookupState()}
    fun metadataLookupQuery(value: String) {
        if(_metadataLookup.value.busy||_state.value.busy)return
        _metadataLookup.update{it.copy(query=value.take(500),results=null,choice=null,readSessionId="",phase=LookupPhase.READY,message=null)}
    }
    private suspend fun metadataAccess(initial: MusicAccess,owner: Long,g: Int): MusicAccess {
        active(g)
        val current=if(sessions!=null)sessions.continueRead(initial)else access().also{
            musicCheck(it.readSessionId==initial.readSessionId,MusicError.ACCESS)
        }
        musicCheck(current.accountId==null||current.accountId==owner,MusicError.ACCOUNT_CHANGED);active(g)
        return current
    }
    fun findMetadataReplacement(offset: Int=0) {
        val s=_metadataLookup.value;val row=s.target?:return
        if(!s.visible||s.busy||_state.value.busy||DuplicateCheckRuntime.transferring)return
        _metadataLookup.update{it.copy(phase=LookupPhase.SEARCHING,choice=null,message=null)}
        metadataLookupWork("search",s.id){g->
            val a=sessions?.current()?:access()
            val owner=a.accountId?:api.library(a).owner
            musicCheck(row.source?.account?.let{it>0&&it!=owner}!=true,MusicError.ACCOUNT_CHANGED)
            suspend fun guard(){metadataAccess(a,owner,g);Unit}
            val page=TrackMetadataLookup(api,guard=::guard).find(a,s.query,offset)
            metadataLookupUpdate(s.id){it.copy(phase=LookupPhase.READY,results=page,resultsAt=lookupNow(),readSessionId=a.readSessionId,owner=owner,
                message=if(page.tracks.isEmpty())"ВК не вернул вариантов. Измени запрос или вставь полную ссылку на песню." else "Выбери запись и проверь название и версию перед подтверждением.")}
        }
    }
    fun previewMetadataReplacement(track: NativeTrack) {
        val s=_metadataLookup.value;val row=s.target?:return
        if(!s.visible||s.busy||_state.value.busy||DuplicateCheckRuntime.transferring||track !in s.results?.tracks.orEmpty())return
        _metadataLookup.update{it.copy(phase=LookupPhase.PREVIEWING,choice=null,message=null)}
        metadataLookupWork("preview",s.id){g->
            val a=sessions?.current()?:access()
            suspend fun guard(){
                musicCheck(a.readSessionId==s.readSessionId,MusicError.ACCESS);metadataAccess(a,s.owner,g)
                if(lookupNow()-s.resultsAt>=10*60_000)throw MetadataChoiceExpired()
            }
            val choice=TrackMetadataLookup(api,guard=::guard).preview(a,row,track)
            metadataLookupUpdate(s.id){it.copy(phase=LookupPhase.CONFIRM,choice=choice,message=null)}
        }
    }
    fun backMetadataReplacement(){if(!_metadataLookup.value.busy)_metadataLookup.update{it.copy(phase=LookupPhase.READY,choice=null,message=null)}}
    fun confirmMetadataReplacement() {
        val s=_metadataLookup.value;val choice=s.choice?:return
        if(!s.visible||s.phase!=LookupPhase.CONFIRM||_state.value.busy||DuplicateCheckRuntime.transferring)return
        _metadataLookup.update{it.copy(phase=LookupPhase.APPLYING,message=null)}
        metadataLookupWork("apply",s.id){g->
            val a=sessions?.current()?:access()
            suspend fun guard(){musicCheck(authForeground.value,MusicError.ACCESS);metadataAccess(a,s.owner,g);Unit}
            val changed=TrackMetadataLookup(api,guard=::guard).apply(a,choice){row,text,reference->
                AudioExportRuntime.editSaved(getApplication()) {
                    AudioExportStore(getApplication()).use{store->
                        val shared=SharedAudioStore(getApplication())
                        val repair=TrackMetadataRepair(getApplication(),store,shared,beforeWrite=::guard)
                        ArtworkRepair(getApplication(),store,shared).recover();repair.recover();guard()
                        musicCheck(store.metadataTarget(TrackMetadataTarget(row.taskKey,row.uri,row.track.id,row.displayName))==row,MusicError.INCOMPLETE)
                        musicCheck(row.source?.account?.let{it>0&&it!=s.owner}!=true,MusicError.ACCOUNT_CHANGED)
                        shared.requireValid(row)
                        // The confirmed source survives interrupted file edits; the repair journal owns the bytes.
                        store.metadataReplacement(TrackMetadataReplacement(s.owner,row.track.id,row.uri,reference))
                        try {
                            val written=repair.update(row,text)
                            store.metadataRun(s.owner)?.let{run->
                                val count=run.issues.count{it.target?.uri==row.uri&&it.target.trackId==row.track.id&&!it.resolved}
                                val updated=run.copy(unavailable=(run.unavailable-count).coerceAtLeast(0),
                                    updated=run.updated+if(written)count else 0,current=run.current+if(written)0 else count,
                                    issues=run.issues.map{if(it.target?.uri==row.uri&&it.target.trackId==row.track.id)
                                        it.copy(resolved=true,selectedId=reference,reason="Название и версия проверены по выбранной записи ВК.")else it})
                                store.metadataRun(updated);_trackMetadata.update{updated.state(it.visible)}
                            }
                            written
                        }finally {withContext(NonCancellable+Dispatchers.IO){SharedPlaylistStore(getApplication(),store).repairLocations();AudioExportRuntime.refresh()}}
                    }
                }
            }
            metadataLookupUpdate(s.id){it.copy(phase=LookupPhase.DONE,choice=null,results=null,
                message=if(changed)"Название и пометка версии обновлены. Выбор сохранён для следующих проверок." else "Название уже актуально. Выбор сохранён для следующих проверок.")}
        }
    }
    private fun metadataLookupWork(step: String,id: String,block:suspend(Int)->Unit) {
        run("metadata_lookup_$step"){g->
            try{withTimeout(60_000){withContext(Dispatchers.IO){block(g)}}}
            catch(_: TimeoutCancellationException){metadataLookupUpdate(id){it.copy(phase=LookupPhase.READY,choice=null,message="ВК не ответил вовремя. Повтори поиск.")}}
            catch(e: CancellationException){metadataLookupUpdate(id){it.copy(phase=LookupPhase.READY,choice=null,message="Операция приостановлена. Выбери запись заново.")};throw e}
            catch(e: Exception){
                val code=(e as? MusicFailure)?.code?:MusicError.STORAGE
                val message=when(e) {
                    is MetadataChoiceExpired->"Время выбора истекло. Повтори поиск."
                    is MetadataCandidateChanged->"Данные выбранной записи изменились. Просмотри её заново."
                    is ArtworkLookupFailure->e.reason.label
                    is SavedFileFailure->e.reason.label
                    else->when(code){MusicError.ACCESS,MusicError.INVALID_ACCESS,MusicError.ACCOUNT_CHANGED->"Доступ ВК изменился. Подключись к тому же аккаунту и повтори поиск."
                        MusicError.DENIED,MusicError.METHOD->"ВК не разрешил запрос. Попробуй полную ссылку на другую запись песни."
                        else->code.label}
                }
                metadataLookupUpdate(id){it.copy(phase=LookupPhase.READY,choice=null,message=message)}
                Diagnostics.event("audio_metadata_lookup",mapOf("phase" to step,"code" to code.name))
            }
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
                    lookupUpdate(id){it.copy(phase=LookupPhase.READY,target=row,query=(row.track.artist+" "+row.track.displayTitle).take(500))}
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
        if(s.phase!=DuplicatePhase.READY||_state.value.busy||row.status!=DuplicateStatus.PENDING||row !in report.duplicates)return
        _duplicates.update{it.copy(consent=DuplicateConsent(report.owner,report.credentialId,row,report.readSessionId))}
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
                withContext(Dispatchers.IO){
                    DuplicateCheckRuntime.awaitIdle()
                    AudioExportRuntime.editSaved(getApplication()){
                    musicCheck(!AudioExportRuntime.state.value.unfinished,MusicError.BUSY)
                    val a=sessions?.current()?:access()
                    suspend fun guard(){
                        active(g)
                        val previousStage=stage;stage="SESSION_GUARD"
                        if(sessions!=null)sessions.continueRead(a)
                        else musicCheck(vault.read()?.credentialId==a.credentialId,MusicError.ACCESS)
                        active(g);stage=previousStage
                    }
                    DuplicateAudioHasher(getApplication(),api,transfer=duplicateTransfer,progress={track,p->
                        stage=when(p.stage){"resolve_media"->"RESOLVE_MEDIA";"payload_hash"->"AUDIO_HASH";"verifying"->"AUDIO_FORMAT";else->"AUDIO_TRANSFER"}
                        val label=when(p.stage){"resolve_media"->"Получаем ссылку ВК";"payload_hash"->"Считаем SHA-256 аудиоданных";"verifying"->"Проверяем аудиоформат";else->"Загружаем временную копию для SHA-256"}
                        update{it.copy(progress=it.progress.copy(stage=label,track=track,bytes=p.bytes))}
                    }).use{hasher->
                        val service=MusicDuplicates(api,hasher,guard=::guard,progress={p->
                            stage=if(p.track!=null)"AUDIO_HASH" else "LIBRARY_READ"
                            update{it.copy(progress=p)}
                        })
                        service.remove(a,consent,mutationAccess={sessions?.continueRead(a)?:access()}){
                            guard();musicCheck(authForeground.value,MusicError.BUSY);attempted=true
                            _state.update{it.copy(probe=null,page=null,selected=null)}
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
    private val _savedFiles=MaintenanceRuntime.files;val savedFiles=_savedFiles.asStateFlow()
    fun showSavedFiles(){if(_state.value.initialized&&!_state.value.busy)_savedFiles.update{it.copy(visible=true)}}
    fun dismissSavedFiles(){_savedFiles.update{it.copy(visible=false)}}
    fun selectSavedFile(row: PublishedAudio){if(!_state.value.busy)_savedFiles.update{it.copy(selecting=row)}}
    fun cancelSavedFileSelection(){_savedFiles.update{it.copy(selecting=null)}}
    fun backSavedFiles() {
        if(_state.value.busy)return
        _savedFiles.update{it.copy(preview=null,forget=null,selecting=null)}
    }
    fun requestForgetFile(row: PublishedAudio){if(!_state.value.busy)_savedFiles.update{it.copy(forget=row)}}
    private fun savedFileWork(background: Boolean=false,block: suspend(Int,AudioExportStore,SharedAudioStore,SavedFileRecovery)->Unit) {
        run(if(background)"saved_files_background" else "saved_files"){g->
            _savedFiles.update{it.copy(running=true,message=null)}
            try {
                withContext(Dispatchers.IO){AudioExportRuntime.editSaved(getApplication(),maintenance=background){
                    AudioExportStore(getApplication()).use{store->
                        val shared=SharedAudioStore(getApplication());val recovery=SavedFileRecovery(getApplication(),store,shared)
                        ArtworkRepair(getApplication(),store,shared).recover();recovery.recover()
                        block(g,store,shared,recovery)
                        SharedPlaylistStore(getApplication(),store).repairLocations();AudioExportRuntime.refresh()
                    }
                }}
            }catch(e: CancellationException){_savedFiles.update{it.copy(message="Проверка прервана. Восстановленные связи сохранены.")};throw e}
             catch(e: Exception){
                if(background)MaintenanceRuntime.markPaused()
                val code=(e as? MusicFailure)?.code?:MusicError.STORAGE
                _savedFiles.update{it.copy(message=(e as? SavedFileFailure)?.reason?.label?:code.label)}
                Diagnostics.event("audio_file_recovery",mapOf("outcome" to "FAILED","code" to code.name,"file_reason" to (e as? SavedFileFailure)?.reason?.name))
            }finally{_savedFiles.update{it.copy(running=false,resumable=MaintenanceRuntime.isResuming("saved_files_background"))}}
        }
    }
    fun checkSavedFiles(search: Boolean=MaintenanceRuntime.savedSearch()) {
        savedFileWork(background=true){g,store,shared,recovery->
            val batch=MaintenanceRuntime.batch("saved_files_background",0,store.savedFiles().distinctBy{it.uri},search)
            var restored=if(batch.resumed)_savedFiles.value.restored else 0
            if(!batch.resumed)_savedFiles.update{it.copy(processed=0,total=batch.rows.size,problems=emptyList(),preview=null,forget=null,restored=0)}
            batch.rows.forEachIndexed{index,original->
                if(index<batch.next)return@forEachIndexed
                val row=store.existing(original.taskKey)?:original
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
                MaintenanceRuntime.advance(index+1)
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
                // The library changed: reload the full snapshot, never patch counts optimistically.
                _state.update{it.copy(probe=null,page=null)}
                var refreshed=false
                try {
                    val proof=api.library(a);active(g);musicCheck(proof.owner==ticket.owner,MusicError.ACCESS)
                    val fresh=fullLibrary(g,a,proof)
                    _state.update{it.copy(probe=fresh,page=fresh.library)};refreshed=true
                }catch(e: CancellationException){throw e}catch(_: Exception){/* Deletion success remains known. */}
                val message="ВК подтвердил удаление из «Моей музыки». Скачанный файл сохранён."+
                    if(refreshed)"" else " Не удалось обновить список; нажми «Проверить музыку»."
                _removal.update{it.copy(phase=LibraryRemovalPhase.DONE,message=message)}
                _state.update{it.copy(message=message)}
            }catch(e: CancellationException) {
                if(attempted)_state.update{it.copy(probe=null,page=null)}
                _removal.update{it.copy(phase=if(confirmed)LibraryRemovalPhase.DONE else LibraryRemovalPhase.ERROR,
                    message=if(confirmed)"ВК подтвердил удаление. Скачанный файл сохранён; список нужно обновить."
                        else if(attempted)"Операция прервана; результат удаления не подтверждён. Проверь список в ВК. Автоматического повтора нет. Локальный файл сохранён."
                        else "Запрос удаления не отправлен. Скачанный файл сохранён.")}
                throw e
            }catch(e: Exception) {
                if(attempted)_state.update{it.copy(probe=null,page=null)}
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
            AudioExportRuntime.state.map{Triple(it.account,it.done,it.status)}.distinctUntilChanged().collect{(owner,done,status)->
                if(done>0&&status.startsWith("FINISHED")&&owner==_state.value.probe?.owner&&!_state.value.busy)refreshCatalog()
            }
        }
        viewModelScope.launch {
            try {
                val loaded=withContext(Dispatchers.IO){
                    MaintenanceRuntime.initialize(application)
                    DuplicateCheckRuntime.initialize(application)
                    val interrupted=if(NativeMusicGate.mutex.tryLock())try{operations.recover()}finally{NativeMusicGate.mutex.unlock()}else false
                    val access=runCatching{vault.read()}
                    Triple(access.getOrNull()!=null,interrupted,access.isFailure)
                }
                _state.value=NativeMusicState(initialized=true,configured=loaded.first,
                    accessDamaged=loaded.third,
                    message=if(loaded.third)"Сохранённый доступ не удалось прочитать. Подключи ВК заново или удали только данные доступа. Музыка и очередь сохранены."
                        else if(loaded.second)"Предыдущая операция прервана. Готовые файлы сохранены; проверку можно повторить." else null)
                maintenanceOpen?.let{maintenanceOpen=null;openMaintenanceNotification(it)}
            }catch(_: Exception){_state.value=NativeMusicState(initialized=true,error=MusicError.STORAGE)}
        }
    }
    private fun run(stage: String,block: suspend (Int)->Unit) {
        if(MaintenanceRuntime.busy){_state.update{it.copy(error=MusicError.BUSY)};return}
        val maintenance=stage in MaintenanceRuntime.stages
        if(DuplicateCheckRuntime.transferring&&stage!="duplicates_delete"){
            _state.update{it.copy(error=MusicError.BUSY)};return
        }
        if(!_state.value.initialized||_state.value.busy){
            Diagnostics.event("native_music_operation_ignored",mapOf("stage" to stage,"reason" to if(_state.value.busy)"busy" else "initializing"))
            return
        }
        if(stage!="availability")_availability.value=AvailabilityState()
        if(stage in setOf("browser_auth","configure","forget"))_duplicates.value=DuplicatesState()
        if(!stage.startsWith("metadata_lookup_"))_metadataLookup.value=MetadataLookupState()
        if(!stage.startsWith("cover_lookup_"))_artworkLookup.value=ArtworkLookupState()
        if(!stage.startsWith("duplicates_"))_duplicates.update{it.copy(visible=false,consent=null)}
        if(stage !in setOf("remove_inspect","remove_confirm"))_removal.value=LibraryRemovalState()
        val lookupRunId=_artworkLookup.value.id.takeIf{stage.startsWith("cover_lookup_")}
        val metadataRunId=_metadataLookup.value.id.takeIf{stage.startsWith("metadata_lookup_")}
        val duplicateRunId=_duplicates.value.scanId.takeIf{stage=="duplicates_delete"}
        val g=++generation;val id=UUID.randomUUID().toString()
        operationOwner=g
        _state.update{it.copy(busy=true,stage=stage,error=null,message=null,authIssue=null,
            authCodeIssue=null,authCodePending=false,stopping=false,preparing=null,waitingForSlot=NativeMusicGate.mutex.isLocked,
            authPhase=if(stage=="browser_auth")DeviceAuthPhase.PREPARING else null)}
        Diagnostics.event("native_music_operation",mapOf("stage" to stage,"phase" to "start"))
        if(stage=="browser_auth")Diagnostics.event("native_auth_phase",mapOf("phase" to DeviceAuthPhase.PREPARING.name))
        val operation: suspend()->Unit={
            var outcome="failed"
            try {
                if(maintenance)MaintenanceRuntime.awaitAdmission()
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
                if(maintenance)MaintenanceRuntime.markPaused()
                if(g==generation) {
                    val error=(e as? MusicFailure)?.code?:MusicError.STORAGE
                    _state.update{it.copy(error=error,authPhase=if(stage=="browser_auth")DeviceAuthPhase.FAILED else it.authPhase,
                        authIssue=if(stage=="browser_auth")DeviceAuthIssue.RESPONSE else it.authIssue)}
                    Diagnostics.event("native_music_operation",mapOf("stage" to stage,"phase" to "failed","code" to error.name,"vk_code" to (e as? MusicFailure)?.remoteCode))
                }
            }finally {
                if(metadataRunId!=null)metadataLookupUpdate(metadataRunId){if(it.busy)it.copy(phase=LookupPhase.READY,choice=null,message="Операция прервана. Повтори поиск.")else it}
                if(lookupRunId!=null)lookupUpdate(lookupRunId){if(it.busy)it.copy(phase=LookupPhase.READY,choice=null,message="Операция прервана. Повтори поиск.") else it}
                // Cancellation can happen while waiting for the shared gate, before block() starts.
                if(duplicateRunId!=null)_duplicates.update{if(it.scanId==duplicateRunId&&it.busy)
                    it.copy(phase=DuplicatePhase.BLOCKED,consent=null,message="Проверка прервана до завершения. Готовые проверки сохранены; поиск можно продолжить.") else it}
                if(maintenance||stage in setOf("metadata_lookup_apply","cover_lookup_apply","saved_files"))withContext(NonCancellable+Dispatchers.IO){runCatching{MaintenanceRuntime.checkpoint()}}
                if(operationOwner==g){
                    operationOwner=null;work=null
                    authSession=null;authOpened.value=false;authCodes?.cancel();authCodes=null
                    _state.update{it.copy(busy=false,stage="",stopping=false,waitingForSlot=false,
                        authCodePending=false,authCodeIssue=null,preparing=null,
                        message=if(it.stopping)"Операция остановлена. Готовые файлы сохранены." else it.message)}
                }
            }
        }
        work=if(maintenance)MaintenanceRuntime.launch(getApplication(),stage,id,maintenanceLauncher,operation)
            else viewModelScope.launch(start=CoroutineStart.UNDISPATCHED){operation()}
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
    fun openMusic(){if(_state.value.initialized&&_state.value.configured&&!_state.value.busy)checkAccess()}
    fun checkAccess(){Diagnostics.button("native_music_probe");run("probe"){probe(it)}}
    private suspend fun probe(g: Int) {
        _state.update{it.copy(probe=null,page=null,selected=null,catalog=null,catalogError=null,catalogKinds=emptyMap(),hiddenCatalog=emptySet(),catalogSection=CatalogSection.LIBRARY)}
        val access=access();val probe=api.library(access);active(g)
        withContext(Dispatchers.IO){vault.replace(access,access.withSession(verifiedAt=System.currentTimeMillis(),accountId=probe.owner))}
        sessions?.reload()
        acceptProbe(g,access,probe)
    }
    private suspend fun acceptProbe(g: Int,access: MusicAccess,probe: NativeProbe) {
        active(g)
        _artists.value=MusicArtistsState(owner=probe.owner)
        _state.update{it.copy(probe=probe,page=null,selected=null,catalog=null,catalogError=null,catalogKinds=emptyMap(),hiddenCatalog=emptySet(),catalogSection=CatalogSection.LIBRARY)}
        try {
            val full=fullLibrary(g,access,probe)
            active(g);_state.update{it.copy(probe=full,page=full.library)}
        }catch(e: CancellationException){throw e}
         catch(e: Exception){active(g);_state.update{it.copy(error=(e as? MusicFailure)?.code?:MusicError.RESPONSE,errorDetails=ArtistFailureText.describe(e))}}
        try {
            loadCatalog(g,access,probe.owner)
            _state.update{it.copy(message="Музыкальный доступ работает. Плейлисты и альбомы показаны отдельно.")}
        }catch(e: CancellationException){throw e}
         catch(e: Exception){active(g);val error=(e as? MusicFailure)?.code?:MusicError.RESPONSE;_state.update{it.copy(catalogError=error)}}
    }
    private suspend fun fullLibrary(g: Int,access: MusicAccess,proof: NativeProbe): NativeProbe {
        val page=MusicPagination.tracks({offset->
            active(g);if(offset==0)proof.library else api.libraryPage(access,proof.owner,offset)
        }){n,total->_state.update{it.copy(preparing="Получено треков: $n/$total")}}
        active(g)
        Diagnostics.event("native_music_sample",mapOf("stage" to "library","entries" to page.tracks.size,"expected_count" to page.count,"complete" to true))
        return proof.copy(library=page)
    }
    private suspend fun downloadedCatalog(g: Int,access: MusicAccess,owner: Long,catalog: NativeCatalog): Set<String> = withContext(Dispatchers.IO) {
        AudioExportStore(getApplication()).use{store->
        val shared=SharedAudioStore(getApplication())
        val playlists=SharedPlaylistStore(getApplication(),store)
        CatalogDownloads.completed(catalog,owner,store.savedFiles(),fetch={list->
            active(g)
            MusicPagination.tracks({offset->active(g);api.playlistPage(access,list,offset)}){n,total->
                _state.update{it.copy(preparing="Проверяем скачанную подборку: $n/$total")}
            }
        },valid={row->active(g);shared.inspect(row).reason==SavedFileReason.OK},
            sourceReady={source,tasks,files->active(g);playlists.matches(source,tasks,files)})
        }
    }
    private suspend fun currentCatalog(g: Int,access: MusicAccess,owner: Long)=MusicCatalog.load(api,access,owner,{active(g)}){n,total->
        _state.update{it.copy(preparing="Получено подборок: $n/$total")}
    }
    private suspend fun loadCatalog(g: Int,access: MusicAccess,owner: Long) {
        // Never retain a selected/deleted source when a fresh catalog is requested or fails.
        _state.update{it.copy(catalog=null,catalogError=null,catalogKinds=emptyMap(),hiddenCatalog=emptySet(),selected=null,
            page=if(it.catalogSection==CatalogSection.LIBRARY)it.probe?.library?.takeIf{p->p.tracks.size==p.count} else null)}
        val catalog=currentCatalog(g,access,owner)
        val hidden=downloadedCatalog(g,access,owner,catalog)
        active(g)
        _state.update{it.copy(catalog=catalog,hiddenCatalog=hidden,catalogError=null)}
        Diagnostics.event("native_music_sample",mapOf("stage" to "catalog","entries" to catalog.lists.size,
            "expected_count" to catalog.count,"complete" to catalog.complete))
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
        _state.update{it.copy(catalogSection=section,selected=null,
            page=if(section==CatalogSection.LIBRARY)it.probe?.library?.takeIf{p->p.tracks.size==p.count} else null)}
    }
    fun select(list: NativePlaylist?) {
        Diagnostics.button(if(list==null)"native_music_library" else "native_music_playlist")
        val probe=_state.value.probe?:return
        if(list==null){if(!_state.value.busy)_state.update{it.copy(page=probe.library.takeIf{p->p.tracks.size==p.count},selected=null,catalogSection=CatalogSection.LIBRARY)};return}
        if(_state.value.catalog?.lists?.none{it.key==list.key}!=false)return
        run("playlist") {g->
            _state.update{it.copy(page=null,selected=null)}
            val a=access();val current=currentCatalog(g,a,probe.owner)
            val selected=current.lists.singleOrNull{it.key==list.key}
            active(g);_state.update{it.copy(catalog=current,selected=selected)}
            musicCheck(selected!=null,MusicError.UNAVAILABLE)
            val actual=selected!!
            val page=MusicPagination.tracks({offset->active(g);api.playlistPage(a,actual,offset)}){n,total->
                _state.update{it.copy(preparing="Получено треков: $n/$total")}
            };active(g);_state.update{it.copy(page=page,selected=actual,catalogSection=CatalogSection.forKind(actual.kind))}
            Diagnostics.event("native_music_sample",mapOf("stage" to "playlist","entries" to page.tracks.size,"expected_count" to page.count,"complete" to true))
        }
    }
    /** Preparing metadata stays in the visible Activity; audio is handed to a persisted UIDT queue. */
    fun prepare(catalogKind: PlaylistKind?=null,libraryAlbums: Boolean=false) {
        val s=_state.value;val owner=s.probe?.owner?:return
        Diagnostics.button(if(catalogKind!=null)"audio_queue_prepare_category" else "audio_queue_prepare_source")
        run("prepare") {g->
            withContext(Dispatchers.IO) {
                AudioExportRuntime.initialize(getApplication())
                musicCheck(!AudioExportRuntime.state.value.unfinished&&!AudioExportRuntime.active.get(),MusicError.BUSY)
                val a=access();val tasks=mutableListOf<AudioExportTask>();var issues=0
                val failures=mutableListOf<ExportSourceFailure>()
                val choices=emptyMap<String,PlaylistKind>()
                suspend fun read(list: NativePlaylist?): NativePage = MusicPagination.tracks({offset->
                    active(g);if(list==null)api.libraryPage(a,owner,offset)else api.playlistPage(a,list,offset)
                }){n,total->_state.update{it.copy(preparing="Получено позиций: $n/$total")}}
                if(catalogKind==null) {
                    if(s.selected==null)tasks.addAll(ExportLayout.library(read(null),albums=libraryAlbums,account=owner))
                    else {
                        val latest=currentCatalog(g,a,owner)
                        val found=latest.lists.singleOrNull{it.key==s.selected.key}
                        active(g);_state.update{it.copy(catalog=latest,selected=found,page=if(found==null)null else it.page)}
                        val list=found?:throw MusicFailure(MusicError.UNAVAILABLE)
                        val kind=MusicCatalog.kind(list,choices)
                        tasks.addAll(ExportLayout.playlist(read(list),list,kind,account=owner))
                    }
                }else {
                    val catalog=currentCatalog(g,a,owner)
                    val hidden=downloadedCatalog(g,a,owner,catalog)
                    active(g);_state.update{it.copy(catalog=catalog,hiddenCatalog=hidden,selected=null,page=null)}
                    if(!catalog.complete)failures.add(ExportSourceFailure(
                        ExportSource(owner,"catalog_page",catalogKind.name),"Каталог ${catalogKind.folder}: получен не полностью",MusicError.INCOMPLETE,catalogKind))
                    val lists=catalog.lists.filter{MusicCatalog.kind(it,choices)==catalogKind&&it.key !in hidden}
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
                if(tasks.isEmpty()&&failures.isEmpty()){_state.update{it.copy(message="Новых треков для скачивания нет.")};return@withContext}
                active(g);AudioExportRuntime.replace(getApplication(),tasks,account=owner,sources=failures)
                active(g);startDownloads()
                _state.update{it.copy(message="Очередь готова. Файлы сохраняются в Music/VK Music Export. Можно свернуть приложение."+
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
            if(s.selected!=null)musicCheck(currentCatalog(g,a,owner).lists.any{it.key==s.selected.key},MusicError.UNAVAILABLE)
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
            val choices=emptyMap<String,PlaylistKind>()
            val catalog=currentCatalog(g,a,proof.owner)
            if(issue.source.kind=="catalog_page")musicCheck(catalog.complete,MusicError.INCOMPLETE)
            val lists=if(issue.source.kind=="catalog_page")catalog.lists.filter{MusicCatalog.kind(it,choices)==issue.category}
                else listOf(catalog.lists.singleOrNull{it.key==issue.source.id}?:throw MusicFailure(MusicError.UNAVAILABLE))
            val tasks=mutableListOf<AudioExportTask>();val failures=mutableListOf<ExportSourceFailure>()
            for(list in lists) {
                val kind=MusicCatalog.kind(list,choices)
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
    fun cancel(){if(MaintenanceRuntime.busy)MaintenanceRuntime.pause()else stop("native_stop_button")}
    fun stopForWindow(){_metadataLookup.value=MetadataLookupState();_artworkLookup.value=ArtworkLookupState();if(_state.value.stage !in MaintenanceRuntime.stages)stop("native_window_closed")}
    fun stopInBackground() {
        _metadataLookup.value=MetadataLookupState()
        _artworkLookup.value=ArtworkLookupState()
        _duplicates.update{it.copy(consent=null)}
        setForeground(false)
        // Every browser-auth phase survives Chrome, including code validation and music verification.
        if(_state.value.stage=="browser_auth"||_state.value.stage in MaintenanceRuntime.stages)return
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
            _artists.value=MusicArtistsState()
        }
    }
    override fun onCleared(){if(_state.value.stage !in MaintenanceRuntime.stages)work?.cancel();super.onCleared()}
}
