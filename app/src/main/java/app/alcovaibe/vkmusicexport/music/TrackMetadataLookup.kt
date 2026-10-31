package app.alcovaibe.vkmusicexport.music

import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal data class MetadataMatches(val tracks: List<NativeTrack>,val count: Int,val offset: Int=0,val byLink: Boolean=false)
@Serializable internal data class TrackMetadataReplacement(val owner: Long,val trackId: String,val uri: String,val referenceId: String)
internal class MetadataChoice(val target: PublishedAudio,val selected: NativeTrack,val readSessionId: String,val expiresAt: Long) {
    val id=UUID.randomUUID().toString()
    private val used=AtomicBoolean()
    fun claim()=used.compareAndSet(false,true)
}
internal class MetadataChoiceExpired: MusicFailure(MusicError.INCOMPLETE)
internal class MetadataCandidateChanged: MusicFailure(MusicError.INCOMPLETE)
internal object MetadataReplacementText {
    /** A human chose text from another record. Never rebind the binary to that record or copy its artist/album/duration. */
    fun merge(saved: NativeTrack,selected: NativeTrack)=saved.copy(title=selected.title,subtitle=selected.subtitle)
}
internal class TrackMetadataLookup(private val api: MusicApi,private val now:()->Long={System.nanoTime()/1_000_000},
    private val guard:suspend()->Unit={}) {
    suspend fun find(access: MusicAccess,input: String,offset: Int=0): MetadataMatches {
        val query=ArtworkQuery.text(input);guard()
        val result=if(ArtworkQuery.isLink(query)) {
            musicCheck(offset==0,MusicError.RESPONSE)
            val id=ArtworkQuery.reference(query)
            val track=api.metadata(access,id);musicCheck(track==null||track.id==id,MusicError.RESPONSE)
            MetadataMatches(listOfNotNull(track),if(track==null)0 else 1,byLink=true)
        }else {
            musicCheck(offset>=0&&offset<ArtworkQuery.MAX_RESULTS&&offset%ArtworkQuery.PAGE_SIZE==0,MusicError.RESPONSE)
            api.searchMetadata(access,query,offset)
        }
        musicCheck(result.tracks.size<=ArtworkQuery.PAGE_SIZE&&result.count>=result.tracks.size&&result.offset==offset,MusicError.RESPONSE)
        guard();return result
    }
    suspend fun preview(access: MusicAccess,target: PublishedAudio,selected: NativeTrack): MetadataChoice {
        guard()
        val fresh=api.metadata(access,selected.id)?:throw MusicFailure(MusicError.UNAVAILABLE)
        musicCheck(fresh.id==selected.id,MusicError.RESPONSE);guard()
        return MetadataChoice(target,fresh,access.readSessionId,now()+5*60_000)
    }
    suspend fun apply(access: MusicAccess,choice: MetadataChoice,write:suspend(PublishedAudio,NativeTrack,String)->Boolean): Boolean {
        musicCheck(choice.claim(),MusicError.BUSY)
        musicCheck(access.readSessionId==choice.readSessionId,MusicError.ACCESS)
        if(now()>=choice.expiresAt)throw MetadataChoiceExpired()
        guard()
        val fresh=api.metadata(access,choice.selected.id)?:throw MusicFailure(MusicError.UNAVAILABLE)
        if(fresh.id!=choice.selected.id||fresh.title!=choice.selected.title||fresh.subtitle!=choice.selected.subtitle||
            fresh.artist!=choice.selected.artist||fresh.duration!=choice.selected.duration)throw MetadataCandidateChanged()
        guard()
        return write(choice.target,MetadataReplacementText.merge(choice.target.track,fresh),fresh.id)
    }
}
internal data class MetadataLookupState(val visible: Boolean=false,val id: String="",val phase: LookupPhase=LookupPhase.READY,
    val target: PublishedAudio?=null,val query: String="",val results: MetadataMatches?=null,
    val owner: Long=0,val readSessionId: String="",val resultsAt: Long=0,val choice: MetadataChoice?=null,val message: String?=null) {
    val busy get()=phase in setOf(LookupPhase.OPENING,LookupPhase.SEARCHING,LookupPhase.PREVIEWING,LookupPhase.APPLYING)
}
