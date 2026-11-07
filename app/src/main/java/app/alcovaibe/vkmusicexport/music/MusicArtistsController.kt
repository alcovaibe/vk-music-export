package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class MusicArtistsState(val owner: Long=0,val loaded: Boolean=false,val artists: List<MusicArtist> = emptyList(),
    val selected: MusicArtist?=null,val details: MusicArtistDetails?=null,val error: MusicError?=null,
    val album: NativePlaylist?=null,val albumTracks: NativePage?=null,val requests: Int=0,val message: String?=null,val errorDetails: String?=null,val busy: Boolean=false,val stage: String="",val progress: String?=null)

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
        if(!refresh&&_artists.value.owner==owner&&_artists.value.loaded) {pause();_artists.update{it.copy(selected=null,details=null,error=null,message=null,album=null,albumTracks=null)};return}
        _artists.value=MusicArtistsState(owner=owner)
        run("artists") {g->
            try {
                val a=access()
                musicCheck(a.accountId==null||a.accountId==owner,MusicError.ACCOUNT_CHANGED)
                val rows=withTimeout(artistTimeoutMs){api.followedArtists(a,owner){partial,requests->
                    if(g==generation) {
                        _artists.update{it.copy(artists=partial.distinctBy{artist->artist.id},requests=requests)}
                        _artists.update{it.copy(progress="Подписки: ${partial.size} · запросов: $requests")}
                    }
                }};active(g)
                checked(a);active(g)
                _artists.value=MusicArtistsState(owner,true,rows.distinctBy{it.id},busy=true,stage="artists")
            }catch(_: TimeoutCancellationException){active(g);_artists.update{it.copy(error=MusicError.TIMEOUT,errorDetails=null,message="ВК не завершил загрузку подписок за отведённое время. Уже полученные исполнители доступны; обнови список для повтора.")}}
            catch(e: CancellationException){if(g==generation)_artists.update{it.copy(message="Загрузка подписок приостановлена. Уже полученные исполнители доступны.")};throw e}
            catch(e: Exception){active(g);report(e);_artists.update{it.copy(error=(e as? MusicFailure)?.code?:MusicError.RESPONSE,errorDetails=ArtistFailureText.describe(e))}}
        }
    }
    fun select(artist: MusicArtist?) {
        if(blocked())return
        if(artist==null){pause();_artists.update{it.copy(selected=null,details=null,error=null,message=null,album=null,albumTracks=null)};return}
        val s=_artists.value
        if(s.owner!=owner()||s.artists.none{it.id==artist.id})return
        _artists.update{it.copy(selected=artist,details=null,error=null,message=null,album=null,albumTracks=null)}
        run("artist_details") {g->
            try {
                val a=access()
                musicCheck(a.accountId==null||a.accountId==s.owner,MusicError.ACCOUNT_CHANGED)
                val details=withTimeout(artistTimeoutMs){api.artistDetails(a,artist)};active(g)
                checked(a);active(g)
                _artists.update{it.copy(details=details)}
            }catch(_: TimeoutCancellationException){active(g);_artists.update{it.copy(error=MusicError.TIMEOUT,errorDetails=null,message="ВК не завершил запрос вовремя. Повтори загрузку.")}}
            catch(e: CancellationException){throw e}catch(e: Exception){active(g);_artists.update{it.copy(error=(e as? MusicFailure)?.code?:MusicError.RESPONSE,errorDetails=ArtistFailureText.describe(e))}}
        }
    }
    fun back() {
        pause()
        _artists.update{if(it.album!=null)it.copy(album=null,albumTracks=null,error=null,message=null)else it.copy(selected=null,details=null,error=null,message=null,album=null,albumTracks=null)}
    }
    fun selectAlbum(album: NativePlaylist?) {
        if(blocked())return
        if(album==null){pause();_artists.update{it.copy(album=null,albumTracks=null,error=null,message=null)};return}
        val s=_artists.value
        if(s.owner!=owner()||s.details?.albums?.none{it.key==album.key}!=false)return
        _artists.update{it.copy(album=album,albumTracks=null,error=null,message=null)}
        run("artist_album") {g->
            try {
                val a=access()
                musicCheck(a.accountId==null||a.accountId==s.owner,MusicError.ACCOUNT_CHANGED)
                val page=withTimeout(artistTimeoutMs){MusicPagination.tracks({offset->active(g);api.playlistPage(checked(a),album,offset)})}
                checked(a);active(g);_artists.update{it.copy(albumTracks=page)}
            }catch(_: TimeoutCancellationException){active(g);_artists.update{it.copy(error=MusicError.TIMEOUT,errorDetails=null,message="ВК не завершил запрос вовремя. Повтори загрузку.")}}
            catch(e: CancellationException){throw e}catch(e: Exception){active(g);_artists.update{it.copy(error=(e as? MusicFailure)?.code?:MusicError.RESPONSE,errorDetails=ArtistFailureText.describe(e))}}
        }
    }
}
