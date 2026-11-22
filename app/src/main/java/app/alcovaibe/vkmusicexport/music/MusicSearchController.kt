package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal object MusicSearchQuery {
    const val PAGE_SIZE=100
    const val MAX_RESULTS=20_000
    fun text(raw: String): String = raw.trim().also {
        musicCheck(it.length in 2..300&&it.none{char->Character.isISOControl(char)},MusicError.RESPONSE)
    }
}

internal enum class MusicSearchPhase { READY, LOADING, RESULTS, EMPTY, ERROR, DENIED, CHALLENGE, UNAVAILABLE }
internal data class MusicSearchState(val query: String="",val phase: MusicSearchPhase=MusicSearchPhase.READY,
    val tracks: List<NativeTrack> = emptyList(),val total: Int=0,val nextOffset: Int=0,
    val account: Long=0,val readSessionId: String?=null,val active: Boolean=false,
    val error: MusicError?=null,val capability: CapabilityStatus=CapabilityStatus.UNAVAILABLE,
    val audio: CapabilityStatus=CapabilityStatus.UNAVAILABLE) {
    fun matchesArtist(track: NativeTrack) = track.artist.trim().equals(query.trim(), true) ||
        track.creditedArtists.any { it.name.equals(query.trim(), true) }
    val artistTrackCount by lazy { tracks.takeWhile(::matchesArtist).size }
    val creditedArtist by lazy { tracks.asSequence().take(artistTrackCount).flatMap { it.creditedArtists.asSequence() }
        .firstOrNull { it.name.equals(query.trim(), true) } }
    val albums by lazy { tracks.take(artistTrackCount).mapNotNull { it.album }.distinctBy { it.id } }
    val canLoadMore get()=phase==MusicSearchPhase.RESULTS&&nextOffset<total&&nextOffset<MusicSearchQuery.MAX_RESULTS
}
/** User search owns its own cancellation/generation; it never takes the file mutation gate. */
internal class MusicSearchController(private val scope: CoroutineScope,private val api: MusicApi,
    private val access: suspend()->MusicAccess,private val checked: suspend(MusicAccess)->MusicAccess,
    private val debounceMs: Long=350) {
    private val mutable=MutableStateFlow(MusicSearchState(capability=api.capabilities.search,audio=api.capabilities.directAudioStream))
    val state=mutable.asStateFlow()
    private var work: Job?=null
    private var generation=0
    fun reset(){pause();mutable.value=MusicSearchState(capability=api.capabilities.search,audio=api.capabilities.directAudioStream)}
    fun pause(){generation++;work?.cancel();work=null;mutable.update{it.copy(active=false)}}
    fun setActive(active: Boolean) {
        if(active==mutable.value.active)return
        if(!active){pause();return}
        mutable.update{it.copy(active=true)}
        val snapshot = mutable.value
        if (snapshot.query.trim().length >= 2 && snapshot.phase !in setOf(MusicSearchPhase.RESULTS, MusicSearchPhase.EMPTY))
            search(append = snapshot.tracks.isNotEmpty())
    }
    fun query(value: String) {
        val query=value.take(300)
        if(query==mutable.value.query)return
        generation++;work?.cancel();work=null
        mutable.value=MusicSearchState(query=query,active=mutable.value.active,capability=api.capabilities.search,audio=api.capabilities.directAudioStream)
        if(query.trim().length>=2&&mutable.value.active)search(append=false)
    }
    fun retry(){if(mutable.value.query.trim().length>=2)search(append=false)}
    fun more(){if(mutable.value.canLoadMore)search(append=true)}
    private suspend fun active(g: Int) {
        currentCoroutineContext().ensureActive()
        if(g!=generation||!mutable.value.active)throw CancellationException("Search invalidated")
    }
    private fun search(append: Boolean) {
        val before=mutable.value
        if(!before.active)return
        if(api.capabilities.search==CapabilityStatus.UNAVAILABLE) {
            mutable.update{it.copy(phase=MusicSearchPhase.UNAVAILABLE,tracks=emptyList())};return
        }
        work?.cancel();val g=++generation
        val offset=if(append)before.nextOffset else 0
        mutable.update{it.copy(phase=MusicSearchPhase.LOADING,error=null,
            tracks=if(append)it.tracks else emptyList())}
        work=scope.launch {
            try {
                if(!append)delay(debounceMs)
                active(g)
                val initial=access();active(g)
                val current=checked(initial);active(g)
                val owner=current.accountId?:api.accountOwner(current)
                musicCheck(owner>0,MusicError.ACCOUNT_CHANGED)
                if (append) musicCheck(initial.readSessionId == before.readSessionId && owner == before.account, MusicError.ACCOUNT_CHANGED)
                val result=api.searchMusic(current,MusicSearchQuery.text(before.query),offset)
                checked(initial);active(g)
                musicCheck(result.offset==offset&&result.tracks.size<=MusicSearchQuery.PAGE_SIZE&&
                    result.count>=offset+result.tracks.size&&
                    (result.tracks.isNotEmpty()||result.count<=offset),MusicError.INCOMPLETE)
                val tracks=if(append)before.tracks+result.tracks else result.tracks
                val (matching, others) = tracks.partition(before::matchesArtist)
                mutable.value=MusicSearchState(before.query,
                    if(tracks.isEmpty())MusicSearchPhase.EMPTY else MusicSearchPhase.RESULTS,
                    matching + others,result.count,offset+result.tracks.size,owner,initial.readSessionId,true,
                    capability=api.capabilities.search,audio=api.capabilities.directAudioStream)
            }catch(e: CancellationException){throw e}
             catch(e: Exception) {
                active(g)
                val error=(e as? MusicFailure)?.code?:MusicError.RESPONSE
                mutable.update{it.copy(phase=when(error) {
                    MusicError.DENIED,MusicError.ACCESS,MusicError.INVALID_ACCESS,MusicError.ACCOUNT_CHANGED->MusicSearchPhase.DENIED
                    MusicError.CAPTCHA,MusicError.VALIDATION->MusicSearchPhase.CHALLENGE
                    MusicError.METHOD->MusicSearchPhase.UNAVAILABLE
                    else->MusicSearchPhase.ERROR
                },error=error)}
            }finally {if(g==generation)work=null}
        }
    }
    fun isCurrent(snapshot: MusicSearchState): Boolean =
        snapshot == mutable.value && snapshot.active && snapshot.phase == MusicSearchPhase.RESULTS &&
            snapshot.account > 0 && snapshot.readSessionId != null

    fun entries(snapshot: MusicSearchState): List<PlaybackEntry> {
        if (!isCurrent(snapshot) || api.capabilities.directAudioStream == CapabilityStatus.UNAVAILABLE)
            return emptyList()
        val occurrences=mutableMapOf<String,Int>()
        return snapshot.tracks.mapIndexed { index, track ->
            val occurrence=(occurrences[track.id]?:0)+1;occurrences[track.id]=occurrence
            PlaybackEntry(track,"Поиск ВК",snapshot.account,
                export=directTrackExport(snapshot.account, track).copy(position=index+1, occurrence=occurrence),
                readSessionId=snapshot.readSessionId)
        }
    }
}
