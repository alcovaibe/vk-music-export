package app.alcovaibe.vkmusicexport.music

import android.app.Application
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Process-wide admission only; independent screen readers never acquire this gate. */
private object NativeMusicGate { val mutex=Mutex() }

internal class MusicOperationCleanup(val verification: Job?,val finish: ()->Unit)

/** One owner of foreground cancellation and retained maintenance admission.
 * File writes additionally remain owned by AudioExportRuntime.editSaved.
 */
internal class MusicOperationCoordinator(
    private val application: Application,
    private val scope: CoroutineScope,
    private val _state: MutableStateFlow<NativeMusicState>,
    private val maintenanceLauncher: ((Job)->Unit)?,
    private val prepareOperation: (String)->MusicOperationCleanup,
    private val resetAuth: ()->Unit
) {
    private val operations=NativeOperationJournal(application)
    var work: Job?=null
        private set
    @Volatile var generation=0
        private set
    private var operationOwner: Int?=null

    fun invalidate() { generation++ }
    fun recover(): Boolean=if(NativeMusicGate.mutex.tryLock())try{operations.recover()}finally{NativeMusicGate.mutex.unlock()}else false

    fun run(stage: String,block: suspend (Int)->Unit) {
        if(MaintenanceRuntime.busy){_state.update{it.copy(error=MusicError.BUSY)};return}
        val maintenance=stage in MaintenanceRuntime.stages
        if(DuplicateCheckRuntime.transferring&&stage!="duplicates_delete"){
            _state.update{it.copy(error=MusicError.BUSY)};return
        }
        // A user-initiated queue must take precedence over an automatic catalog refresh.
        // Cancel the old reader, then wait for its cleanup under NativeMusicGate below.
        val preemptBackground=stage=="prepare"&&_state.value.busy&&
            _state.value.stage in setOf("catalog_refresh","resume_refresh")
        if(preemptBackground)work?.cancel()
        if(!_state.value.initialized||(_state.value.busy&&!preemptBackground)){
            Diagnostics.event("native_music_operation_ignored",mapOf("stage" to stage,"reason" to if(_state.value.busy)"busy" else "initializing"))
            return
        }
        val cleanup=prepareOperation(stage)
        val g=++generation;val id=UUID.randomUUID().toString()
        operationOwner=g
        _state.update{it.copy(busy=true,stage=stage,error=null,message=null,authIssue=null,
            authCodeIssue=null,authCodePending=false,stopping=false,preparing=null,waitingForSlot=NativeMusicGate.mutex.isLocked,
            authPhase=if(stage=="browser_auth")DeviceAuthPhase.PREPARING else null)}
        Diagnostics.event("native_music_operation",mapOf("stage" to stage,"phase" to "start"))
        if(stage=="browser_auth")Diagnostics.event("native_auth_phase",mapOf("phase" to DeviceAuthPhase.PREPARING.name))
        val operation: suspend()->Unit={
            var outcome="failed"
            try {
                if(maintenance)MaintenanceRuntime.awaitAdmission()
                // An old Activity/operation may still be finishing its non-cancellable cleanup.
                // Wait for that owner instead of failing the user's next attempt with BUSY.
                NativeMusicGate.mutex.withLock {
                    cleanup.verification?.join()
                    active(g);_state.update{it.copy(waitingForSlot=false)}
                    try {
                        withContext(Dispatchers.IO){operations.begin(id)}
                        block(g);active(g);outcome="completed"
                        Diagnostics.event("native_music_operation",mapOf("stage" to stage,"phase" to "completed"))
                    }catch(e: CancellationException){outcome="interrupted";throw e}
                    finally {
                        withContext(NonCancellable+Dispatchers.IO){runCatching{operations.finish(id,outcome)}}
                    }
                }
            }catch(e: CancellationException){outcome="interrupted";throw e}
             catch(e: DeviceAuthFailure) {
                if(g==generation) {
                    _state.update{it.copy(authPhase=DeviceAuthPhase.FAILED,authIssue=e.issue,error=e.musicError)}
                    Diagnostics.event("native_auth_failed",mapOf("code" to e.issue.name,"vk_code" to e.remoteCode,"music_code" to e.musicError?.name))
                }
             }
             catch(e: Exception){
                if(maintenance)MaintenanceRuntime.markPaused()
                if(g==generation) {
                    val error=(e as? MusicFailure)?.code?:MusicError.STORAGE
                    _state.update{it.copy(error=error,authPhase=if(stage=="browser_auth")DeviceAuthPhase.FAILED else it.authPhase,
                        authIssue=if(stage=="browser_auth")DeviceAuthIssue.RESPONSE else it.authIssue)}
                    Diagnostics.event("native_music_operation",mapOf("stage" to stage,"phase" to "failed","code" to error.name,"vk_code" to (e as? MusicFailure)?.remoteCode))
                }
            }finally {
                cleanup.finish()
                if(maintenance||stage in setOf("metadata_lookup_apply","cover_lookup_apply","saved_files"))withContext(NonCancellable+Dispatchers.IO){runCatching{MaintenanceRuntime.checkpoint()}}
                if(operationOwner==g){
                    operationOwner=null;work=null
                    resetAuth()
                    _state.update{it.copy(busy=false,stage="",stopping=false,waitingForSlot=false,
                        authCodePending=false,authCodeIssue=null,preparing=null,
                        libraryRefreshing=if(it.stopping)false else it.libraryRefreshing,
                        catalogRefreshing=if(it.stopping)false else it.catalogRefreshing,
                        libraryStale=it.libraryStale||(it.stopping&&it.libraryRefreshing&&it.page!=null),
                        catalogStale=it.catalogStale||(it.stopping&&it.catalogRefreshing&&it.catalog!=null),
                        message=if(it.stopping)"Операция остановлена. Готовые файлы сохранены." else it.message)}
                }
            }
        }
        work=if(maintenance)MaintenanceRuntime.launch(application,stage,id,maintenanceLauncher,operation)
            else scope.launch(start=CoroutineStart.UNDISPATCHED){operation()}
    }
    suspend fun active(g: Int){currentCoroutineContext().ensureActive();if(g!=generation)throw CancellationException()}
}
