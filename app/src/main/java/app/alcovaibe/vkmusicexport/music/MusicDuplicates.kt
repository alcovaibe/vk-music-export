package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal data class DuplicateSignature(val codec: String,val sha256: String,val version: Int=2)
internal fun interface DuplicateHasher {
    suspend fun signature(access: MusicAccess,track: NativeTrack): DuplicateSignature
    fun beginScan(owner: Long,tracks: List<NativeTrack>) {}
    fun cached(track: NativeTrack): DuplicateSignature?=null
}
internal enum class DuplicateStatus(val label: String) { PENDING("Ожидает решения"),DELETED("Удалён из ВК"),SKIPPED("Пропущен"),UNKNOWN("Результат не подтверждён") }
internal data class MusicDuplicate(val keep: NativeTrack,val remove: NativeTrack,val signature: DuplicateSignature,val status: DuplicateStatus=DuplicateStatus.PENDING)
internal data class DuplicateProblem(val track: NativeTrack,val reason: String,val error: MusicError?=null)
internal data class DuplicateReport(val owner: Long,val credentialId: String,val tracks: Int,val checked: Int,
    val duplicates: List<MusicDuplicate>,val problems: List<DuplicateProblem>,val libraryChanged: Boolean=false)
internal data class DuplicateProgress(val stage: String,val done: Int=0,val total: Int=0,val track: NativeTrack?=null,val bytes: Long=0,val reused: Int=0)

internal object DuplicateNames {
    private fun normalize(value: String)=Normalizer.normalize(value,Normalizer.Form.NFKC)
        .replace(Regex("[\\p{Z}\\s]+")," ").trim().lowercase(Locale.ROOT)
    fun key(track: NativeTrack)=normalize(track.artist) to normalize(track.title)
}
/** A single explicit consent can authorize at most one request, including failed/ambiguous results. */
internal class DuplicateConsent(val owner: Long,val credentialId: String,val duplicate: MusicDuplicate) {
    val id: String=UUID.randomUUID().toString()
    private val claimed=AtomicBoolean()
    fun claim()=claimed.compareAndSet(false,true)
}

