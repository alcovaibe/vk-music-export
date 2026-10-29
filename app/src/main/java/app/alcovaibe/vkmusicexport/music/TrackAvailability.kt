package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal enum class TrackAccessIssue(val label: String) {
    DENIED("ВК не разрешил получить эту запись для текущего доступа."),
    NOT_RETURNED("ВК не вернул запись по этому ID для текущего доступа."),
    RESTRICTED("В свежем ответе ВК запись отмечена ограниченной. Причина и регион не установлены."),
    NO_DURATION("ВК не вернул длительность доступной записи."),
    NO_STREAM("ВК вернул запись без доступной ссылки на аудио. Причина и регион не установлены.")
}
internal class TrackUnavailable(val issue: TrackAccessIssue,remoteCode: Int?=null): MusicFailure(MusicError.UNAVAILABLE,remoteCode)
internal class ResolvedAudio(val track: NativeTrack,val url: String) {
    override fun toString()="ResolvedAudio([metadata omitted])"
}
internal data class TrackAvailability(val track: NativeTrack,val linkAvailable: Boolean,val reason: String)
internal data class AvailabilityState(val visible: Boolean=false,val running: Boolean=false,val done: Int=0,val total: Int=0,
    val results: List<TrackAvailability> = emptyList(),val message: String?=null)

/** Exact IDs only. Obtaining a validated link is not a successful download or proof of a country's rights. */
internal class AvailabilityCheck(private val api: MusicApi,private val guard: suspend()->Unit={},private val pageDelayMs: Long=400) {
    suspend fun check(access: MusicAccess,extra: List<NativeTrack>,progress:(Int,Int,TrackAvailability?)->Unit): List<TrackAvailability> {
        guard();val proof=api.library(access)
        val library=MusicPagination.tracks({offset->guard();if(offset==0)proof.library else {
            delay(pageDelayMs);api.libraryPage(access,proof.owner,offset)
        }})
        val candidates=(extra+library.tracks.filter{it.restricted||it.duration<=0}).distinctBy{it.id}
        progress(0,candidates.size,null)
        val results=mutableListOf<TrackAvailability>()
        for((i,track)in candidates.withIndex()) {
            guard()
            val row=try {
                val resolved=api.resolve(access,track);guard()
                TrackAvailability(resolved.track,true,"ВК вернул проверенный адрес аудио. Файл и воспроизведение ещё не проверены.")
            }catch(e: CancellationException){throw e}
             catch(e: MusicFailure) {
                if(e.code in setOf(MusicError.ACCESS,MusicError.INVALID_ACCESS,MusicError.NETWORK,MusicError.TLS,MusicError.TIMEOUT,
                    MusicError.RATE_LIMIT,MusicError.CAPTCHA,MusicError.VALIDATION,MusicError.STORAGE))throw e
                TrackAvailability(track,false,(e as? TrackUnavailable)?.issue?.label?:e.code.label)
            }
            results.add(row);progress(i+1,candidates.size,row)
        }
        return results
    }
}
