package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class MusicArtistsState(val owner: Long=0,val loaded: Boolean=false,val artists: List<MusicArtist> = emptyList(),
    val selected: MusicArtist?=null,val details: MusicArtistDetails?=null,val error: MusicError?=null,
    val album: NativePlaylist?=null,val albumTracks: NativePage?=null,val requests: Int=0,val message: String?=null,val errorDetails: String?=null,val busy: Boolean=false,val stage: String="",val progress: String?=null,
    val readSessionId: String?=null,val queue: ArtistTracks?=null)

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
) {
    private val _artists=MutableStateFlow(MusicArtistsState())
    val state=_artists.asStateFlow()
    private var work: Job?=null
    private var generation=0
    fun reset(){pause();_artists.value=MusicArtistsState()}
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
    fun show(refresh: Boolean=false) {
        val owner=owner()?:return
        if(blocked())return
        if(!refresh&&_artists.value.owner==owner&&_artists.value.loaded) {select(null);return}
        _artists.value=MusicArtistsState(owner=owner)
        run("artists") {g->
            try {
                val a=access()
                musicCheck(a.accountId==null||a.accountId==owner,MusicError.ACCOUNT_CHANGED)
                checked(a);active(g)
                _artists.update{it.copy(readSessionId=a.readSessionId)}
                val rows=withTimeout(artistTimeoutMs){api.followedArtists(a,owner){partial,requests->
                    if(g==generation) {
                        _artists.update{it.copy(artists=partial.distinctBy{artist->artist.id},requests=requests)}
                        _artists.update{it.copy(progress="Подписки: ${partial.size} · запросов: $requests")}
                    }
                }};active(g)
                checked(a);active(g)
                _artists.value=MusicArtistsState(owner,true,rows.distinctBy{it.id},busy=true,stage="artists",readSessionId=a.readSessionId)
            }catch(_: TimeoutCancellationException){active(g);_artists.update{it.copy(error=MusicError.TIMEOUT,errorDetails=null,message="ВК не завершил загрузку подписок за отведённое время. Уже полученные исполнители доступны; обнови список для повтора.")}}
            catch(e: CancellationException){if(g==generation)_artists.update{it.copy(message="Загрузка подписок приостановлена. Уже полученные исполнители доступны.")};throw e}
            catch(e: Exception){active(g);report(e);_artists.update{it.copy(error=(e as? MusicFailure)?.code?:MusicError.RESPONSE,errorDetails=ArtistFailureText.describe(e))}}
        }
    }
    fun select(artist: MusicArtist?) {
        if(artist==null){pause();_artists.update{it.copy(selected=null,details=null,error=null,message=null,album=null,albumTracks=null,queue=null)};return}
        if(blocked())return
        val s=_artists.value
        if(s.owner!=owner()||s.artists.none{it.id==artist.id})return
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
