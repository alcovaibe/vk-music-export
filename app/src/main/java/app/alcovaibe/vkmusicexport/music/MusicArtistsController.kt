package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class MusicArtistsState(val owner: Long=0,val loaded: Boolean=false,val artists: List<MusicArtist> = emptyList(),
    val selected: MusicArtist?=null,val details: MusicArtistDetails?=null,val error: MusicError?=null,
    val album: NativePlaylist?=null,val albumTracks: NativePage?=null,val requests: Int=0,val message: String?=null,val errorDetails: String?=null,val busy: Boolean=false,val stage: String="",val progress: String?=null,
    val readSessionId: String?=null,val queue: ArtistTracks?=null,val subscriptionPending: Boolean=false)

/** Screen-owned artist reads never acquire the file/maintenance gate. */
internal class MusicArtistsController(
    private val scope: CoroutineScope,
    private val api: MusicApi,
    private val owner: ()->Long?,
    private val access: suspend()->MusicAccess,
    private val checked: suspend(MusicAccess)->MusicAccess,
    private val blocked: ()->Boolean,
    private val artistTimeoutMs: Long=45_000,
    private val report: (Exception)->Unit={},
    private val readCached: suspend (Long, String) -> List<MusicArtist>? = { _, _ -> null },
    private val writeCached: suspend (Long, String, List<MusicArtist>) -> Unit = { _, _, _ -> },
) {
    private val _artists=MutableStateFlow(MusicArtistsState())
    val state=_artists.asStateFlow()
    private var work: Job?=null
    private var generation=0
    private var fetchedSession: String? = null
    private var subscriptionJob: Job? = null
    private val _subscriptionEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val subscriptionEvents = _subscriptionEvents.asSharedFlow()

    fun toggleSubscription() {
        val snapshot = _artists.value
        val artist = snapshot.selected ?: return
        val session = snapshot.readSessionId ?: return
        if (blocked() || owner() != snapshot.owner || snapshot.subscriptionPending) return
        val follow = snapshot.artists.none { it.id == artist.id }
        _artists.update { it.copy(subscriptionPending = true) }
        subscriptionJob = scope.launch {
            try {
                val credential = access()
                musicCheck(credential.readSessionId == session && (credential.accountId == null || credential.accountId == snapshot.owner),
                    MusicError.ACCOUNT_CHANGED)
                api.setArtistFollowed(checked(credential), snapshot.owner, artist.id, follow)
                checked(credential)
                ensureActive()
                if (_artists.value.readSessionId != session || owner() != snapshot.owner) return@launch
                _artists.update { current ->
                    val others = current.artists.filterNot { it.id == artist.id }
                    current.copy(artists = if (follow) listOf(artist) + others else others)
                }
                _subscriptionEvents.tryEmit(if (follow) "Вы подписались на исполнителя" else "Вы отписались от исполнителя")
                val updated = _artists.value
                if (updated.loaded) try { writeCached(snapshot.owner, session, updated.artists) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { fetchedSession = null }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                fetchedSession = null
                _subscriptionEvents.tryEmit("ВК не подтвердил изменение подписки")
            } finally {
                if (isActive) _artists.update { it.copy(subscriptionPending = false) }
            }
        }
    }

    fun reset(){subscriptionJob?.cancel();pause();fetchedSession=null;_artists.value=MusicArtistsState()}
    fun pause(){generation++;work?.cancel();work=null;_artists.update{it.copy(busy=false,stage="",progress=null)}}
    private suspend fun active(g: Int){currentCoroutineContext().ensureActive();if(g!=generation)throw CancellationException()}
    private fun run(stage: String,body: suspend(Int)->Unit){
        if(blocked())return
        work?.cancel();val g=++generation
        _artists.update{it.copy(busy=true,stage=stage,progress=null)}
        work=scope.launch {
            try{body(g)}finally{if(g==generation){work=null;_artists.update{it.copy(busy=false,stage="",progress=null)}}}
        }
    }
    fun preload() {
        val snapshot = _artists.value
        if (api.capabilities.artists != CapabilityStatus.UNAVAILABLE && snapshot.selected == null &&
            !snapshot.busy && (snapshot.owner != owner() || fetchedSession == null)) show()
    }

    fun show(refresh: Boolean = false) {
        val owner = owner() ?: return
        if (blocked() || _artists.value.subscriptionPending) return
        val before = _artists.value
        if (!refresh && before.owner == owner) {
            if (before.busy && before.stage == "artists") return
            if (before.loaded && fetchedSession == before.readSessionId) {
                if (before.selected != null) select(null)
                return
            }
        }
        _artists.value = if (before.owner == owner && before.loaded)
            MusicArtistsState(owner = owner, loaded = true, artists = before.artists, readSessionId = before.readSessionId)
        else MusicArtistsState(owner = owner)

        run("artists") { g ->
            try {
                val a = access()
                musicCheck(a.accountId == null || a.accountId == owner, MusicError.ACCOUNT_CHANGED)
                checked(a)
                active(g)
                val cached = if (before.owner == owner && before.loaded && before.readSessionId == a.readSessionId)
                    before.artists
                else try { readCached(owner, a.readSessionId) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
                checked(a)
                active(g)
                _artists.update { it.copy(readSessionId = a.readSessionId, loaded = cached != null, artists = cached.orEmpty()) }
                val rows = withTimeout(artistTimeoutMs) {
                    api.followedArtists(a, owner) { partial, requests ->
                        if (g == generation)
                            _artists.update {
                                it.copy(artists = if (it.loaded) it.artists else partial.distinctBy(MusicArtist::id),
                                    requests = requests, progress = "Подписки: ${partial.size} · запросов: $requests")
                            }
                    }
                }.distinctBy(MusicArtist::id)
                checked(a)
                active(g)
                fetchedSession = a.readSessionId
                _artists.value = MusicArtistsState(owner, true, rows, busy = true, stage = "artists", readSessionId = a.readSessionId)
                try { writeCached(owner, a.readSessionId, rows) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* The verified list is already available in memory. */ }
            } catch (_: TimeoutCancellationException) {
                active(g)
                _artists.update { it.copy(error = MusicError.TIMEOUT, errorDetails = null,
                    message = if (it.loaded) "ВК не ответил вовремя. Показаны сохранённые подписки."
                    else "ВК не завершил загрузку подписок за отведённое время. Уже полученные исполнители доступны; обнови список для повтора.") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                active(g)
                report(e)
                val code = (e as? MusicFailure)?.code ?: MusicError.RESPONSE
                _artists.update {
                    val retain = code !in setOf(MusicError.ACCESS, MusicError.ACCOUNT_CHANGED, MusicError.INVALID_ACCESS)
                    if (!retain) fetchedSession = null
                    it.copy(artists = if (retain) it.artists else emptyList(), loaded = retain && it.loaded,
                        error = code, errorDetails = ArtistFailureText.describe(e))
                }
            }
        }
    }
    fun selectSearchArtist(artist: MusicArtist, account: Long, session: String) {
        if (blocked() || owner() != account) return
        val current = _artists.value
        if (current.owner != account || current.readSessionId != session)
            _artists.value = MusicArtistsState(owner = account, readSessionId = session)
        openArtist(artist)
    }

    fun select(artist: MusicArtist?) {
        if(artist==null){pause();_artists.update{it.copy(selected=null,details=null,error=null,message=null,album=null,albumTracks=null,queue=null)};return}
        if(blocked())return
        val s=_artists.value
        if(s.owner!=owner()||(s.selected?.id != artist.id && s.artists.none{it.id==artist.id}))return
        openArtist(artist)
    }
    private fun openArtist(artist: MusicArtist) {
        val s = _artists.value
        _artists.update{it.copy(selected=artist,details=null,error=null,message=null,album=null,albumTracks=null,queue=null)}
        run("artist_details") {g->
            try {
                val a=access()
                musicCheck(a.accountId==null||a.accountId==s.owner,MusicError.ACCOUNT_CHANGED)
                musicCheck(s.readSessionId==a.readSessionId,MusicError.ACCESS)
                checked(a);active(g)
                // Each VK page has a request timeout. Long discographies can finish across many pages.
                val details=api.artistDetails(a,artist){partial->
                    if(g==generation&&partial.artist.id==artist.id)_artists.update{it.copy(details=partial)}
                };active(g)
                checked(a);active(g)
                _artists.update{it.copy(details=details)}
            }catch(_: TimeoutCancellationException){active(g);_artists.update{it.copy(error=MusicError.TIMEOUT,errorDetails=null,message="ВК не завершил запрос вовремя. Повтори загрузку.")}}
            catch(e: CancellationException){throw e}catch(e: Exception){
                active(g)
                val code=(e as? MusicFailure)?.code?:MusicError.RESPONSE
                _artists.update {
                    val retain = code !in setOf(MusicError.ACCESS, MusicError.ACCOUNT_CHANGED, MusicError.INVALID_ACCESS)
                    it.copy(details=it.details.takeIf{retain},queue=it.queue.takeIf{retain},error=code,errorDetails=ArtistFailureText.describe(e))
                }
            }
        }
    }
    fun openTracks(group: ArtistTracks) {
        val s=_artists.value
        if(s.owner==owner()&&s.album==null&&s.details?.tracks(group)?.isNotEmpty()==true)
            _artists.update{it.copy(queue=group)}
    }
    fun back() {
        val s=_artists.value
        when {
            s.queue!=null -> _artists.update{it.copy(queue=null)}
            s.album!=null -> selectAlbum(null)
            else -> select(null)
        }
    }
    fun selectAlbum(album: NativePlaylist?) {
        if(album==null){pause();_artists.update{it.copy(album=null,albumTracks=null,error=null,message=null)};return}
        if(blocked())return
        val s=_artists.value
        val details=s.details?:return
        if(s.owner!=owner()||s.stage=="artist_details"||(details.releases+details.appearances).none{it.playlist.key==album.key})return
        _artists.update{it.copy(album=album,albumTracks=null,error=null,message=null,queue=null)}
        run("artist_album") {g->
            try {
                val a=access()
                musicCheck(a.accountId==null||a.accountId==s.owner,MusicError.ACCOUNT_CHANGED)
                musicCheck(s.readSessionId==a.readSessionId,MusicError.ACCESS)
                val page=withTimeout(artistTimeoutMs){MusicPagination.tracks({offset->active(g);api.playlistPage(checked(a),album,offset)})}
                checked(a);active(g);_artists.update{it.copy(albumTracks=page)}
            }catch(_: TimeoutCancellationException){active(g);_artists.update{it.copy(error=MusicError.TIMEOUT,errorDetails=null,message="ВК не завершил запрос вовремя. Повтори загрузку.")}}
            catch(e: CancellationException){throw e}catch(e: Exception){active(g);_artists.update{it.copy(error=(e as? MusicFailure)?.code?:MusicError.RESPONSE,errorDetails=ArtistFailureText.describe(e))}}
        }
    }
}
