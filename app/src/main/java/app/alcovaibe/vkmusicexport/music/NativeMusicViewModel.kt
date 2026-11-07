package app.alcovaibe.vkmusicexport.music

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

internal data class NativeMusicState(val initialized: Boolean=false,val configured: Boolean=false,val busy: Boolean=false,
    val stage: String="",val error: MusicError?=null,val message: String?=null,val probe: NativeProbe?=null,
    val catalog: NativeCatalog?=null,val catalogError: MusicError?=null,val page: NativePage?=null,val selected: NativePlaylist?=null,
    val authPhase: DeviceAuthPhase?=null,val authIssue: DeviceAuthIssue?=null,
    val authCodeIssue: DeviceCodeIssue?=null,val authCodePending: Boolean=false,
    val stopping: Boolean=false,val waitingForSlot: Boolean=false,
    val preparing: String?=null,val catalogSection: CatalogSection=CatalogSection.LIBRARY,
    val hiddenCatalog: Set<String> = emptySet(),val accessDamaged: Boolean=false,
    val libraryRefreshing: Boolean=false,val libraryStale: Boolean=false,
    val catalogRefreshing: Boolean=false,val catalogStale: Boolean=false,
    val catalogVerifying: Boolean=false,val readSessionId: String?=null,
    val localCatalogKeys: Set<String> = emptySet(),val localCounts: Map<String,Int> = emptyMap(),
    val localCatalogAccount: Long?=null,
    val localPlayback: LocalCollectionPlayback?=null)

