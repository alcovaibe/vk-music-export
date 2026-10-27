package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Search metadata/URLs stay in memory and never replace the saved track identity. */
internal class ArtworkMatch(val track: NativeTrack,val candidates: List<ArtworkCandidate>,val coverError: MusicError?=null) {
    val selectable get()=!track.restricted&&track.duration>0&&candidates.isNotEmpty()&&coverError==null
    override fun toString()="ArtworkMatch([metadata omitted])"
}
internal data class ArtworkMatches(val matches: List<ArtworkMatch>,val count: Int,val offset: Int=0,val byLink: Boolean=false)
internal enum class LookupReason(val label: String) {
    INPUT("Введи исполнителя и название: от 1 до 500 символов без переносов строк."),
    LINK("Нужна HTTPS-ссылка на отдельную песню: vk.com/audioВЛАДЕЛЕЦ_ID или vk.com/music?z=audioВЛАДЕЛЕЦ_ID. Короткие ссылки и ссылки на подборки не поддерживаются."),
    EXPIRED("Выбор устарел. Выполни поиск и посмотри обложку заново."),
    NO_COVER("У выбранной записи ВК не передал доступную обложку.")
}
internal class ArtworkLookupFailure(val reason: LookupReason): MusicFailure(when(reason){LookupReason.INPUT->MusicError.RESPONSE;LookupReason.LINK->MusicError.MEDIA_URL;LookupReason.EXPIRED->MusicError.INCOMPLETE;LookupReason.NO_COVER->MusicError.UNAVAILABLE})
internal object ArtworkQuery {
    const val PAGE_SIZE=20;const val MAX_RESULTS=100
    fun text(raw: String): String {
        val text=raw.trim()
        if(text.length !in 1..500||text.any{Character.isISOControl(it)})throw ArtworkLookupFailure(LookupReason.INPUT)
        return text
    }
    fun isLink(text: String)=text.contains("://")||text.startsWith("https:",true)||text.startsWith("http:",true)||
        Regex("(?i)^(?:(?:m|music)\\.)?vk\\.(?:com|ru|cc)/").containsMatchIn(text)
    fun reference(raw: String): String {
        val text=raw.trim();val full=if(text.startsWith("https://",true))text else "https://$text"
        val url=full.toHttpUrlOrNull()?:throw ArtworkLookupFailure(LookupReason.LINK)
        if(url.scheme!="https"||url.port!=443||url.username.isNotEmpty()||url.password.isNotEmpty()||url.fragment!=null||
            url.host !in setOf("vk.com","m.vk.com","music.vk.com","vk.ru","m.vk.ru","music.vk.ru"))throw ArtworkLookupFailure(LookupReason.LINK)
        val audio=when(url.encodedPath) {"/music","/music/"->url.queryParameter("z");else->url.pathSegments.singleOrNull()}
        val match=audio?.let{Regex("audio(-?[1-9][0-9]*)_([1-9][0-9]*)").matchEntire(it)}?:throw ArtworkLookupFailure(LookupReason.LINK)
        if(match.groupValues.drop(1).any{it.toLongOrNull()==null})throw ArtworkLookupFailure(LookupReason.LINK)
        return "${match.groupValues[1]}_${match.groupValues[2]}"
    }
}
internal class ArtworkChoice(val target: PublishedAudio,val credentialId: String,val match: ArtworkMatch,val image: CoverImage,val expiresAt: Long) {
    val id=UUID.randomUUID().toString()
    private val claimed=AtomicBoolean()
    fun claim()=claimed.compareAndSet(false,true)
}
/** No audio media/collection mutation methods are used in this path. */
internal class ArtworkLookup(private val api: MusicApi,private val source:(ArtworkMatch)->ArtworkSource,
    private val clock:()->Long={System.nanoTime()/1_000_000},private val guard:suspend()->Unit={}) {
    suspend fun find(access: MusicAccess,input: String,offset: Int=0): ArtworkMatches {
        val query=ArtworkQuery.text(input);guard();currentCoroutineContext().ensureActive()
        val result=if(ArtworkQuery.isLink(query)) {
            musicCheck(offset==0,MusicError.RESPONSE)
            val reference=ArtworkQuery.reference(query)
            val match=api.artworkRecord(access,reference)
            musicCheck(match==null||match.track.id==reference,MusicError.RESPONSE)
            ArtworkMatches(listOfNotNull(match),if(match==null)0 else 1,byLink=true)
        }else {
            musicCheck(offset>=0&&offset<ArtworkQuery.MAX_RESULTS&&offset%ArtworkQuery.PAGE_SIZE==0,MusicError.RESPONSE)
            api.searchArtwork(access,query,offset)
        }
        musicCheck(result.matches.size<=ArtworkQuery.PAGE_SIZE&&result.count>=result.matches.size&&result.offset==offset,MusicError.RESPONSE)
        guard();currentCoroutineContext().ensureActive();return result
    }
    suspend fun preview(access: MusicAccess,target: PublishedAudio,match: ArtworkMatch): ArtworkChoice {
        musicCheck(!target.coverEmbedded,MusicError.INCOMPLETE)
        if(!match.selectable)throw ArtworkLookupFailure(LookupReason.NO_COVER)
        guard();currentCoroutineContext().ensureActive()
        val image=source(match).load(access,match.track)?:throw ArtworkLookupFailure(LookupReason.NO_COVER)
        guard();currentCoroutineContext().ensureActive()
        return ArtworkChoice(target,access.credentialId,match,image,clock()+5*60*1000)
    }
    suspend fun apply(access: MusicAccess,choice: ArtworkChoice,write:suspend(PublishedAudio,CoverImage)->Boolean): Boolean {
        musicCheck(choice.claim(),MusicError.BUSY)
        musicCheck(access.credentialId==choice.credentialId,MusicError.ACCESS)
        if(clock()>=choice.expiresAt)throw ArtworkLookupFailure(LookupReason.EXPIRED)
        guard();currentCoroutineContext().ensureActive()
        // The writer receives the old saved record, never the candidate's track/ID.
        return write(choice.target,choice.image)
    }
}
internal enum class LookupPhase { OPENING,READY,SEARCHING,PREVIEWING,CONFIRM,APPLYING,DONE }
internal data class ArtworkLookupState(val visible: Boolean=false,val id: String="",val phase: LookupPhase=LookupPhase.READY,
    val target: PublishedAudio?=null,val query: String="",val results: ArtworkMatches?=null,val resultsAt: Long=0,
    val credentialId: String="",val choice: ArtworkChoice?=null,val message: String?=null) {
    val busy get()=phase in setOf(LookupPhase.OPENING,LookupPhase.SEARCHING,LookupPhase.PREVIEWING,LookupPhase.APPLYING)
}
