package app.alcovaibe.vkmusicexport.music

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable internal enum class MaintenancePhase { IDLE,QUEUED,RUNNING,STOPPING,PAUSED,COMPLETED }
@Serializable internal data class MaintenanceStatus(val stage: String="",val id: String="",val phase: MaintenancePhase=MaintenancePhase.IDLE) {
    val busy get()=phase in setOf(MaintenancePhase.QUEUED,MaintenancePhase.RUNNING,MaintenancePhase.STOPPING)
}
@Serializable internal data class MaintenanceCheckpoint(val status: MaintenanceStatus=MaintenanceStatus(),val owner: Long=0,
    val targets: List<PublishedAudio> = emptyList(),val next: Int=0,val search: Boolean=false,
    val metadata: TrackMetadataState=TrackMetadataState(),val artwork: ArtworkState=ArtworkState(),val files: SavedFilesState=SavedFilesState())
internal data class MaintenanceBatch(val rows: List<PublishedAudio>,val next: Int,val resumed: Boolean)

/** One owner of long file operations; no Activity/ViewModel scope owns its lifetime. */
internal object MaintenanceRuntime {
    const val OPEN_EXTRA="open_maintenance";const val RUN_EXTRA="maintenance_run"
    val stages=setOf("track_metadata","artwork","saved_files_background")
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    val metadata=MutableStateFlow(TrackMetadataState())
    val artwork=MutableStateFlow(ArtworkState())
    val files=MutableStateFlow(SavedFilesState())
    val status=MutableStateFlow(MaintenanceStatus())
    val busy get()=status.value.busy
    private var journal: AtomicFile?=null
    private var location=""
    private var snapshot=MaintenanceCheckpoint()
    private var work: Job?=null
    private var pausedOutcome=false
    private var ready: CompletableDeferred<Unit>?=null
    private val json=Json{encodeDefaults=true;ignoreUnknownKeys=true}
    private val lock=Any()
    /** IO only. Reopening a screen must not overwrite an active task with its on-disk checkpoint. */
    fun initialize(context: Context)=synchronized(lock) {
        val path=File(context.noBackupFilesDir,"maintenance.json").path
        if(location==path)return@synchronized
        musicCheck(!busy,MusicError.BUSY)
        location=path;journal=AtomicFile(File(path))
        snapshot=try {
            val file=journal!!
            if(!file.baseFile.exists()&&!File(path+".bak").exists())MaintenanceCheckpoint() else {
                val bytes=file.openRead().use{it.readNBytes(MAX_BYTES+1)};require(bytes.size<=MAX_BYTES)
                json.decodeFromString<MaintenanceCheckpoint>(strictUtf8(bytes)).also {
                    require(it.status.stage.isEmpty()||it.status.stage in stages)
                    require(it.next in 0..it.targets.size&&it.targets.size<=20000&&it.owner>=0)
                }
            }
        }catch(_: Exception){MaintenanceCheckpoint()}
        val old=snapshot.status
        val restored=if(old.busy)old.copy(phase=MaintenancePhase.PAUSED)else old
        snapshot=snapshot.copy(status=restored)
        status.value=restored
        metadata.value=snapshot.metadata.copy(visible=false,running=false,name=null)
        artwork.value=snapshot.artwork.copy(visible=false,running=false,current=null,resumable=snapshot.status.stage=="artwork"&&snapshot.next<snapshot.targets.size)
        files.value=snapshot.files.copy(visible=false,running=false,selecting=null,preview=null,forget=null,resumable=snapshot.status.stage=="saved_files_background"&&snapshot.next<snapshot.targets.size)
    }
    fun checkpoint()=synchronized(lock) {
        snapshot=snapshot.copy(status=status.value,metadata=metadata.value.copy(visible=false),
            artwork=artwork.value.copy(visible=false),files=files.value.copy(visible=false))
        val bytes=json.encodeToString(snapshot).toByteArray()
        musicCheck(bytes.size<=MAX_BYTES,MusicError.STORAGE)
        val file=journal?:throw MusicFailure(MusicError.STORAGE)
        val stream=file.startWrite()
        try{stream.write(bytes);file.finishWrite(stream)}catch(e: Exception){file.failWrite(stream);throw e}
    }
    fun batch(stage: String,owner: Long,rows: List<PublishedAudio>,search: Boolean=false): MaintenanceBatch=synchronized(lock) {
        val resume=snapshot.status.stage==stage&&snapshot.owner==owner&&snapshot.next<snapshot.targets.size&&snapshot.search==search
        if(!resume)snapshot=snapshot.copy(owner=owner,targets=rows,next=0,search=search)
        checkpoint();MaintenanceBatch(snapshot.targets,snapshot.next,resume)
    }
    fun advance(next: Int)=synchronized(lock){require(next in 0..snapshot.targets.size);snapshot=snapshot.copy(next=next);checkpoint()}
    fun isResuming(stage: String)=synchronized(lock){snapshot.status.stage==stage&&snapshot.next<snapshot.targets.size}
    fun markPaused(){pausedOutcome=true}
    fun savedSearch()=synchronized(lock){snapshot.status.stage=="saved_files_background"&&snapshot.next<snapshot.targets.size&&snapshot.search}
    /** Called from the visible UI. Tests may supply an explicit coroutine launcher instead of Android's service. */
    fun launch(context: Context,stage: String,id: String,launcher: ((Job)->Unit)?=null,block:suspend()->Unit): Job {
        require(stage in stages);musicCheck(!busy,MusicError.BUSY)
        synchronized(lock){
            if(snapshot.status.stage!=stage)snapshot=MaintenanceCheckpoint(status=MaintenanceStatus(stage,id))
            status.value=MaintenanceStatus(stage,id,MaintenancePhase.QUEUED)
        }
        pausedOutcome=false
        val admission=CompletableDeferred<Unit>();ready=admission
        val job=scope.launch(start=CoroutineStart.UNDISPATCHED) {
            try {
                block()
            }finally {
                withContext(NonCancellable+Dispatchers.IO) {
                    status.update{it.copy(phase=if(!pausedOutcome&&it.phase==MaintenancePhase.RUNNING)MaintenancePhase.COMPLETED else MaintenancePhase.PAUSED)}
                    runCatching{checkpoint()}
                }
            }
        }
        work=job
        try {
            if(launcher!=null){launcher(job);admission.complete(Unit)}
            else ContextCompat.startForegroundService(context,Intent(context,MaintenanceService::class.java).putExtra(RUN_EXTRA,id))
        }catch(_: Exception) {
            status.update{it.copy(phase=MaintenancePhase.STOPPING)}
            pausedOutcome=true
            admission.complete(Unit)
            job.cancel(CancellationException("Maintenance service was not started"))
            metadata.update{it.copy(running=false,message="Android не запустил фоновую обработку. Открой приложение и повтори запуск.")}
            artwork.update{it.copy(running=false,message="Android не запустил фоновую обработку. Повтори запуск из приложения.")}
            files.update{it.copy(running=false,message="Android не запустил фоновую проверку. Повтори запуск из приложения.")}
        }
        return job
    }
    suspend fun awaitAdmission() {
        ready?.await();currentCoroutineContext().ensureActive()
        status.update{it.copy(phase=MaintenancePhase.RUNNING)}
        withContext(Dispatchers.IO){checkpoint()}
    }
    fun admitted(id: String){if(status.value.id==id)ready?.complete(Unit)}
    fun pause(id: String=status.value.id) {
        if(id!=status.value.id||!busy)return
        status.update{it.copy(phase=MaintenancePhase.STOPPING)}
        work?.cancel()
    }
    private const val MAX_BYTES=32*1024*1024
}
