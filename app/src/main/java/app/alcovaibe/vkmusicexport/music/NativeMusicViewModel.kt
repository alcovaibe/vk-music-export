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

internal data class TrackActionMessage(val text: String, val downloadError: Boolean = false)

internal class NativeMusicViewModel @JvmOverloads constructor(application: Application,
    private val api: MusicApi=VkAudioClient(sessions=MusicSessions.get(application)),private val deviceAuth: DeviceAuthApi=VkDeviceAuthClient(),
    private val authClock: ()->Long={android.os.SystemClock.elapsedRealtime()},private val authPollMs: Long=5000,
    private val authBrowser: (android.content.Context,String)->Boolean=::openDeviceAuthBrowser,
    private val lookupImages:((ArtworkMatch)->ArtworkSource)?=null,private val artistTimeoutMs: Long=45_000,private val duplicateTransfer: AudioTransfer=AudioTransfer(),private val maintenanceLauncher: ((Job)->Unit)?=null,private val maintenanceBeforeItem: suspend(String,Int)->Unit={_,_->},private val queueStart: (suspend(Boolean)->Unit)?=null): AndroidViewModel(application) {
    private val accessChangeStages = setOf("browser_auth", "configure", "forget")
    private val libraryReadStages = setOf("probe", "resume_refresh", "catalog_refresh", "library_refresh_after_add")
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

    private val artistSubscriptionsStore = ArtistSubscriptionsStore(application)
    private val artistController=MusicArtistsController(viewModelScope,api,
        owner={_state.value.probe?.owner},access={sessions?.current()?:access()},
        checked={initial->
            sessions?.continueRead(initial)?:access().also{MusicReadIdentity(initial).require(it)}
        },blocked={_state.value.probe==null||_state.value.stage in setOf("browser_auth","configure","forget")},
        artistTimeoutMs=artistTimeoutMs,report={e->Diagnostics.event("audio_artists_failure",mapOf("code" to (e as? MusicFailure)?.code?.name,
                "vk_code" to (e as? MusicFailure)?.remoteCode,"http_status" to (e as? MusicFailure)?.httpStatus,
                "reason" to (e as? ArtistLoadingFailure)?.reason?.name,"method" to (e as? ArtistLoadingFailure)?.method,
                "pages" to (e as? ArtistLoadingFailure)?.pages,"sections" to (e as? ArtistLoadingFailure)?.sections,
                "blocks" to (e as? ArtistLoadingFailure)?.blocks,"artist_pool" to (e as? ArtistLoadingFailure)?.pool))},
        readCached = artistSubscriptionsStore::read, writeCached = artistSubscriptionsStore::write)
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
    private val _musicLink = MutableStateFlow(MusicLinkState())
    val musicLink = _musicLink.asStateFlow()
    private var linkWork: Job? = null
    private var linkRequest = 0L
    private val libraryAddReceipts = LibraryAddReceipts(application)
    private val _searchAdding = MutableStateFlow(false)
    val searchAdding = _searchAdding.asStateFlow()
    private var searchAdditionWork: Job? = null
    private val libraryRefresh = MutableStateFlow<MusicReadIdentity?>(null)
    private var libraryRevision = 0L

    fun canBrowseLibrary(state: NativeMusicState): Boolean =
        !state.stopping && (!state.busy || state.stage in libraryReadStages)
    private val _addedTracks = MutableStateFlow<Map<String, String>>(emptyMap())
    val addedTracks = _addedTracks.asStateFlow()
    private val _additionRetry = MutableStateFlow<LibraryAddition?>(null)
    val additionRetry = _additionRetry.asStateFlow()

    suspend fun loadAddedTracks(account: Long, session: String?) {
        if (account <= 0 || session == null) return
        val receipts = try { libraryAddReceipts.confirmed(account) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { return }
        if ((_state.value.probe?.owner == account && _state.value.readSessionId == session) ||
            (musicSearch.value.account == account && musicSearch.value.readSessionId == session))
            _addedTracks.update { receipts + it }
    }

    fun dismissAdditionRetry() { _additionRetry.value = null }
    fun confirmAdditionRetry() {
        val retry = _additionRetry.value ?: return
        dismissAdditionRetry()
        startLibraryAddition(retry, retryApproved = true)
    }
    private val _trackActionMessage = MutableStateFlow<TrackActionMessage?>(null)
    val trackActionMessage = _trackActionMessage.asStateFlow()

    fun openMusicLink(text: CharSequence, chooseApp: Boolean = false) {
        linkWork?.cancel()
        _trackActionMessage.value = null
        val link = VkMusicLinks.fromText(text)
        _musicLink.value = MusicLinkState(++linkRequest, link = link,
            error = if (link == null) MusicError.RESPONSE else null, awaitingChoice = link != null && chooseApp)
    }

    fun dismissMusicLink() {
        linkWork?.cancel()
        _trackActionMessage.value = null
        _musicLink.value = MusicLinkState()
    }

    fun chooseMusicLinkHere() {
        _musicLink.update { it.copy(awaitingChoice = false) }
        retryMusicLink()
    }

    fun retryMusicLink() {
        val request = _musicLink.value
        val link = request.link ?: return
        if (request.awaitingChoice || request.loading || !_state.value.initialized ||
            !_state.value.configured || _state.value.stage in accessChangeStages) return
        linkWork?.cancel()
        _musicLink.value = request.copy(loading = true, error = null)
        linkWork = viewModelScope.launch {
            try {
                val result = withTimeout(90_000) {
                    val initial = sessions?.current() ?: access()
                    readLinkedMusic(api, initial, link).also { checkRead(initial) }
                }
                if (_musicLink.value.request == request.request) _musicLink.value = request.copy(music = result)
            } catch (_: TimeoutCancellationException) {
                if (_musicLink.value.request == request.request) _musicLink.value = request.copy(error = MusicError.TIMEOUT)
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                if (_musicLink.value.request == request.request)
                    _musicLink.value = request.copy(error = (e as? MusicFailure)?.code ?: MusicError.RESPONSE)
            }
        }
    }

    private suspend fun checkRead(initial: MusicAccess): MusicAccess =
        sessions?.continueRead(initial) ?: access().also { MusicReadIdentity(initial).require(it) }

    private fun linkedEntries(snapshot: LinkedMusic): List<PlaybackEntry> =
        if (_musicLink.value.music === snapshot && _state.value.stage !in accessChangeStages &&
            api.capabilities.directAudioStream != CapabilityStatus.UNAVAILABLE) snapshot.entries() else emptyList()

    fun playLinkedMusic(snapshot: LinkedMusic, index: Int = -1) =
        PlaybackRuntime.play(getApplication(), linkedEntries(snapshot), index)

    fun downloadLinkedTrack(snapshot: LinkedMusic, index: Int) {
        linkedEntries(snapshot).getOrNull(index)?.let(::downloadPlayback)
    }

    fun downloadLinkedCollection(snapshot: LinkedMusic) {
        val entry = linkedEntries(snapshot).firstOrNull { !it.track.restricted && it.track.duration > 0 && it.track.metadataError == null }
            ?: return
        enqueuePlayback(entry, snapshot::collectionTasks)
    }

    fun addSearchTrack(snapshot: MusicSearchState, index: Int) {
        if (!searchController.isCurrent(snapshot)) return
        val track = snapshot.tracks.getOrNull(index) ?: return
        startLibraryAddition(LibraryAddition(track, snapshot.account, snapshot.readSessionId ?: return))
    }

    fun addPlaybackTrack(entry: PlaybackEntry) {
        if (PlaybackRuntime.state.value.current != entry) return
        val state = _state.value
        val account = state.probe?.owner ?: return
        val session = state.readSessionId ?: return
        if (!entry.offline && (entry.account != account || entry.readSessionId != session)) return
        if (VkMusicLinks.track(entry.track.id) == null) return
        startLibraryAddition(LibraryAddition(entry.track, account, session))
    }

    private fun startLibraryAddition(request: LibraryAddition, retryApproved: Boolean = false) {
        val state = _state.value
        if (_searchAdding.value || !state.initialized || !state.configured || !canBrowseLibrary(state) ||
            MaintenanceRuntime.busy || DuplicateCheckRuntime.transferring ||
            request.track.restricted || request.track.metadataError != null) return
        _trackActionMessage.value = null
        _searchAdding.value = true
        searchAdditionWork = viewModelScope.launch {
            try {
                libraryAddReceipts.serialized {
                    addLibraryTrack(request, retryApproved)
                }
            } finally {
                _searchAdding.value = false
            }
        }
    }

    private suspend fun addLibraryTrack(request: LibraryAddition, retryApproved: Boolean) {
        val (track, account, session) = request
        var pending = false
        try {
            val initial = sessions?.current() ?: access()
            musicCheck(initial.readSessionId == session, MusicError.ACCESS)
            musicCheck(initial.accountId == null || initial.accountId == account, MusicError.ACCOUNT_CHANGED)
            val receipt = libraryAddReceipts.read(account, track.id)
            val own = _state.value
            val page = own.probe?.takeIf { it.owner == account && own.readSessionId == session }?.library
            val existingInPage = page?.tracks?.firstOrNull { it.id == track.id || it.id == receipt }
            val usable = !own.libraryStale && !own.libraryRefreshing && page != null && page.tracks.size == page.count
            val existing = if (receipt != null && existingInPage == null && (!usable || receipt == LibraryAddReceipts.PENDING)) {
                val first = MusicPagination.retryTransientPage { api.library(initial) }
                musicCheck(first.owner == account, MusicError.ACCOUNT_CHANGED)
                MusicPagination.tracks({ offset ->
                    if (offset == 0) first.library else api.libraryPage(checkRead(initial), first.owner, offset)
                }).tracks.firstOrNull { it.id == track.id || it.id == receipt }
            } else existingInPage
            checkRead(initial)
            if (existing == null && receipt != null && !retryApproved) {
                _additionRetry.value = request
                return
            }
            val libraryId = existing?.id ?: run {
                val id = api.addLibraryTrack(checkRead(initial), account, track) {
                    checkRead(initial)
                    libraryAddReceipts.save(account, track.id, LibraryAddReceipts.PENDING)
                    pending = true
                }
                "${account}_$id"
            }
            withContext(NonCancellable) {
                libraryAddReceipts.save(account, track.id, libraryId)
                if (existing == null) {
                    libraryRevision++
                    exportReads.invalidateLibrary(exportReads.lease(account, session), account)
                    _state.update { it.copy(libraryStale = true) }
                    libraryRefresh.value = MusicReadIdentity(session, account)
                }
            }
            pending = false
            checkRead(initial)
            _addedTracks.update { it + (track.id to libraryId) }
            _trackActionMessage.value = TrackActionMessage(
                if (existing == null) "Трек добавлен в «Мою музыку»." else "Трек уже в «Моей музыке».")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = e as? MusicFailure
            val rejected = failure?.remoteCode != null && failure.code in setOf(
                MusicError.ACCESS, MusicError.DENIED, MusicError.METHOD, MusicError.RATE_LIMIT,
                MusicError.CAPTCHA, MusicError.VALIDATION)
            if (pending && rejected) try { libraryAddReceipts.clear(account, track.id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Retain the pending receipt if cleanup fails. */ }
            _trackActionMessage.value = TrackActionMessage(if (pending && !rejected)
                "ВК мог добавить трек. Проверь «Мою музыку» перед повторной попыткой."
            else "Не удалось добавить трек. " + (failure?.code ?: MusicError.RESPONSE).label)
            Diagnostics.event("native_music_operation", mapOf("stage" to "library_add", "phase" to "failed",
                "code" to failure?.code?.name, "vk_code" to failure?.remoteCode))
        }
    }

    private fun refreshAddedLibrary(request: MusicReadIdentity) {
        val owner = request.accountId ?: return
        run("library_refresh_after_add") { g ->
            val revision = libraryRevision
            _state.update { it.copy(libraryRefreshing = true) }
            try {
                val initial = sessions?.current() ?: access()
                musicCheck(initial.readSessionId == request.readSessionId &&
                    (initial.accountId == null || initial.accountId == owner), MusicError.ACCOUNT_CHANGED)
                val first = MusicPagination.retryTransientPage { api.library(initial) }
                musicCheck(first.owner == owner, MusicError.ACCOUNT_CHANGED)
                checkRead(initial)
                active(g)
                exportReads.acquire(owner, initial.readSessionId)
                val full = fullLibrary(g, initial, first, revision)
                checkRead(initial)
                active(g)
                if (revision == libraryRevision) _state.update { old ->
                    old.copy(probe = full, readSessionId = initial.readSessionId, libraryStale = false,
                        page = if (old.catalogSection == CatalogSection.LIBRARY && old.selected == null) full.library else old.page)
                }
            } catch (e: MusicFailure) {
                if (revision == libraryRevision) throw e
            } finally {
                if (g == generation) _state.update { it.copy(libraryRefreshing = false) }
            }
        }
    }

    val artistSubscriptionEvents = artistController.subscriptionEvents
    fun toggleArtistSubscription() = artistController.toggleSubscription()
    val artists=artistController.state
    private val savedRepository=SavedLibrary(application,viewModelScope)
    val savedLibrary=savedRepository.rows.asStateFlow()
    val savedLibraryError=savedRepository.error.asStateFlow()
    fun previewArtwork(track: NativeTrack)=api.previewArtwork(track)
    fun refreshSavedLibrary()=savedRepository.refresh()
    fun showArtists(refresh: Boolean=false)=artistController.show(refresh)
    fun selectSearchArtist(snapshot: MusicSearchState, artist: MusicArtist) {
        if (searchController.isCurrent(snapshot) && snapshot.creditedArtist == artist)
            artistController.selectSearchArtist(artist, snapshot.account, snapshot.readSessionId ?: return)
    }
    fun selectArtist(artist: MusicArtist?)=artistController.select(artist)
    fun selectArtistAlbum(album: NativePlaylist?)=artistController.selectAlbum(album)
    fun openArtistTracks(group: ArtistTracks)=artistController.openTracks(group)
    fun backFromArtist()=artistController.back()
    fun pauseArtists()=artistController.pause()
    private fun artistEntries(snapshot: MusicArtistsState, group: ArtistTracks): List<PlaybackEntry> {
        val current=artistController.state.value
        val own=_state.value
        if(snapshot.selected?.id!=current.selected?.id||snapshot.album?.key!=current.album?.key||snapshot.queue!=current.queue||
            snapshot.readSessionId!=current.readSessionId||own.stage in setOf("browser_auth","configure","forget")||
            snapshot.owner!=own.probe?.owner||snapshot.readSessionId!=own.readSessionId)return emptyList()
        return PlaybackLists.artist(snapshot, group)
    }
    fun playArtistTrack(snapshot: MusicArtistsState,index: Int,group: ArtistTracks = snapshot.queue ?: ArtistTracks.ALL) {
        PlaybackRuntime.play(getApplication(),artistEntries(snapshot, group),index)
    }
    fun downloadArtistTrack(snapshot: MusicArtistsState,index: Int,group: ArtistTracks = snapshot.queue ?: ArtistTracks.ALL) {
        artistEntries(snapshot, group).getOrNull(index)?.let(::downloadPlayback)
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
        _removal.update{if(it.phase==LibraryRemovalPhase.SENDING)it.copy(visible=false)else LibraryRemovalState()}
    }
    private fun isLibraryTrack(track: NativeTrack,owner: Long): Boolean {
        val s=_state.value
        return s.probe?.owner==owner&&s.selected==null&&s.catalogSection==CatalogSection.LIBRARY&&s.page?.tracks?.contains(track)==true
    }
    fun inspectRemoval(track: NativeTrack) {
        val s=_state.value;val owner=s.probe?.owner?:return
        if(!s.initialized||s.busy||!isLibraryTrack(track,owner))return
        _removal.value = try {
            LibraryRemovalProtocol.audioId(owner, track)
            LibraryRemovalState(true, LibraryRemovalPhase.CONFIRM, owner, track)
        } catch (_: MusicFailure) {
            LibraryRemovalState(true, LibraryRemovalPhase.ERROR, owner, track, "Эту запись нельзя удалить из своего профиля.")
        }
    }

    private var removalRefresh: Job? = null

    private fun scheduleRemovalRefresh(owner: Long) {
        if (removalRefresh?.isActive == true) return
        val session = _state.value.readSessionId
        removalRefresh = viewModelScope.launch {
            delay(5 * 60_000L)
            combine(_state, authForeground, _searchAdding, MaintenanceRuntime.status, _removal) { state, foreground, adding, maintenance, removal ->
                foreground && !state.busy && !adding && !maintenance.busy && !removal.visible
            }.first { it }
            if (_state.value.probe?.owner == owner && _state.value.readSessionId == session) checkAccess()
        }
    }

    fun confirmRemoval() {
        val ticket = _removal.value
        val track = ticket.track ?: return
        if (!ticket.visible || ticket.phase != LibraryRemovalPhase.CONFIRM || !isLibraryTrack(track, ticket.owner)) return
        run("remove_confirm") { g ->
            _removal.update { it.copy(phase = LibraryRemovalPhase.SENDING) }
            try {
                val access = sessions?.current() ?: access()
                active(g)
                musicCheck(isLibraryTrack(track, ticket.owner), MusicError.ACCESS)
                api.removeLibraryTrack(access, ticket.owner, track)
                active(g)
                libraryRevision++
                _state.update { state ->
                    val proof = state.probe ?: return@update state
                    if (proof.owner != ticket.owner) return@update state
                    val library = proof.library
                    val remaining = library.copy(tracks = library.tracks.filterNot { it.id == track.id },
                        count = (library.count - 1).coerceAtLeast(0))
                    state.copy(probe = proof.copy(library = remaining),
                        page = if (state.selected == null && state.catalogSection == CatalogSection.LIBRARY) remaining else state.page,
                        message = "Трек удалён из «Моей музыки».")
                }
                _removal.value = ticket.copy(visible = false, phase = LibraryRemovalPhase.DONE)
                scheduleRemovalRefresh(ticket.owner)
                _addedTracks.update { tracks -> tracks.filterValues { it != track.id } }
                try { libraryAddReceipts.removed(ticket.owner, track.id) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* The next add checks the current VK library. */ }
            } catch (e: CancellationException) {
                if (_removal.value.phase == LibraryRemovalPhase.SENDING)
                    _removal.update { it.copy(phase = LibraryRemovalPhase.ERROR, message = "Удаление прервано. Обнови библиотеку, чтобы проверить результат.") }
                throw e
            } catch (e: Exception) {
                _removal.update { it.copy(phase = LibraryRemovalPhase.ERROR,
                    message = "ВК не подтвердил удаление. Обнови библиотеку, чтобы проверить результат.") }
            }
        }
    }
    private val operationCoordinator: MusicOperationCoordinator=MusicOperationCoordinator(application,viewModelScope,_state,maintenanceLauncher,
        prepareOperation=::prepareOperation,resetAuth=::resetAuth)
    private val authCoordinator: MusicAuthCoordinator=MusicAuthCoordinator(_state,deviceAuth,api,authForeground,authClock,authPollMs,authBrowser,
        operation=::run,active=::active,isCurrent={it==generation},
        save={credential->withContext(Dispatchers.IO){vault.save(credential)}},reload={sessions?.reload();Unit},
        acceptProbe=::acceptProbe,stopOnline=PlaybackRuntime::stopOnline)
    private val maintenanceCoordinator: MusicMaintenanceCoordinator=MusicMaintenanceCoordinator(application,viewModelScope,_state,api,
        operation=::run,active=::active,savedAccount={vault.read()?.accountId},
        currentAccess={sessions?.current()?:access()},
        continueRead={initial->sessions?.continueRead(initial)?:access().also {
            if(it.credentialId!=initial.credentialId)throw MusicReadChanged()
        }},maintenanceBeforeItem=maintenanceBeforeItem)
    private val savedFilesCoordinator: MusicSavedFilesCoordinator=MusicSavedFilesCoordinator(application,_state,::run,::active,maintenanceBeforeItem)
    private val work: Job? get()=operationCoordinator.work
    private var catalogVerificationWork: Job?=null
    private var lastLibraryRefreshMs=0L
    private val generation: Int get()=operationCoordinator.generation
    init {
        viewModelScope.launch {
            authForeground.collectLatest { foreground ->
                if (foreground) while (isActive) {
                    delay(5 * 60_000L)
                    if (!_state.value.busy && removalRefresh?.isActive != true &&
                        !_removal.value.visible && !_searchAdding.value && !MaintenanceRuntime.busy &&
                        android.os.SystemClock.elapsedRealtime() - lastLibraryRefreshMs >= 5 * 60_000L) refreshOnline()
                }
            }
        }
        viewModelScope.launch {
            combine(_state, _searchAdding, authForeground, libraryRefresh, MaintenanceRuntime.status) { state, adding, foreground, request, maintenance ->
                request?.takeIf { foreground && state.configured && !state.busy && !adding && !maintenance.busy }
            }.collect { request ->
                if (request != null && request == libraryRefresh.value && authForeground.value && !_state.value.busy &&
                    !_searchAdding.value && !DuplicateCheckRuntime.transferring) {
                    libraryRefresh.value = null
                    refreshAddedLibrary(request)
                }
            }
        }
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
    private fun resetAuth(): Unit=authCoordinator.reset()
    private fun prepareOperation(stage: String): MusicOperationCleanup {
        if (stage in accessChangeStages || stage in setOf("remove_confirm", "duplicates_delete", "catalog_refresh", "library_refresh_after_add"))
            cancelOnlineRefresh()
        // Cancel background verification before any foreground operation. Await its
        // completion before touching the download database: cancellation alone does
        // not guarantee that an in-flight SQLite/file read has released its handles.
        val cancelledVerification=catalogVerificationWork
        catalogVerificationWork=null
        cancelledVerification?.cancel()
        _state.update{if(it.catalogVerifying)it.copy(catalogVerifying=false)else it}
        if (stage in accessChangeStages) {
            removalRefresh?.cancel(); removalRefresh = null
            exportReads.clear();_duplicates.value=DuplicatesState();friends.reset();artistController.reset();availabilityController.reset();searchController.reset()
            linkWork?.cancel()
            _musicLink.update { it.copy(music = null, loading = false, error = if (it.link == null) it.error else null) }
            _addedTracks.value = emptyMap()
            libraryRefresh.value = null
            dismissAdditionRetry()
            _trackActionMessage.value = null
        }
        if(stage in accessChangeStages)_state.update{old->
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
        if(stage != "remove_confirm")_removal.value=LibraryRemovalState()
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
    private fun run(stage: String, block: suspend (Int) -> Unit) {
        if (_searchAdding.value && stage !in libraryReadStages) {
            _state.update { it.copy(error = MusicError.BUSY) }
            return
        }
        operationCoordinator.run(stage, block)
    }
    private suspend fun active(g: Int): Unit=operationCoordinator.active(g)
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
    private var onlineRefresh: Job? = null
    @Volatile private var refreshGeneration = 0L

    private fun cancelOnlineRefresh() {
        refreshGeneration++
        onlineRefresh?.cancel()
        onlineRefresh = null
        _state.update { it.copy(libraryRefreshing = false, catalogRefreshing = false) }
    }

    fun checkAccess() {
        Diagnostics.button("native_music_probe")
        if (_state.value.busy) return
        removalRefresh?.cancel()
        removalRefresh = null
        if (_state.value.probe == null) run("probe") { probe(it) }
        else refreshOnline(includeArtists = true)
    }

    fun refreshOnResume() {
        if (removalRefresh?.isActive == true || _state.value.probe == null || _state.value.busy) return
        if (android.os.SystemClock.elapsedRealtime() - lastLibraryRefreshMs >= 20_000L) refreshOnline()
    }

    private fun refreshOnline(includeArtists: Boolean = false, catalogOnly: Boolean = false, forceCatalog: Boolean = true) {
        val snapshot = _state.value
        val owner = snapshot.probe?.owner ?: return
        val session = snapshot.readSessionId ?: return
        if (onlineRefresh?.isActive == true) return
        val epoch = ++refreshGeneration
        val revision = libraryRevision
        val read = CatalogRead(true) {
            epoch == refreshGeneration && _state.value.probe?.owner == owner && _state.value.readSessionId == session
        }
        val existingLease = exportReads.acquire(owner, session) ?: return
        val lease = if (catalogOnly && forceCatalog) exportReads.restartCatalog(existingLease) ?: return else existingLease
        val window = exportReads.startRefresh(lease, snapshot.selected)
        onlineRefresh = viewModelScope.launch {
            _state.update { it.copy(libraryRefreshing = !catalogOnly, catalogRefreshing = true, libraryStale = !catalogOnly || it.libraryStale,
                catalogStale = it.catalog != null, error = null) }
            try {
                val access = sessions?.current() ?: access()
                musicCheck(access.readSessionId == session && (access.accountId == null || access.accountId == owner),
                    MusicError.ACCOUNT_CHANGED)
                if (catalogOnly) {
                    loadCatalog(read, access, owner, force = forceCatalog)
                    return@launch
                }
                val first = MusicPagination.retryTransientPage { api.library(access) }
                read.guard()
                musicCheck(first.owner == owner, MusicError.ACCOUNT_CHANGED)
                checkRead(access)
                read.guard()
                if (includeArtists && artistController.state.value.selected == null && api.capabilities.artists != CapabilityStatus.UNAVAILABLE) artistController.show(refresh = true)
                coroutineScope {
                    launch {
                        try {
                            loadCatalog(read, access, owner)
                            val selected = _state.value.selected
                            if (selected != null && selected.key == snapshot.selected?.key &&
                                snapshot.page != null && _state.value.page === snapshot.page &&
                                selected.key !in _state.value.localCatalogKeys && _state.value.catalogError == null) {
                                val fresh = MusicPagination.tracks({ offset ->
                                    read.guard()
                                    api.playlistPage(access, selected, offset)
                                })
                                checkRead(access)
                                read.guard()
                                val observed = exportReads.observePlaylist(lease, selected, fresh)
                                _state.update { old ->
                                    if (old.selected?.key != selected.key || old.page !== snapshot.page) old
                                    else old.copy(page = fresh, selected = observed, catalog = old.catalog?.let { catalog ->
                                        catalog.copy(lists = catalog.lists.map { if (it.key == observed.key) observed else it })
                                    })
                                }
                            }
                        }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            read.guard()
                            _state.update { it.copy(catalogStale = true, catalogError = (e as? MusicFailure)?.code ?: MusicError.RESPONSE) }
                        }
                    }
                    try {
                        val page = MusicPagination.tracks({ offset ->
                            read.guard()
                            if (offset == 0) first.library else api.libraryPage(access, owner, offset)
                        })
                        checkRead(access)
                        read.guard()
                        if (revision == libraryRevision) {
                            exportReads.rememberLibrary(lease, page)
                            _state.update { old -> old.copy(probe = NativeProbe(owner, page), libraryStale = false,
                                page = if (old.selected == null && old.catalogSection == CatalogSection.LIBRARY) page else old.page) }
                            lastLibraryRefreshMs = android.os.SystemClock.elapsedRealtime()
                        }
                    } catch (e: CancellationException) { throw e }
                      catch (e: Exception) {
                        read.guard()
                        _state.update { it.copy(error = (e as? MusicFailure)?.code ?: MusicError.RESPONSE) }
                    }
                }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                read.guard()
                val error = (e as? MusicFailure)?.code ?: MusicError.RESPONSE
                _state.update { if (catalogOnly) it.copy(catalogError = error) else it.copy(error = error) }
            } finally {
                exportReads.finishRefresh(window)
                if (read.current()) _state.update { it.copy(libraryRefreshing = false, catalogRefreshing = false) }
            }
        }
    }

    private suspend fun probe(g: Int) {
        val revision = libraryRevision
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
            acceptProbe(g,access,first,revision)
        }finally {
            if(g==generation)_state.update{it.copy(libraryRefreshing=false,catalogRefreshing=false)}
        }
    }
    private suspend fun acceptProbe(g: Int, access: MusicAccess, probe: NativeProbe) =
        acceptProbe(g, access, probe, libraryRevision)

    private suspend fun acceptProbe(g: Int, access: MusicAccess, probe: NativeProbe, revision: Long) {
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
                libraryRefreshing=true,libraryStale=revision != libraryRevision,catalogRefreshing=true,catalogStale=false,
                catalogVerifying=false)
        }
        if (_state.value.stage == "probe" && api.capabilities.artists != CapabilityStatus.UNAVAILABLE) artistController.show(refresh = true)
        else if (_state.value.stage !in accessChangeStages) artistController.preload()
        // Independent fetches: the catalogue can appear before the last track page, and
        // track positions appear as each verified page is returned. One failing source
        // does not prevent the other source from being displayed.
        supervisorScope {
            val catalogue=async {
                try{loadCatalog(foregroundCatalog(g),access,probe.owner)}
                catch(e: CancellationException){throw e}
                catch(e: Exception){active(g);_state.update{it.copy(catalogError=(e as? MusicFailure)?.code?:MusicError.RESPONSE)}}
            }
            try {
                val full = fullLibrary(g, access, probe, revision)
                active(g)
                if (revision == libraryRevision) _state.update { old ->
                    old.copy(probe=full,
                        page=if(old.catalogSection==CatalogSection.LIBRARY&&old.selected==null)full.library else old.page,
                        libraryRefreshing=false,libraryStale=false)
                }
            }catch(e: CancellationException){throw e}
             catch(e: Exception) {
                active(g)
                _state.update { old ->
                    if (revision != libraryRevision) old.copy(libraryRefreshing = false, libraryStale = true)
                    else old.copy(error = (e as? MusicFailure)?.code ?: MusicError.RESPONSE,
                        libraryRefreshing = false, libraryStale = old.page?.let { it.tracks.size == it.count } == true)
                }
            }
            catalogue.await()
        }
        active(g)
        _state.update{if(it.catalogError==null&&it.error==null)
            it.copy(message="Музыкальный доступ работает. Плейлисты и альбомы показаны отдельно.")else it}
    }
    private suspend fun fullLibrary(g: Int, access: MusicAccess, proof: NativeProbe,
        revision: Long = libraryRevision): NativeProbe {
        val lease=exportReads.lease(proof.owner,access.readSessionId)
        val page=MusicPagination.tracks({offset->
            active(g)
            musicCheck(revision == libraryRevision, MusicError.LIBRARY_CHANGED)
            if(offset==0)proof.library else api.libraryPage(access,proof.owner,offset)
        },progress={n,total->
            _state.update{it.copy(preparing="Получено треков: $n/$total")}
        },partial={partial->
            _state.update{old->
                if(old.probe?.owner!=proof.owner || revision != libraryRevision)old
                else old.copy(probe=proof.copy(library=partial),
                    page=if(old.catalogSection==CatalogSection.LIBRARY&&old.selected==null)partial else old.page,
                    libraryStale=false)
            }
        })
        active(g)
        if (revision == libraryRevision) exportReads.rememberLibrary(lease,page)
        lastLibraryRefreshMs=android.os.SystemClock.elapsedRealtime()
        Diagnostics.event("native_music_sample",mapOf("stage" to "library","entries" to page.tracks.size,
            "expected_count" to page.count,"complete" to true))
        return proof.copy(library=page)
    }
    private class CatalogRead(val background: Boolean, val current: () -> Boolean) {
        suspend fun guard() {
            currentCoroutineContext().ensureActive()
            if (!current()) throw CancellationException()
        }
    }
    private fun foregroundCatalog(g: Int) = CatalogRead(false) { g == generation }

    private suspend fun downloadedCatalog(read: CatalogRead,access: MusicAccess,owner: Long,catalog: NativeCatalog): Set<String> = withContext(Dispatchers.IO) {
        AudioExportStore(getApplication()).use{store->
        val shared=SharedAudioStore(getApplication())
        val playlists=SharedPlaylistStore(getApplication(),store)
        CatalogDownloads.completed(catalog,owner,emptyList(),fetch={list->
            read.guard()
            MusicSourceReader(api,exportReads).playlist(access,owner,list,guard={read.guard()},progress={n,total->
                if (!read.background) _state.update{it.copy(preparing="Проверяем скачанную подборку: $n/$total")}
            }).page
        },valid={row->read.guard();shared.inspect(row).reason==SavedFileReason.OK},
            sourceReady={source,tasks,files->read.guard();playlists.matches(source,tasks,files)},
            sourceRows={list->store.sourceFiles(ExportSource(owner,"catalog",list.key))})
        }
    }
    private suspend fun currentCatalog(read: CatalogRead,access: MusicAccess,owner: Long,
        publishPartial: Boolean=false,force: Boolean=true): NativeCatalog {
        val result=MusicSourceReader(api,exportReads).catalog(
        access,owner,force=force,guard={read.guard()},
        partial={part->
            if(publishPartial&&read.current())_state.update{old->
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
        progress={n,total->if (!read.background) _state.update{it.copy(preparing="Получено подборок: $n/$total")}}
        )
        read.guard()
        withContext(Dispatchers.IO){AudioExportStore(getApplication()).use{store->
            val snapshots=LocalCatalogSnapshots(store)
            result.lists.forEach{list->read.guard();snapshots.remember(owner,list)}
        }}
        read.guard()
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
    private suspend fun loadCatalog(read: CatalogRead,access: MusicAccess,owner: Long,force: Boolean=true) {
        val previousCatalog=_state.value.catalog
        val previousHidden=_state.value.hiddenCatalog
        _state.update{it.copy(catalogRefreshing=true,catalogStale=it.catalog!=null,catalogError=null)}
        try {
            val remote=currentCatalog(read,access,owner,publishPartial=!read.background,force=force)
            if (read.background && previousCatalog?.complete == true)
                musicCheck(remote.complete, MusicError.INCOMPLETE)
            read.guard()
            val local=withContext(Dispatchers.IO){AudioExportStore(getApplication()).use{LocalCatalogSnapshots(it).available(owner,remote,presence={files->SharedAudioStore(getApplication()).presence(files)})}}
            read.guard()
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
                    val hidden=downloadedCatalog(read,access,owner,remote)
                    if(read.current()&&_state.value.probe?.owner==owner&&_state.value.catalog===catalog)
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
            if(read.current())_state.update{it.copy(catalog=previousCatalog?:it.catalog,
                hiddenCatalog=if(previousCatalog!=null)previousHidden else emptySet(),
                catalogStale=true,catalogError=(e as? MusicFailure)?.code?:MusicError.RESPONSE)}
            throw e
        }finally {
            if(read.current())_state.update{it.copy(catalogRefreshing=false)}
        }
    }
    fun refreshCatalog(force: Boolean=true) {
        if (force && removalRefresh?.isActive == true) {
            checkAccess()
            return
        }
        if (_state.value.busy) return
        if (_state.value.probe != null) refreshOnline(catalogOnly = true, forceCatalog = force)
        else if (_state.value.localCatalogAccount != null) checkAccess()
    }
    fun section(section: CatalogSection) = catalogController.section(section, allowDuringRead = canBrowseLibrary(_state.value))
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
                    loadCatalog={currentCatalog(foregroundCatalog(g),a,owner)})
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
        enqueuePlayback(entry) { listOfNotNull(entry.download) }
    }

    private fun enqueuePlayback(entry: PlaybackEntry, tasks: () -> List<AudioExportTask>) {
        if (entry.offline || entry.export == null || _state.value.busy) return
        _trackActionMessage.value = null
        run("player_download") { g -> withContext(Dispatchers.IO) {
            try {
                AudioExportRuntime.initialize(getApplication())
                val queue = AudioExportRuntime.state.value
                musicCheck(!queue.unfinished && queue.status !in setOf("RUNNING", "QUEUED") &&
                    !AudioExportRuntime.active.get(), MusicError.BUSY)
                val initial = sessions?.current() ?: access()
                musicCheck(entry.readSessionId == null || entry.readSessionId == initial.readSessionId, MusicError.ACCESS)
                val owner = initial.accountId ?: api.accountOwner(initial)
                active(g)
                musicCheck(owner == entry.account, MusicError.ACCOUNT_CHANGED)
                val items = tasks()
                musicCheck(items.isNotEmpty(), MusicError.UNAVAILABLE)
                musicCheck(items.all { it.source?.account == owner }, MusicError.ACCOUNT_CHANGED)
                // Search and shared links can supply the worker with an already-issued stream.
                if (entry.export.source.kind != "search") api.resolve(initial, entry.track)
                checkRead(initial)
                active(g)
                AudioExportRuntime.replace(getApplication(), items, account = owner)
                active(g)
                if (entry.export.source.kind == "search") {
                    _trackActionMessage.value = TrackActionMessage("Скачивание добавлено в очередь.")
                    PlaybackRuntime.expanded.value = false
                }
                startDownloads()
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                _trackActionMessage.value = TrackActionMessage("Не удалось начать скачивание. " +
                    ((e as? MusicFailure)?.code ?: MusicError.RESPONSE).label, downloadError = true)
                throw e
            }
        } }
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
            val catalog=currentCatalog(foregroundCatalog(g),a,proof.owner)
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
    fun stopForWindow(){searchAdditionWork?.cancel();searchController.pause();artistController.pause();availabilityController.pause();_metadataLookup.value=MetadataLookupState();_artworkLookup.value=ArtworkLookupState();if(_state.value.stage !in MaintenanceRuntime.stages)stop("native_window_closed")}
    fun stopInBackground() {
        searchAdditionWork?.cancel()
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
        cancelOnlineRefresh()
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
            withContext(Dispatchers.IO){friends.clearPersisted();vault.delete();DuplicateCheckRuntime.forget(getApplication())};active(g);sessions?.reload()
            _state.value=NativeMusicState(initialized=true,busy=true,stage="forget",message="Доступ ВК удалён с устройства.")
            artistController.reset()
        }
    }
    override fun onCleared(){
        cancelOnlineRefresh()
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