/** Discover first, mutate later. Deletion never uses positions/offsets from the old snapshot. */
internal class MusicDuplicates(private val api: MusicApi,private val hasher: DuplicateHasher,
    private val guard: suspend()->Unit={},private val progress:(DuplicateProgress)->Unit={},private val pageDelayMs: Long=400) {
    private suspend fun read(access: MusicAccess,owner: Long?=null,stage: String): Pair<Long,List<NativeTrack>> {
        progress(DuplicateProgress(stage));guard();val proof=api.library(access)
        musicCheck(proof.owner>0&&(owner==null||proof.owner==owner),MusicError.ACCESS)
        val page=MusicPagination.tracks({offset->
            guard();if(offset==0)proof.library else {delay(pageDelayMs);guard();api.libraryPage(access,proof.owner,offset)}
        }){done,total->progress(DuplicateProgress(stage,done,total))}
        return proof.owner to page.tracks
    }
    private fun own(owner: Long,track: NativeTrack)=runCatching{LibraryRemovalProtocol.audioId(owner,track)}.isSuccess
    private fun checked(signature: DuplicateSignature): DuplicateSignature {
        musicCheck(signature.version==2&&signature.codec in setOf("mp3","m4a")&&signature.sha256.matches(Regex("[0-9a-f]{64}")),MusicError.FORMAT)
        return signature
    }
    suspend fun scan(access: MusicAccess): DuplicateReport {
        val (owner,tracks)=read(access,stage="Читаем мою музыку")
        guard();hasher.beginScan(owner,tracks)
        val problems=mutableListOf<DuplicateProblem>()
        val byId=tracks.groupBy{it.id}
        val repeated=byId.filterValues{it.size>1}.keys
        repeated.forEach{id->problems.add(DuplicateProblem(byId.getValue(id).first(),"Один ID ВК встречается несколько раз. Удаление по ID не позволяет гарантировать сохранение одной позиции."))}
        val unique=tracks.distinctBy{it.id}
        val groups=unique.groupBy(DuplicateNames::key).values.filter{it.size>1}
        val candidates=groups.flatten()
        val signatures=mutableMapOf<String,DuplicateSignature>();var checkedCount=0;var reused=0
        for((index,track)in candidates.withIndex()) {
            guard();progress(DuplicateProgress("Проверяем SHA-256 аудиоданных",index,candidates.size,track,reused=reused))
            if(track.id in repeated)continue
            if(!own(owner,track)){problems.add(DuplicateProblem(track,"Эта запись не поддерживает безопасное удаление из своего профиля по текущему протоколу."));continue}
            try {
                val cached=hasher.cached(track)
                signatures[track.id]=checked(cached?:hasher.signature(access,track));checkedCount++
                if(cached!=null)reused++
                progress(DuplicateProgress("Проверяем SHA-256 аудиоданных",index+1,candidates.size,track,reused=reused))
            }
            catch(e: kotlinx.coroutines.CancellationException){throw e}
            catch(e: MusicFailure){
                if(e.code in setOf(MusicError.ACCESS,MusicError.INVALID_ACCESS,MusicError.CAPTCHA,MusicError.VALIDATION,MusicError.RATE_LIMIT,MusicError.NETWORK,MusicError.TLS,MusicError.TIMEOUT,MusicError.STORAGE))throw e
                problems.add(DuplicateProblem(track,(e as? TrackUnavailable)?.issue?.label?:"SHA-256 не подтверждён: ${e.code.label}",e.code))
            }
        }
        guard();val (_,after)=read(access,owner,"Проверяем, что список не изменился")
        // Discovery is one finite pass. A moving library must not restart hashing the entire list.
        // Offer only records still present exactly once with the same metadata; new/changed records
        // can be checked by an explicit next search. Removal retains its stricter fresh checks.
        val current=after.groupBy{it.id}
        val stable=tracks.filter{current[it.id]==listOf(it)}.map{it.id}.toHashSet()
        signatures.keys.filter{it !in stable}.forEach{id->
            problems.add(DuplicateProblem(byId.getValue(id).first(),"Запись изменилась или исчезла во время поиска. Удаление не предлагается.",MusicError.LIBRARY_CHANGED))
        }
        signatures.keys.retainAll(stable)
        val libraryChanged=after.groupingBy{it}.eachCount()!=tracks.groupingBy{it}.eachCount()
        val duplicates=mutableListOf<MusicDuplicate>()
        for(group in groups) {
            val verified=group.filter{it.id in signatures}.groupBy{signatures.getValue(it.id)}
            verified.values.filter{it.size>1}.forEach{equal->
                val keep=equal.first();equal.drop(1).forEach{duplicates.add(MusicDuplicate(keep,it,signatures.getValue(keep.id)))}
            }
            group.filter{it.id in signatures&&verified.getValue(signatures.getValue(it.id)).size==1}.forEach{
                problems.add(DuplicateProblem(it,"Название и исполнитель совпали, но SHA-256 аудиоданных отличается. Удаление не предлагается."))
            }
        }
        progress(DuplicateProgress("Поиск завершён",candidates.size,candidates.size,reused=reused))
        return DuplicateReport(owner,access.credentialId,tracks.size,checkedCount,duplicates,problems,libraryChanged)
    }
    suspend fun remove(access: MusicAccess,consent: DuplicateConsent,beforeMutation: suspend()->Unit={}) {
        musicCheck(consent.claim(),MusicError.BUSY)
        val row=consent.duplicate
        musicCheck(access.credentialId==consent.credentialId&&row.status==DuplicateStatus.PENDING,MusicError.ACCESS)
        LibraryRemovalProtocol.audioId(consent.owner,row.keep);LibraryRemovalProtocol.audioId(consent.owner,row.remove)
        musicCheck(row.keep.id!=row.remove.id&&DuplicateNames.key(row.keep)==DuplicateNames.key(row.remove),MusicError.INCOMPLETE)
        val (_,before)=read(access,consent.owner,"Проверяем обе записи в ВК")
        musicCheck(before.count{it.id==row.keep.id}==1&&before.count{it.id==row.remove.id}==1&&before.contains(row.keep)&&before.contains(row.remove),MusicError.LIBRARY_CHANGED)
        for((index,track)in listOf(row.keep,row.remove).withIndex()) {
            guard();progress(DuplicateProgress("Повторно сверяем SHA-256 перед удалением",index,2,track))
            musicCheck(checked(hasher.signature(access,track))==checked(row.signature),MusicError.INCOMPLETE)
        }
        val (_,after)=read(access,consent.owner,"Повторно проверяем библиотеку")
        musicCheck(after==before,MusicError.LIBRARY_CHANGED)
        guard();currentCoroutineContext().ensureActive();beforeMutation()
        // The existing client checks users.get with this token and sends one non-retrying POST audio.delete.
        api.removeLibraryTrack(access,consent.owner,row.remove)
    }
}

internal enum class DuplicatePhase { INTRO,QUEUED,SCANNING,READY,DELETING,BLOCKED }
internal data class DuplicatesState(val visible: Boolean=false,val phase: DuplicatePhase=DuplicatePhase.INTRO,val scanId: String="",
    val report: DuplicateReport?=null,val progress: DuplicateProgress=DuplicateProgress(""),val consent: DuplicateConsent?=null,val message: String?=null,
    val savedChecks: Int=0) {
    val busy get()=phase in setOf(DuplicatePhase.QUEUED,DuplicatePhase.SCANNING,DuplicatePhase.DELETING)
    val found get()=report?.duplicates?.size?:0
    val removed get()=report?.duplicates?.count{it.status==DuplicateStatus.DELETED}?:0
    val skipped get()=report?.duplicates?.count{it.status==DuplicateStatus.SKIPPED}?:0
    val remaining get()=report?.duplicates?.count{it.status==DuplicateStatus.PENDING}?:0
}