internal class NativeMusicViewModel @JvmOverloads constructor(application: Application,
    private val api: MusicApi=VkAudioClient(sessions=MusicSessions.get(application)),private val deviceAuth: DeviceAuthApi=VkDeviceAuthClient(),
    private val authClock: ()->Long={android.os.SystemClock.elapsedRealtime()},private val authPollMs: Long=5000,
    private val authBrowser: (android.content.Context,String)->Boolean=::openDeviceAuthBrowser,
    private val lookupImages:((ArtworkMatch)->ArtworkSource)?=null,private val artistTimeoutMs: Long=45_000,private val duplicateTransfer: AudioTransfer=AudioTransfer(),private val maintenanceLauncher: ((Job)->Unit)?=null,private val maintenanceBeforeItem: suspend(String,Int)->Unit={_,_->},private val queueStart: (suspend(Boolean)->Unit)?=null): AndroidViewModel(application) {
    private val vault=MusicAccessStore(application)
    private val exportReads=ExportReadCache(authClock)
    private val sessions=if(api is VkAudioClient)MusicSessions.get(application) else null
    val sessionState=sessions?.state?:MutableStateFlow(MusicSessionState()).asStateFlow()
    private var sessionMonitor: Job?=null
    private val _state=MutableStateFlow(NativeMusicState())
    private val stateScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val _visibleState=MutableStateFlow(_state.value)
    val state=_visibleState.asStateFlow()
    private fun visibleState(own: NativeMusicState,task: MaintenanceStatus=MaintenanceRuntime.status.value)=
        if(task.busy)own.copy(busy=true,stage=task.stage,stopping=task.phase==MaintenancePhase.STOPPING)else own
    private var maintenanceOpen: String?=null
    private val _maintenanceNavigation=MutableStateFlow(0L);val maintenanceNavigation=_maintenanceNavigation.asStateFlow()
    fun openMaintenanceNotification(stage: String) {
        if(stage !in MaintenanceRuntime.stages)return
        if(!_state.value.initialized){maintenanceOpen=stage;return}
        _maintenanceNavigation.value=android.os.SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L)
        when(stage){
            "track_metadata"->{_trackMetadata.update{it.copy(visible=true)};if(!MaintenanceRuntime.busy)showTrackMetadata()}
            "artwork"->_artwork.update{it.copy(visible=true)}
            else->_savedFiles.update{it.copy(visible=true)}
        }
    }
    val friends=FriendMusicController(application,api,viewModelScope,onDownloads=::showDownloads,clock=authClock,blocked={_state.value.busy},
        previewBlocked={_state.value.stage in setOf("browser_auth","configure","forget")})
    private val authForeground=MutableStateFlow(false)
    private val _downloadsOpen=MutableStateFlow(false);val downloadsOpen=_downloadsOpen.asStateFlow()
    private val downloadsReader=DownloadsReader(viewModelScope,combine(downloadsOpen,authForeground){open,foreground->open&&foreground},AudioExportRuntime.state){tab,offset->
        AudioExportRuntime.initialize(application)
        AudioExportStore(application).use{it.queuePage(tab,offset)}
    }
    val downloadsPage=downloadsReader.state
    fun selectDownloadsPage(tab: DownloadsTab,offset: Int=0)=downloadsReader.select(tab,offset)
    fun retryDownloadsPage()=downloadsReader.retry()

    private val artistController=MusicArtistsController(viewModelScope,api,
        owner={_state.value.probe?.owner},access={sessions?.current()?:access()},
        checked={initial->
            sessions?.continueRead(initial)?:access().also{MusicReadIdentity(initial).require(it)}
        },blocked={_state.value.probe==null||_state.value.stage in setOf("browser_auth","configure","forget")},
        artistTimeoutMs=artistTimeoutMs,report={e->Diagnostics.event("audio_artists_failure",mapOf("code" to (e as? MusicFailure)?.code?.name,
                "vk_code" to (e as? MusicFailure)?.remoteCode,"http_status" to (e as? MusicFailure)?.httpStatus,
                "reason" to (e as? ArtistLoadingFailure)?.reason?.name,"method" to (e as? ArtistLoadingFailure)?.method,
                "pages" to (e as? ArtistLoadingFailure)?.pages,"sections" to (e as? ArtistLoadingFailure)?.sections,
                "blocks" to (e as? ArtistLoadingFailure)?.blocks,"artist_pool" to (e as? ArtistLoadingFailure)?.pool))})
    private val searchController=MusicSearchController(viewModelScope,api,
        access={sessions?.current()?:access()},
        checked={initial->sessions?.continueRead(initial)?:access().also{MusicReadIdentity(initial).require(it)}})
    private val catalogController=MusicCatalogController(_state,api,exportReads,
        access={access()},
        checked={initial->sessions?.continueRead(initial)?:access().also{MusicReadIdentity(initial).require(it)}},
        operation={stage,block->run(stage,block)},active={active(it)},isCurrent={it==generation},
        localPlayback={owner,list->withContext(Dispatchers.IO){AudioExportStore(getApplication()).use{
            LocalCatalogSnapshots(it).availablePlayback(owner,list.key,presence={files->SharedAudioStore(getApplication()).presence(files)})
        }}},
        remember={owner,read->withContext(Dispatchers.IO){AudioExportStore(getApplication()).use{
            LocalCatalogSnapshots(it).remember(owner,read.list,read.page)
        }}},
        report={event,fields->Diagnostics.event(event,fields)})
    val musicSearch=searchController.state
    fun setSearchActive(active: Boolean)=searchController.setActive(active)
    fun searchMusic(query: String)=searchController.query(query)
    fun retryMusicSearch()=searchController.retry()
    fun moreMusicSearch()=searchController.more()
    fun playSearchTrack(snapshot: MusicSearchState,index: Int)=
        PlaybackRuntime.play(getApplication(),searchController.entries(snapshot),index)
    fun downloadSearchTrack(snapshot: MusicSearchState,index: Int) {
        searchController.entries(snapshot).getOrNull(index)?.let(::downloadPlayback)
    }
    val artists=artistController.state
    private val savedRepository=SavedLibrary(application,viewModelScope)
    val savedLibrary=savedRepository.rows.asStateFlow()
    val savedLibraryError=savedRepository.error.asStateFlow()
    fun previewArtwork(track: NativeTrack)=api.previewArtwork(track)
    fun refreshSavedLibrary()=savedRepository.refresh()
    fun showArtists(refresh: Boolean=false)=artistController.show(refresh)
    fun selectArtist(artist: MusicArtist?)=artistController.select(artist)
    fun selectArtistAlbum(album: NativePlaylist?)=artistController.selectAlbum(album)
    fun backFromArtist()=artistController.back()
    fun pauseArtists()=artistController.pause()
    private fun artistEntries(snapshot: MusicArtistsState): List<PlaybackEntry> {
        val current=artistController.state.value
        val own=_state.value
        if(snapshot!=current||own.stage in setOf("browser_auth","configure","forget")||
            snapshot.owner!=own.probe?.owner||snapshot.readSessionId!=own.readSessionId)return emptyList()
        return PlaybackLists.artist(snapshot)
    }
    fun playArtistTrack(snapshot: MusicArtistsState,index: Int) {
        PlaybackRuntime.play(getApplication(),artistEntries(snapshot),index)
    }
    fun downloadArtistTrack(snapshot: MusicArtistsState,index: Int) {
        artistEntries(snapshot).getOrNull(index)?.let(::downloadPlayback)
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
        if(!_downloadsOpen.value)return
        Diagnostics.button("audio_downloads_dismiss");_downloadsOpen.value=false
        if(AudioExportRuntime.state.value.status.startsWith("FINISHED")&&!_state.value.busy&&!MaintenanceRuntime.busy&&!DuplicateCheckRuntime.transferring&&_state.value.probe!=null)refreshCatalog(force=false)
    }
    private suspend fun startDownloads(retry: Boolean=false) {
        // Presentation only: leaving Downloads never controls the UIDT job.
        _downloadsOpen.value=true
        queueStart?.invoke(retry)?:AudioExportRuntime.start(getApplication(),retry)
    }
    private val _artwork=MaintenanceRuntime.artwork;val artwork=_artwork.asStateFlow()
    private val _trackMetadata=MaintenanceRuntime.metadata;val trackMetadata=_trackMetadata.asStateFlow()
    fun showTrackMetadata()=maintenanceCoordinator.showTrackMetadata()
    fun dismissTrackMetadata()=maintenanceCoordinator.dismissTrackMetadata()
    fun updateSavedTrackMetadata()=maintenanceCoordinator.updateSavedTrackMetadata()
    fun showArtwork()=maintenanceCoordinator.showArtwork()
    fun dismissArtwork()=maintenanceCoordinator.dismissArtwork()
    fun addSavedArtwork()=maintenanceCoordinator.addSavedArtwork()
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
                                store.metadataRun(updated);MaintenanceRuntime.updateMetadata(s.owner){updated.state(it.visible)}
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
        _artworkLookup.update{it.copy(query=value.take(500),results=null,choice=null,session=null,phase=LookupPhase.READY,message=null)}
    }
    private suspend fun artworkAccess(initial: MusicAccess,session: MusicReadIdentity,g: Int): MusicAccess {
        active(g);session.require(initial)
        val current=sessions?.continueRead(initial)?:access()
        session.require(current);active(g)
        return current
    }
    fun findArtworkReplacement(offset: Int=0) {
        val s=_artworkLookup.value
        if(!s.visible||s.target==null||s.busy||_state.value.busy)return
        _artworkLookup.update{it.copy(phase=LookupPhase.SEARCHING,choice=null,message=null)}
        lookupWork("search",s.id){g->
            val a=sessions?.current()?:access()
            val session=MusicReadIdentity(a)
            suspend fun guard(){artworkAccess(a,session,g);Unit}
            val service=ArtworkLookup(api,source={error("Search never loads images")},guard=::guard)
            val page=service.find(a,s.query,offset)
            lookupUpdate(s.id){it.copy(phase=LookupPhase.READY,results=page,resultsAt=lookupNow(),session=session,
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
            val session=s.session?:throw MusicFailure(MusicError.ACCESS)
            suspend fun guard(){artworkAccess(a,session,g)
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
            suspend fun guard(){active(g);musicCheck(authForeground.value,MusicError.ACCESS);artworkAccess(a,choice.session,g);Unit}
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
                    code==MusicError.ACCESS->"Доступ ВК изменился или отклонён. Выполни поиск заново; при необходимости подключись через браузер."
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
            val saved=withContext(Dispatchers.IO){runCatching{DuplicateScanCache(getApplication()).use{it.count()}}.getOrDefault(0)}
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
    // Read-only availability probes have their own job and generation; they do not own
    // the catalog/file mutation gate. Auth changes reset only this controller.
    private val availabilityController=MusicAvailabilityController(viewModelScope,api,
        access={sessions?.current()?:access()},
        checked={initial->sessions?.continueRead(initial)?:access().also{MusicReadIdentity(initial).require(it)}},
        extra={
            AudioExportStore(getApplication()).use{it.unavailableTracks()}+
                _duplicates.value.report?.problems.orEmpty()
                    .filter{it.error in setOf(MusicError.UNAVAILABLE,MusicError.DENIED)}.map{it.track}
        },
        blocked={_state.value.probe==null||_state.value.stage in setOf("browser_auth","configure","forget","remove_confirm")||
            MaintenanceRuntime.busy||DuplicateCheckRuntime.transferring},
        report={snapshot->Diagnostics.event("audio_availability_result",
            mapOf("checked" to snapshot.done,"links" to snapshot.results.count{it.linkAvailable}))})
    val availability=availabilityController.state
    fun showAvailability()=availabilityController.show()
    fun dismissAvailability()=availabilityController.dismiss()
    fun checkUnavailableTracks()=availabilityController.check()
    fun pauseAvailability()=availabilityController.pause()
    private val _savedFiles=MaintenanceRuntime.files;val savedFiles=_savedFiles.asStateFlow()
    fun showSavedFiles()=savedFilesCoordinator.showSavedFiles()
    fun dismissSavedFiles()=savedFilesCoordinator.dismissSavedFiles()
    fun selectSavedFile(row: PublishedAudio)=savedFilesCoordinator.selectSavedFile(row)
    fun cancelSavedFileSelection()=savedFilesCoordinator.cancelSavedFileSelection()
    fun backSavedFiles()=savedFilesCoordinator.backSavedFiles()
    fun requestForgetFile(row: PublishedAudio)=savedFilesCoordinator.requestForgetFile(row)
    fun checkSavedFiles(search: Boolean=MaintenanceRuntime.savedSearch())=savedFilesCoordinator.checkSavedFiles(search)
    fun previewSavedFile(uri: android.net.Uri)=savedFilesCoordinator.previewSavedFile(uri)
    fun confirmSavedFile()=savedFilesCoordinator.confirmSavedFile()
    fun confirmForgetFile()=savedFilesCoordinator.confirmForgetFile()
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
    private val operationCoordinator=MusicOperationCoordinator(application,viewModelScope,_state,maintenanceLauncher,
        prepareOperation=::prepareOperation,resetAuth=::resetAuth)
    private val authCoordinator=MusicAuthCoordinator(_state,deviceAuth,api,authForeground,authClock,authPollMs,authBrowser,
        operation=::run,active=::active,isCurrent={it==generation},
        save={credential->withContext(Dispatchers.IO){vault.save(credential)}},reload={sessions?.reload();Unit},
        acceptProbe=::acceptProbe,stopOnline=PlaybackRuntime::stopOnline)
    private val maintenanceCoordinator=MusicMaintenanceCoordinator(application,viewModelScope,_state,api,
        operation=::run,active=::active,savedAccount={vault.read()?.accountId},
        currentAccess={sessions?.current()?:access()},
        continueRead={initial->sessions?.continueRead(initial)?:access().also {
            if(it.credentialId!=initial.credentialId)throw MusicReadChanged()
        }},maintenanceBeforeItem=maintenanceBeforeItem)
    private val savedFilesCoordinator=MusicSavedFilesCoordinator(application,_state,::run,::active,maintenanceBeforeItem)
    private val work: Job? get()=operationCoordinator.work
    private var catalogVerificationWork: Job?=null
    private var lastLibraryRefreshMs=0L
    private val generation: Int get()=operationCoordinator.generation
    init {
        stateScope.launch {
            combine(_state,MaintenanceRuntime.status){own,task->visibleState(own,task)}.collect{_visibleState.value=it}
        }
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
                if(done>0&&status.startsWith("FINISHED")&&owner==_state.value.probe?.owner&&!_state.value.busy)refreshCatalog(force=false)
            }
        }
        viewModelScope.launch {
            try {
                var initialLocal: LocalCatalogRead?=null
                var localReadFailed=false
                val loaded=withContext(Dispatchers.IO){
                    MaintenanceRuntime.initialize(application)
                    DuplicateCheckRuntime.initialize(application)
                    val interrupted=operationCoordinator.recover()
                    val access=runCatching{vault.read()}
                    access.getOrNull()?.accountId?.takeIf{it>0}?.let{owner->
                        try{AudioExportStore(application).use{store->
                            initialLocal=LocalCatalogSnapshots(store).read(owner).takeIf{it.catalog.lists.isNotEmpty()}
                        }}catch(e: CancellationException){throw e}catch(_: Exception){localReadFailed=true}
                    }
                    Triple(access.getOrNull()!=null,interrupted,access.isFailure)
                }
                _state.value=NativeMusicState(initialized=true,configured=loaded.first,
                    localCatalogAccount=initialLocal?.account,
                    catalog=initialLocal?.catalog,catalogStale=initialLocal!=null,
                    localCatalogKeys=initialLocal?.retained.orEmpty(),localCounts=initialLocal?.counts.orEmpty(),
                    error=if(localReadFailed)MusicError.STORAGE else null,
                    accessDamaged=loaded.third,
                    message=if(loaded.third)"Сохранённый доступ не удалось прочитать. Подключи ВК заново или удали только данные доступа. Музыка и очередь сохранены."
                        else if(loaded.second)"Предыдущая операция прервана. Готовые файлы сохранены; проверку можно повторить." else null)
                maintenanceOpen?.let{maintenanceOpen=null;openMaintenanceNotification(it)}
            }catch(e: CancellationException){throw e}
             catch(_: Exception){_state.value=NativeMusicState(initialized=true,error=MusicError.STORAGE)}
        }
    }
    private fun resetAuth()=authCoordinator.reset()
    private fun prepareOperation(stage: String): MusicOperationCleanup {
        // Cancel background verification before any foreground operation. Await its
        // completion before touching the download database: cancellation alone does
        // not guarantee that an in-flight SQLite/file read has released its handles.
        val cancelledVerification=catalogVerificationWork
        catalogVerificationWork=null
        cancelledVerification?.cancel()
        _state.update{if(it.catalogVerifying)it.copy(catalogVerifying=false)else it}
        if(stage in setOf("browser_auth","configure","forget")){exportReads.clear();_duplicates.value=DuplicatesState();friends.reset();artistController.reset();availabilityController.reset();searchController.reset()}
        if(stage in setOf("browser_auth","configure","forget"))_state.update{old->
            val localKeys=old.localCatalogKeys
            old.copy(catalog=old.catalog?.let{catalog->
                val lists=catalog.lists.filter{it.key !in localKeys}
                catalog.copy(lists=lists,count=(catalog.count-(catalog.lists.size-lists.size)).coerceAtLeast(0))
            },localCatalogKeys=emptySet(),localCounts=emptyMap(),localCatalogAccount=null,localPlayback=null,
                selected=if(old.localPlayback!=null)null else old.selected,
                page=if(old.localPlayback!=null)null else old.page)
        }
        if(stage in setOf("duplicates_delete","remove_confirm"))exportReads.clear()
        if(!stage.startsWith("metadata_lookup_"))_metadataLookup.value=MetadataLookupState()
        if(!stage.startsWith("cover_lookup_"))_artworkLookup.value=ArtworkLookupState()
        if(!stage.startsWith("duplicates_"))_duplicates.update{it.copy(visible=false,consent=null)}
        if(stage !in setOf("remove_inspect","remove_confirm"))_removal.value=LibraryRemovalState()
        val lookupRunId=_artworkLookup.value.id.takeIf{stage.startsWith("cover_lookup_")}
        val metadataRunId=_metadataLookup.value.id.takeIf{stage.startsWith("metadata_lookup_")}
        val duplicateRunId=_duplicates.value.scanId.takeIf{stage=="duplicates_delete"}
        return MusicOperationCleanup(cancelledVerification) {
                if(metadataRunId!=null)metadataLookupUpdate(metadataRunId){if(it.busy)it.copy(phase=LookupPhase.READY,choice=null,message="Операция прервана. Повтори поиск.")else it}
                if(lookupRunId!=null)lookupUpdate(lookupRunId){if(it.busy)it.copy(phase=LookupPhase.READY,choice=null,message="Операция прервана. Повтори поиск.") else it}
                // Cancellation can happen while waiting for the shared gate, before block() starts.
                if(duplicateRunId!=null)_duplicates.update{if(it.scanId==duplicateRunId&&it.busy)
                    it.copy(phase=DuplicatePhase.BLOCKED,consent=null,message="Проверка прервана до завершения. Готовые проверки сохранены; поиск можно продолжить.") else it}
        }
    }
    private fun run(stage: String,block: suspend(Int)->Unit)=operationCoordinator.run(stage,block)
    private suspend fun active(g: Int)=operationCoordinator.active(g)
    private suspend fun access(): MusicAccess=withContext(Dispatchers.IO){vault.read()?:throw MusicFailure(MusicError.ACCESS)}
    fun setForeground(value: Boolean) {
        friends.setForeground(value)
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
    fun authenticate()=authCoordinator.authenticate()
    fun openAuthChrome(context: android.content.Context)=authCoordinator.openAuthChrome(context)
    fun submitAuthCode(raw: String)=authCoordinator.submitAuthCode(raw)
    fun configure(token: String,agent: String)=authCoordinator.configure(token,agent)
    private var initialMusicReadAttempted=false
    fun openInitialMusic() {
        if(initialMusicReadAttempted||!_state.value.initialized||!_state.value.configured||_state.value.busy)return
        initialMusicReadAttempted=true
        openMusic()
    }
    fun openMusic(){if(_state.value.initialized&&_state.value.configured&&!_state.value.busy)checkAccess()}
    fun checkAccess(){Diagnostics.button("native_music_probe");run("probe"){probe(it)}}
    fun refreshOnResume() {
        if(_state.value.probe==null||_state.value.busy)return
        val now=android.os.SystemClock.elapsedRealtime()
        if(now-lastLibraryRefreshMs<20_000L)return
        run("resume_refresh"){probe(it)}
    }
    private suspend fun probe(g: Int) {
        exportReads.clear()
        _state.update{it.copy(error=null,catalogError=null,libraryRefreshing=true,catalogRefreshing=true,
            libraryStale=it.page!=null&&it.selected==null,catalogStale=it.catalog!=null)}
        try {
            val access=access()
            val first=MusicPagination.retryTransientPage{api.library(access)}
            active(g)
            withContext(Dispatchers.IO){
                vault.replace(access,access.withSession(verifiedAt=System.currentTimeMillis(),accountId=first.owner))
            }
            sessions?.reload()
            acceptProbe(g,access,first)
        }finally {
            if(g==generation)_state.update{it.copy(libraryRefreshing=false,catalogRefreshing=false)}
        }
    }
    private suspend fun acceptProbe(g: Int,access: MusicAccess,probe: NativeProbe) {
        active(g)
        exportReads.begin(probe.owner,access.readSessionId)
        val sameOwner=_state.value.probe?.owner==probe.owner&&_state.value.readSessionId==access.readSessionId
        val cached=if(!sameOwner)withContext(Dispatchers.IO){AudioExportStore(getApplication()).use{
            LocalCatalogSnapshots(it).read(probe.owner).takeIf{local->local.catalog.lists.isNotEmpty()}
        }}else null
        active(g)
        if(!sameOwner) {
            friends.reset()
            artistController.reset()
        }
        _state.update{old->
            if(sameOwner)old.copy(libraryRefreshing=true,catalogRefreshing=true)
            else old.copy(probe=probe,page=probe.library,selected=null,catalog=cached?.catalog,catalogError=null,readSessionId=access.readSessionId,
                hiddenCatalog=emptySet(),catalogSection=CatalogSection.LIBRARY,
                localCatalogKeys=cached?.retained.orEmpty(),localCounts=cached?.counts.orEmpty(),
                localCatalogAccount=cached?.account,localPlayback=null,
                libraryRefreshing=true,libraryStale=false,catalogRefreshing=true,catalogStale=false,
                catalogVerifying=false)
        }
        // Independent fetches: the catalogue can appear before the last track page, and
        // track positions appear as each verified page is returned. One failing source
        // does not prevent the other source from being displayed.
        supervisorScope {
            val catalogue=async {
                try{loadCatalog(g,access,probe.owner)}
                catch(e: CancellationException){throw e}
                catch(e: Exception){active(g);_state.update{it.copy(catalogError=(e as? MusicFailure)?.code?:MusicError.RESPONSE)}}
            }
            try {
                val full=fullLibrary(g,access,probe)
                active(g)
                _state.update{old->
                    old.copy(probe=full,
                        page=if(old.catalogSection==CatalogSection.LIBRARY&&old.selected==null)full.library else old.page,
                        libraryRefreshing=false,libraryStale=false)
                }
            }catch(e: CancellationException){throw e}
             catch(e: Exception) {
                active(g)
                _state.update{it.copy(error=(e as? MusicFailure)?.code?:MusicError.RESPONSE,
                    libraryRefreshing=false,libraryStale=it.page?.let{part->part.tracks.size==part.count}==true)}
            }
            catalogue.await()
        }
        active(g)
        _state.update{if(it.catalogError==null&&it.error==null)
            it.copy(message="Музыкальный доступ работает. Плейлисты и альбомы показаны отдельно.")else it}
    }
    private suspend fun fullLibrary(g: Int,access: MusicAccess,proof: NativeProbe): NativeProbe {
        val lease=exportReads.lease(proof.owner,access.readSessionId)
        val page=MusicPagination.tracks({offset->
            active(g);if(offset==0)proof.library else api.libraryPage(access,proof.owner,offset)
        },progress={n,total->
            _state.update{it.copy(preparing="Получено треков: $n/$total")}
        },partial={partial->
            _state.update{old->
                if(old.probe?.owner!=proof.owner)old
                else old.copy(probe=proof.copy(library=partial),
                    page=if(old.catalogSection==CatalogSection.LIBRARY&&old.selected==null)partial else old.page,
                    libraryStale=false)
            }
        })
        active(g)
        exportReads.rememberLibrary(lease,page)
        lastLibraryRefreshMs=android.os.SystemClock.elapsedRealtime()
        Diagnostics.event("native_music_sample",mapOf("stage" to "library","entries" to page.tracks.size,
            "expected_count" to page.count,"complete" to true))
        return proof.copy(library=page)
    }
    private suspend fun downloadedCatalog(g: Int,access: MusicAccess,owner: Long,catalog: NativeCatalog): Set<String> = withContext(Dispatchers.IO) {
        AudioExportStore(getApplication()).use{store->
        val shared=SharedAudioStore(getApplication())
        val playlists=SharedPlaylistStore(getApplication(),store)
        CatalogDownloads.completed(catalog,owner,emptyList(),fetch={list->
            active(g)
            MusicSourceReader(api,exportReads).playlist(access,owner,list,guard={active(g)},progress={n,total->
                _state.update{it.copy(preparing="Проверяем скачанную подборку: $n/$total")}
            }).page
        },valid={row->active(g);shared.inspect(row).reason==SavedFileReason.OK},
            sourceReady={source,tasks,files->active(g);playlists.matches(source,tasks,files)},
            sourceRows={list->store.sourceFiles(ExportSource(owner,"catalog",list.key))})
        }
    }
    private suspend fun currentCatalog(g: Int,access: MusicAccess,owner: Long,
        publishPartial: Boolean=false,force: Boolean=true): NativeCatalog {
        val result=MusicSourceReader(api,exportReads).catalog(
        access,owner,force=force,guard={active(g)},
        partial={part->
            if(publishPartial&&g==generation)_state.update{old->
                val keys=part.lists.mapTo(hashSetOf()){it.key}
                val retained=old.catalog?.lists.orEmpty().filter{it.key in old.localCatalogKeys&&it.key !in keys}
                old.copy(catalog=part.copy(lists=part.lists+retained,count=part.count+retained.size),
                    hiddenCatalog=emptySet(),catalogStale=false)
            }
        },
        onPagination={source,part->
            val scan=part.pagination
            Diagnostics.event("native_catalog_pagination",mapOf(
                "source" to source,"pages" to scan?.pages,"positions" to scan?.positions,
                "received_positions" to scan?.received,"unique_entries" to part.lists.size,
                "expected_count" to part.count,"retries" to scan?.retries,
                "stop_reason" to scan?.reason?.name,"complete" to part.complete))
        },
        progress={n,total->_state.update{it.copy(preparing="Получено подборок: $n/$total")}}
        )
        active(g)
        withContext(Dispatchers.IO){AudioExportStore(getApplication()).use{store->
            val snapshots=LocalCatalogSnapshots(store)
            result.lists.forEach{list->active(g);snapshots.remember(owner,list)}
        }}
        active(g)
        return result
    }
    /** Verify only planned sources, using their already-read membership, never a second VK pass. */
    private suspend fun completedPrepared(g: Int,owner: Long,prepared: PreparedExport): Set<String> {
        val bySource=prepared.tasks.groupBy{it.source!!.id}
        val lists=prepared.catalog?.lists.orEmpty().filter{it.key in bySource}
        if(lists.isEmpty())return emptySet()
        return AudioExportStore(getApplication()).use{store->
            val shared=SharedAudioStore(getApplication());val playlists=SharedPlaylistStore(getApplication(),store)
            CatalogDownloads.completed(NativeCatalog(lists,lists.size),owner,emptyList(),
                fetch={list->active(g);val tasks=bySource.getValue(list.key);NativePage(tasks.map{it.track},tasks.size)},
                valid={row->active(g);shared.inspect(row).reason==SavedFileReason.OK},
                sourceReady={source,tasks,files->active(g);playlists.matches(source,tasks,files)},
                tasksFor={list,_->bySource.getValue(list.key)},
                sourceRows={list->store.sourceFiles(ExportSource(owner,"catalog",list.key))})
        }
    }
    private suspend fun loadCatalog(g: Int,access: MusicAccess,owner: Long,force: Boolean=true) {
        val previousCatalog=_state.value.catalog
        val previousHidden=_state.value.hiddenCatalog
        _state.update{it.copy(catalogRefreshing=true,catalogStale=it.catalog!=null,catalogError=null)}
        try {
            val remote=currentCatalog(g,access,owner,publishPartial=true,force=force)
            active(g)
            val local=withContext(Dispatchers.IO){AudioExportStore(getApplication()).use{LocalCatalogSnapshots(it).available(owner,remote,presence={files->SharedAudioStore(getApplication()).presence(files)})}}
            active(g)
            val catalog=local.catalog
            _state.update{old->
                val selected=old.selected?.let{prior->catalog.lists.firstOrNull{it.key==prior.key}}
                old.copy(catalog=catalog,hiddenCatalog=emptySet(),catalogError=null,catalogStale=false,
                    localCatalogKeys=local.retained,localCounts=local.counts,localCatalogAccount=owner,
                    localPlayback=old.localPlayback?.takeIf{it.account==owner&&it.key==selected?.key},
                    selected=selected,page=if(old.selected!=null&&selected==null)null else old.page,
                    catalogRefreshing=false,catalogVerifying=true)
            }
            Diagnostics.event("native_music_sample",mapOf("stage" to "catalog","entries" to catalog.lists.size,
                "expected_count" to catalog.count,"complete" to catalog.complete,
                "base_complete" to catalog.baseComplete,
                "optional_albums_incomplete" to catalog.optionalAlbumsIncomplete))
            // The catalogue is now usable. Verification of locally downloaded collections
            // can require extra VK pages and disk reads; it must not block music browsing.
            catalogVerificationWork?.cancel()
            catalogVerificationWork=viewModelScope.launch {
                try {
                    while(isActive&&_state.value.busy)delay(100L)
                    currentCoroutineContext().ensureActive()
                    val hidden=downloadedCatalog(g,access,owner,remote)
                    if(g==generation&&_state.value.probe?.owner==owner&&_state.value.catalog===catalog)
                        _state.update{it.copy(hiddenCatalog=hidden)}
                }catch(e: CancellationException){throw e}
                 catch(e: Exception){
                    Diagnostics.event("native_catalog_download_verification_failed",
                        mapOf("reason" to ((e as? MusicFailure)?.code?.name?:"STORAGE")))
                 }finally {
                    if(_state.value.catalog===catalog)_state.update{it.copy(catalogVerifying=false)}
                }
            }
        }catch(e: CancellationException){throw e}
         catch(e: Exception){
            if(g==generation)_state.update{it.copy(catalog=previousCatalog?:it.catalog,
                hiddenCatalog=if(previousCatalog!=null)previousHidden else emptySet(),
                catalogStale=true,catalogError=(e as? MusicFailure)?.code?:MusicError.RESPONSE)}
            throw e
        }finally {
            if(g==generation)_state.update{it.copy(catalogRefreshing=false)}
        }
    }
    fun refreshCatalog(force: Boolean=true) {
        val owner=_state.value.probe?.owner?:run {
            if(_state.value.localCatalogAccount!=null)checkAccess()
            return
        }
        run("catalog_refresh"){g->
            try {
                val a=access()
                if(force)exportReads.restartCatalog(exportReads.lease(owner,a.readSessionId))
                loadCatalog(g,a,owner,force)
            }catch(e: CancellationException){throw e}
            catch(e: Exception){active(g);_state.update{it.copy(catalogError=(e as? MusicFailure)?.code?:MusicError.STORAGE)}}
        }
    }
    fun section(section: CatalogSection)=catalogController.section(section)
    fun select(list: NativePlaylist?) {
        Diagnostics.button(if(list==null)"native_music_library" else "native_music_playlist")
        catalogController.select(list)
    }
    /** Preparing metadata stays in the visible Activity; audio is handed to a persisted UIDT queue. */
    fun prepare(catalogKind: PlaylistKind?=null,libraryAlbums: Boolean=false) {
        val s=_state.value;val owner=s.probe?.owner?:return
        Diagnostics.button(if(catalogKind!=null)"audio_queue_prepare_category" else "audio_queue_prepare_source")
        run("prepare") {g->
            withContext(Dispatchers.IO) {
                AudioExportRuntime.initialize(getApplication())
                musicCheck(!AudioExportRuntime.state.value.unfinished&&!AudioExportRuntime.active.get(),MusicError.BUSY)
                val a=sessions?.current()?:access();active(g)
                musicCheck(s.readSessionId!=null&&a.readSessionId==s.readSessionId,MusicError.ACCESS)
                musicCheck(a.accountId==null||a.accountId==owner,MusicError.ACCOUNT_CHANGED)
                musicCheck(api.accountOwner(a)==owner,MusicError.ACCOUNT_CHANGED);active(g)
                val prepared=ExportPreparation(api,exportReads,guard={active(g)},
                    progress={text->_state.update{it.copy(preparing=text)}}).plan(a,owner,s.selected,catalogKind,libraryAlbums,
                    loadCatalog={currentCatalog(g,a,owner)})
                val completed=if(catalogKind!=null)completedPrepared(g,owner,prepared)else emptySet()
                if(sessions!=null)sessions.continueRead(a)
                else musicCheck(vault.read()?.readSessionId==a.readSessionId,MusicError.ACCESS)
                active(g)
                prepared.selected?.let{read->_state.update{old->old.copy(selected=read.list,page=read.page,
                    catalog=old.catalog?.let{catalog->catalog.copy(lists=catalog.lists.map{if(it.key==read.list.key)read.list else it})})}}
                prepared.catalog?.let{catalog->_state.update{old->old.copy(catalog=catalog,
                    hiddenCatalog=(old.hiddenCatalog.intersect(catalog.lists.map{it.key}.toSet())-
                        prepared.tasks.mapNotNull{it.source?.id}.toSet())+completed,
                    selected=if(catalogKind!=null)null else old.selected,
                    page=if(catalogKind!=null)null else old.page)}}
                val tasks=prepared.tasks.filter{it.source?.id !in completed};val failures=prepared.failures;val issues=failures.size
                if(tasks.isEmpty()&&failures.isEmpty()){_state.update{it.copy(message="Новых треков для скачивания нет.")};return@withContext}
                active(g)
                AudioExportStore(getApplication()).use{store->
                    val snapshots=LocalCatalogSnapshots(store)
                    val lists=(prepared.catalog?.lists.orEmpty()+listOfNotNull(prepared.selected?.list)).associateBy{it.key}
                    prepared.tasks.filter{it.source?.account==owner&&it.source.kind=="catalog"}.groupBy{it.source!!.id}.forEach{(key,rows)->
                        val list=lists[key]
                        if(list!=null&&rows.size==list.count)
                            snapshots.remember(owner,list,NativePage(rows.map{it.track},rows.size))
                    }
                }
                active(g);AudioExportRuntime.replace(getApplication(),tasks,account=owner,sources=failures)
                active(g);startDownloads()
                _state.update{it.copy(message=(if(prepared.reusedSources>0)"Очередь подготовлена по результатам последнего анализа. " else "Очередь готова. ")+"Файлы сохраняются в Music/VK Music Export. Можно свернуть приложение."+
                    (if(issues>0)" Не включены или не прочитаны источники: $issues. См. итог очереди." else ""))}
            }
        }
    }
    fun downloadPlayback(entry: PlaybackEntry) {
        val task=entry.download?:return
        if(entry.offline||_state.value.busy)return
        run("player_download") {g->withContext(Dispatchers.IO) {
            AudioExportRuntime.initialize(getApplication())
            val queue=AudioExportRuntime.state.value
            musicCheck(!queue.unfinished&&queue.status !in setOf("RUNNING","QUEUED"),MusicError.BUSY)
            val a=sessions?.current()?:access()
            musicCheck(entry.readSessionId==null||entry.readSessionId==a.readSessionId,MusicError.ACCESS)
            val owner=api.accountOwner(a);active(g)
            musicCheck(owner==entry.account&&task.source?.account==owner,MusicError.ACCOUNT_CHANGED)
            api.resolve(a,task.track);active(g)
            if(sessions!=null)sessions.continueRead(a)else musicCheck(vault.read()?.readSessionId==a.readSessionId,MusicError.ACCESS)
            AudioExportRuntime.replace(getApplication(),listOf(task),account=owner)
            active(g);startDownloads(false)
        }}
    }
    fun download(track: NativeTrack,position: Int) {
        Diagnostics.button("native_music_download_one")
        val s=_state.value
        val owner=s.probe?.owner?:return
        run("queue_one") {g->withContext(Dispatchers.IO){
            val task=ExportLayout.single(s.page,track,position,owner,s.selected,
                s.selected?.kind?:PlaylistKind.UNKNOWN)
            active(g);AudioExportRuntime.initialize(getApplication())
            musicCheck(!AudioExportRuntime.state.value.unfinished&&!AudioExportRuntime.active.get(),MusicError.BUSY)
            val a=sessions?.current()?:access();active(g)
            musicCheck(s.readSessionId!=null&&a.readSessionId==s.readSessionId,MusicError.ACCESS)
            musicCheck(a.accountId==null||a.accountId==owner,MusicError.ACCOUNT_CHANGED)
            musicCheck(api.accountOwner(a)==owner,MusicError.ACCOUNT_CHANGED);active(g)
            // A verified renewal may change the credential, but not the read session.
            // Logout/re-login (including the same account) must not reuse the old snapshot.
            if(sessions!=null)sessions.continueRead(a)
            else musicCheck(vault.read()?.readSessionId==a.readSessionId,MusicError.ACCESS)
            active(g);AudioExportRuntime.replace(getApplication(),listOf(task),account=owner)
            active(g);startDownloads()
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
    fun retrySource(issue: ExportSourceFailure)=retrySource(issue,AudioExportRuntime.state.value.queueId)
    fun retrySource(issue: ExportSourceFailure,expectedQueueId: String) {
        run("source_retry"){g->withContext(Dispatchers.IO){
            musicCheck(AudioExportRuntime.state.value.queueId==expectedQueueId,MusicError.LIBRARY_CHANGED)
            if(issue.source.kind=="playlist_file") {
                AudioExportRuntime.editSaved(getApplication()) {
                    musicCheck(AudioExportRuntime.state.value.queueId==expectedQueueId,MusicError.LIBRARY_CHANGED)
                    AudioExportStore(getApplication()).use{SharedPlaylistStore(getApplication(),it).publishAll()}
                    AudioExportRuntime.refresh()
                }
                return@withContext
            }
            val a=sessions?.current()?:access()
            val proof=api.library(a);active(g)
            musicCheck(proof.owner==issue.source.account,MusicError.ACCOUNT_CHANGED)
            val catalog=currentCatalog(g,a,proof.owner)
            if(issue.source.kind=="catalog_page")musicCheck(catalog.completeFor(issue.category),MusicError.INCOMPLETE)
            val lists=if(issue.source.kind=="catalog_page")catalog.lists.filter{it.kind==issue.category}
                else listOf(catalog.lists.singleOrNull{it.key==issue.source.id}?:throw MusicFailure(MusicError.UNAVAILABLE))
            val tasks=mutableListOf<AudioExportTask>();val failures=mutableListOf<ExportSourceFailure>()
            for(list in lists) {
                val kind=list.kind
                try {
                    val page=MusicPagination.tracks({offset->active(g);api.playlistPage(a,list,offset)})
                    tasks.addAll(ExportLayout.playlist(page,list,kind,account=proof.owner))
                }catch(e: MusicFailure) {
                    if(e.code !in setOf(MusicError.DENIED,MusicError.UNAVAILABLE,MusicError.METHOD,MusicError.INCOMPLETE,MusicError.LIBRARY_CHANGED))throw e
                    failures.add(ExportSourceFailure(ExportSource(proof.owner,"catalog",list.key),list.title,e.code,kind))
                }
            }
            active(g);AudioExportRuntime.retrySource(getApplication(),issue.source,tasks,failures,expectedQueueId)
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
    fun stopForWindow(){searchController.pause();artistController.pause();availabilityController.pause();_metadataLookup.value=MetadataLookupState();_artworkLookup.value=ArtworkLookupState();if(_state.value.stage !in MaintenanceRuntime.stages)stop("native_window_closed")}
    fun stopInBackground() {
        searchController.pause()
        friends.pauseRead()
        artistController.pause()
        availabilityController.pause()
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
        operationCoordinator.invalidate();authCoordinator.reset()
        _state.update{it.copy(stopping=true,authPhase=null,authIssue=null,authCodeIssue=null,
            authCodePending=false,message="Завершаем остановку операции…")}
        work?.cancel()
    }
    fun forget() {
        PlaybackRuntime.stopOnline()
        if(_state.value.busy)return
        Diagnostics.button("native_music_forget_access")
        run("forget") {g->
            withContext(Dispatchers.IO){vault.delete();DuplicateCheckRuntime.forget(getApplication())};active(g);sessions?.reload()
            _state.value=NativeMusicState(initialized=true,busy=true,stage="forget",message="Доступ ВК удалён с устройства.")
            artistController.reset()
        }
    }
    override fun onCleared(){
        val pending=work
        if(_state.value.stage !in MaintenanceRuntime.stages)pending?.cancel()
        if(pending==null) {
            // A completed worker can clear work before the StateFlow collector
            // publishes its last state. Never freeze an outdated "busy" snapshot.
            _visibleState.value=visibleState(_state.value)
            stateScope.cancel()
        }else pending.invokeOnCompletion {
            // Cleanup can finish after viewModelScope is cancelled. Publish its final state before releasing observers.
            _visibleState.value=visibleState(_state.value);stateScope.cancel()
        }
        super.onCleared()
    }
}
