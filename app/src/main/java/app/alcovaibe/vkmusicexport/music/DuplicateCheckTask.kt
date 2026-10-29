package app.alcovaibe.vkmusicexport.music

import android.content.Context
import android.util.AtomicFile
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

/** Durable scheduling intent, not credentials or deletion consent. Completed hashes use DuplicateScanCache. */
internal class DuplicateCheckTask(private val context: Context,private val api: MusicApi,
    private val credentials: MusicCredentialStore,private val sessions: MusicSessionManager?,
    val state: MutableStateFlow<DuplicatesState> = MutableStateFlow(DuplicatesState()),
    private val hasherFactory:(DuplicateScanCache,(NativeTrack,AudioProgress)->Unit)->DuplicateAudioHasher={cache,progress->
        DuplicateAudioHasher(context,api,checkpoint=cache,progress=progress)}) {
    @Volatile private var scheduledAccess: MusicAccess?=null
    private val file=AtomicFile(File(context.noBackupFilesDir,"duplicate-job.json"))
    private data class Request(val id: String,val credential: String,val phase: String)
    private fun read(): Request?=synchronized(lock) {
        if(!file.baseFile.exists()&&!File(file.baseFile.path+".bak").exists())return@synchronized null
        runCatching {
            val bytes=file.openRead().use{it.readNBytes(4097)};require(bytes.size<=4096)
            val o=Json.parseToJsonElement(strictUtf8(bytes)).jsonObject
            Request(o.getValue("id").jsonPrimitive.content,o.getValue("credential").jsonPrimitive.content,o.getValue("phase").jsonPrimitive.content)
                .also{require(it.id.length==36&&it.credential.length in 1..128&&it.phase in setOf("QUEUED","RUNNING","PAUSED","DONE"))}
        }.getOrNull()
    }
    private fun write(request: Request)=synchronized(lock) {
        val bytes=buildJsonObject{put("id",request.id);put("credential",request.credential);put("phase",request.phase)}.toString().toByteArray()
        val output=file.startWrite()
        try{output.write(bytes);file.finishWrite(output)}catch(e: Exception){file.failWrite(output);throw e}
    }
    private fun persist(id: String,phase: String): Boolean=synchronized(lock){
        val old=read()?:return@synchronized false
        if(old.id!=id||old.phase=="DONE"||phase=="DONE"&&old.phase!="RUNNING"||phase=="RUNNING"&&old.phase=="PAUSED")return@synchronized false
        write(old.copy(phase=phase));true
    }
    fun restore() {
        if(state.value.scanId.isNotEmpty())return
        val request=read()
        val saved=DuplicateScanCache(context).count()
        if(request!=null||saved>0)state.update{it.copy(scanId=request?.id?:"",savedChecks=saved,phase=DuplicatePhase.BLOCKED,
            message="Сохранённые проверки доступны. Продолжи поиск; старые согласия на удаление не восстанавливаются.")}
    }
    fun discard(){scheduledAccess=null;file.delete();DuplicateScanCache(context).clear();state.value=DuplicatesState()}
    suspend fun prepare(): String {
        musicCheck(!state.value.busy,MusicError.BUSY)
        val access=sessions?.current()?:credentials.read()?:throw MusicFailure(MusicError.ACCESS)
        val id=UUID.randomUUID().toString()
        scheduledAccess=access
        write(Request(id,access.credentialId,"QUEUED"))
        state.value=DuplicatesState(visible=true,phase=DuplicatePhase.QUEUED,scanId=id,savedChecks=DuplicateScanCache(context).count(),
            progress=DuplicateProgress("Ожидаем сеть и запуск Android"))
        return id
    }
    fun pause(id: String,message: String="Проверка приостановлена. Готовые проверки сохранены; продолжение доступно в приложении.") {
        if(!persist(id,"PAUSED"))return
        state.update{if(it.scanId==id)it.copy(phase=DuplicatePhase.BLOCKED,consent=null,message=message)else it}
    }
    suspend fun execute(id: String) {
        val request=read()?.takeIf{it.id==id&&it.phase in setOf("QUEUED","RUNNING")}?:return
        if(state.value.scanId.isNotEmpty()&&state.value.scanId!=id)return
        state.update{it.copy(scanId=id,phase=DuplicatePhase.SCANNING,report=null,consent=null,message=null)}
        val cache=DuplicateScanCache(context);var stage="SESSION";var reused=0
        fun update(block:(DuplicatesState)->DuplicatesState){state.update{if(it.scanId==id&&it.phase==DuplicatePhase.SCANNING)block(it)else it}}
        try {
            // Preserve the read lease in this process while Android waits for network. A new process
            // cannot prove the old renewal chain and must require explicit resume if the ID changed.
            val initial=scheduledAccess?.takeIf{it.credentialId==request.credential}
                ?:credentials.read()?.also{musicCheck(it.credentialId==request.credential,MusicError.ACCESS)}
                ?:throw MusicFailure(MusicError.ACCESS)
            val access=sessions?.continueRead(initial)?:initial
            suspend fun guard(){
                currentCoroutineContext().ensureActive()
                if(state.value.scanId!=id||state.value.phase!=DuplicatePhase.SCANNING)throw CancellationException()
                if(sessions!=null)sessions.continueRead(access)
                else musicCheck(credentials.read()?.credentialId==access.credentialId,MusicError.ACCESS)
            }
            guard();if(!persist(id,"RUNNING"))throw CancellationException()
            hasherFactory(cache){track,p->
                stage=when(p.stage){"resolve_media"->"RESOLVE_MEDIA";"payload_hash"->"AUDIO_HASH";else->"AUDIO_TRANSFER"}
                update{it.copy(progress=it.progress.copy(stage=when(p.stage){"resolve_media"->"Получаем ссылку ВК";"payload_hash"->"Считаем SHA-256";"verifying"->"Проверяем аудиоформат";else->"Загружаем временную копию"},track=track,bytes=p.bytes))}
            }.use{hasher->
                val report=MusicDuplicates(api,hasher,guard=::guard,progress={p->
                    stage=if(p.track==null)"LIBRARY_READ" else "AUDIO_HASH";reused=maxOf(reused,p.reused)
                    update{it.copy(progress=p.copy(reused=reused),savedChecks=cache.count())}
                }).scan(access)
                guard()
                val current=sessions?.continueRead(access)?:access
                if(!persist(id,"DONE"))throw CancellationException()
                update{it.copy(phase=DuplicatePhase.READY,report=report.copy(credentialId=current.credentialId),savedChecks=cache.count(),
                    message=if(report.libraryChanged)"Поиск завершён. Библиотека изменилась: новые и изменённые записи можно проверить отдельным поиском. Удаление предлагается только для неизменившихся записей."
                        else "Проверка завершена. Для удаления каждого дубликата требуется отдельное подтверждение в приложении.")}
                Diagnostics.event("audio_duplicates_scan",mapOf("scan_id" to id,"tracks" to report.tracks,"checked" to report.checked,
                    "duplicates" to report.duplicates.size,"problems" to report.problems.size,"reused" to reused,"background" to true))
            }
        }catch(e: CancellationException){withContext(NonCancellable){pause(id)};throw e}
         catch(e: Exception) {
            val failure=e as? MusicFailure;val code=failure?.code?:MusicError.STORAGE
            pause(id,"Проверка приостановлена: "+(if(code==MusicError.ACCESS)"Не удалось подтвердить доступ ВК. Проверь вход." else code.label)+" Готовые проверки сохранены.")
            Diagnostics.event("audio_duplicates_failure",mapOf("scan_id" to id,"code" to code.name,"vk_code" to failure?.remoteCode,
                "stage" to stage,"saved_checks" to cache.count(),"reused" to reused,"background" to true,"mutation_started" to false))
        }finally{state.update{if(it.scanId==id)it.copy(savedChecks=cache.count())else it}}
    }
    private companion object {val lock=Any()}
}
