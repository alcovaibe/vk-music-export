package app.alcovaibe.vkmusicexport.music

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private object NativeMusicGate {val mutex=Mutex()}

internal data class NativeMusicState(val initialized: Boolean=false,val configured: Boolean=false,val busy: Boolean=false,
    val stage: String="",val error: MusicError?=null,val message: String?=null,val probe: NativeProbe?=null,
    val catalog: NativeCatalog?=null,val catalogError: MusicError?=null,val page: NativePage?=null,val selected: NativePlaylist?=null,
    val authPhase: DeviceAuthPhase?=null,val authIssue: DeviceAuthIssue?=null,
    val authCodeIssue: DeviceCodeIssue?=null,val authCodePending: Boolean=false,
    val stopping: Boolean=false,val waitingForSlot: Boolean=false,val pageOffset: Int=0,val catalogOffset: Int=0,
    val preparing: String?=null)

internal class NativeMusicViewModel @JvmOverloads constructor(application: Application,
    private val api: MusicApi=VkAudioClient(sessions=MusicSessions.get(application)),private val deviceAuth: DeviceAuthApi=VkDeviceAuthClient(),
    private val authClock: ()->Long={android.os.SystemClock.elapsedRealtime()},private val authPollMs: Long=5000,
    private val authBrowser: (android.content.Context,String)->Boolean=::openDeviceAuthChrome): AndroidViewModel(application) {
    private val vault=MusicAccessStore(application)
    private val sessions=if(api is VkAudioClient)MusicSessions.get(application) else null
    val sessionState=sessions?.state?:MutableStateFlow(MusicSessionState()).asStateFlow()
    private var sessionMonitor: Job?=null
    private val operations=NativeOperationJournal(application)
    private val _state=MutableStateFlow(NativeMusicState());val state=_state.asStateFlow()
    private var work: Job?=null
    private val authForeground=MutableStateFlow(false)
    private val authOpened=MutableStateFlow(false)
    private var authSession: DeviceSession?=null
    private var authCodes: Channel<DeviceConfirmationCode>?=null
    private var operationOwner: Int?=null
    @Volatile private var generation=0
    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val removed=LegacyAudioCleanup(application).run()
                if(removed>0)Diagnostics.event("legacy_audio_cleanup",mapOf("removed_files" to removed))
            }catch(e: CancellationException){throw e}
             catch(_: Exception){Diagnostics.event("legacy_audio_cleanup_failed",mapOf("code" to "STORAGE"))}
            try{AudioExportRuntime.initialize(application)}catch(_: Exception){_state.update{it.copy(error=MusicError.STORAGE)}}
        }
        viewModelScope.launch {
            try {
                val loaded=withContext(Dispatchers.IO){
                    val interrupted=if(NativeMusicGate.mutex.tryLock())try{operations.recover()}finally{NativeMusicGate.mutex.unlock()}else false
                    Pair(vault.read()!=null,interrupted)
                }
                _state.value=NativeMusicState(initialized=true,configured=loaded.first,
                    message=if(loaded.second)"Предыдущая операция прервана. Готовые файлы сохранены; проверку можно повторить." else null)
            }catch(_: Exception){_state.value=NativeMusicState(initialized=true,error=MusicError.STORAGE)}
        }
    }
    private fun run(stage: String,block: suspend (Int)->Unit) {
        if(!_state.value.initialized||_state.value.busy){
            Diagnostics.event("native_music_operation_ignored",mapOf("stage" to stage,"reason" to if(_state.value.busy)"busy" else "initializing"))
            return
        }
        val g=++generation;val id=UUID.randomUUID().toString()
        operationOwner=g
        _state.update{it.copy(busy=true,stage=stage,error=null,message=null,authIssue=null,
            authCodeIssue=null,authCodePending=false,stopping=false,preparing=null,waitingForSlot=NativeMusicGate.mutex.isLocked,
            authPhase=if(stage=="browser_auth")DeviceAuthPhase.PREPARING else null)}
        Diagnostics.event("native_music_operation",mapOf("stage" to stage,"phase" to "start"))
        if(stage=="browser_auth")Diagnostics.event("native_auth_phase",mapOf("phase" to DeviceAuthPhase.PREPARING.name))
        work=viewModelScope.launch(start=CoroutineStart.UNDISPATCHED) {
            var outcome="failed"
            try {
                // An old Activity/operation may still be finishing its non-cancellable cleanup.
                // Wait for that owner instead of failing the user's next attempt with BUSY.
                NativeMusicGate.mutex.withLock {
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
                if(g==generation) {
                    val error=(e as? MusicFailure)?.code?:MusicError.STORAGE
                    _state.update{it.copy(error=error,authPhase=if(stage=="browser_auth")DeviceAuthPhase.FAILED else it.authPhase,
                        authIssue=if(stage=="browser_auth")DeviceAuthIssue.RESPONSE else it.authIssue)}
                    Diagnostics.event("native_music_operation",mapOf("stage" to stage,"phase" to "failed","code" to error.name,"vk_code" to (e as? MusicFailure)?.remoteCode))
                }
            }finally {
                if(operationOwner==g){
                    operationOwner=null;work=null
                    authSession=null;authOpened.value=false;authCodes?.cancel();authCodes=null
                    _state.update{it.copy(busy=false,stage="",stopping=false,waitingForSlot=false,
                        authCodePending=false,authCodeIssue=null,preparing=null,
                        message=if(it.stopping)"Операция остановлена. Готовые файлы сохранены." else it.message)}
                }
            }
        }
    }
    private suspend fun active(g: Int){currentCoroutineContext().ensureActive();if(g!=generation)throw CancellationException()}
    private suspend fun access(): MusicAccess=withContext(Dispatchers.IO){vault.read()?:throw MusicFailure(MusicError.ACCESS)}
    fun setForeground(value: Boolean) {
        if(authForeground.value==value)return
        authForeground.value=value
        sessionMonitor?.cancel();sessionMonitor=null
        if(value&&sessions!=null)sessionMonitor=viewModelScope.launch {
            while(isActive) {
                try{sessions.current()}catch(e: CancellationException){throw e}catch(_: Exception){/* Sanitized session state describes the result. */}
                delay(60_000)
            }
        }
        if(_state.value.stage=="browser_auth")Diagnostics.event("native_auth_lifecycle",
            mapOf("foreground" to value,"detector" to if(value)"activity_onResume" else "activity_onPause"))
    }
    private fun authPhase(phase: DeviceAuthPhase) {
        if(_state.value.authPhase==phase)return
        _state.update{it.copy(authPhase=phase,
            authCodePending=if(phase==DeviceAuthPhase.AWAITING_APPROVAL)false else it.authCodePending)}
        Diagnostics.event("native_auth_phase",mapOf("phase" to phase.name))
    }
    fun authenticate() {
        Diagnostics.button("native_auth_start")
        run("browser_auth") {g->
            authSession=null;authOpened.value=false
            authPhase(DeviceAuthPhase.PREPARING)
            val device="VK Music Export / "+android.os.Build.MANUFACTURER.take(24)+" "+android.os.Build.MODEL.take(48)
            val session=deviceAuth.begin(device);active(g);authSession=session
            authPhase(DeviceAuthPhase.READY)
            val codes=Channel<DeviceConfirmationCode>(1);authCodes=codes
            val access=try {
                awaitDeviceAccess(deviceAuth,session,authOpened,authForeground,authClock,authPollMs,codes,
                    onIncorrectCode={if(g==generation)_state.update{it.copy(authCodeIssue=DeviceCodeIssue.INCORRECT,authCodePending=false)}},
                    onPhase={if(g==generation)authPhase(it)})
            }finally{codes.cancel();if(authCodes===codes)authCodes=null}
            active(g);authPhase(DeviceAuthPhase.VERIFYING)
            val result=try{api.library(access)}catch(e: CancellationException){throw e}
                catch(e: MusicFailure){throw DeviceAuthFailure(DeviceAuthIssue.MUSIC_CHECK,e.remoteCode,e.code)}
            active(g);authForeground.first{it};active(g)
            if(access.renewal!=null&&access.renewal.owner!=result.owner)
                throw DeviceAuthFailure(DeviceAuthIssue.MUSIC_CHECK,musicError=MusicError.ACCESS)
            // Only a real successful audio.get may replace an already saved credential.
            try{withContext(Dispatchers.IO){vault.save(access.withSession(verifiedAt=System.currentTimeMillis()))}}
            catch(e: CancellationException){throw e}
            catch(_: Exception){throw DeviceAuthFailure(DeviceAuthIssue.STORAGE)}
            active(g)
            sessions?.reload()
            _state.update{it.copy(configured=true)}
            acceptProbe(g,access,result);active(g)
            authPhase(DeviceAuthPhase.CONNECTED)
        }
    }
    fun openAuthChrome(context: android.content.Context) {
        Diagnostics.button("native_auth_open_chrome")
        val s=authSession
        if(!_state.value.busy||_state.value.stopping||_state.value.stage!="browser_auth"||s==null)return
        if(authClock()>=s.expiresAt) {
            _state.update{it.copy(authIssue=DeviceAuthIssue.EXPIRED)}
            return
        }
        val opened=authBrowser(context,s.url)
        Diagnostics.event("native_auth_browser_dispatch",mapOf("accepted" to opened,"auth_status" to "not_proven"))
        if(opened)authOpened.value=true
        _state.update{it.copy(authIssue=if(opened)null else DeviceAuthIssue.BROWSER)}
        if(opened && _state.value.authPhase==DeviceAuthPhase.READY)authPhase(DeviceAuthPhase.WAITING)
    }
    fun submitAuthCode(raw: String) {
        val s=_state.value
        if(!s.busy||s.stopping||s.authPhase!=DeviceAuthPhase.ENTER_CODE||s.authCodePending||!authForeground.value)return
        val code=DeviceConfirmationCode.from(raw)
        if(code==null){_state.update{it.copy(authCodeIssue=DeviceCodeIssue.FORMAT)};return}
        val codes=authCodes?:return
        Diagnostics.button("native_auth_submit_code")
        // Main.immediate may resume the receiver and finish validation inside trySend.
        // Publish pending first so a fast result cannot be overwritten by a stale true.
        _state.update{it.copy(authCodeIssue=null,authCodePending=true)}
        if(!codes.trySend(code).isSuccess)_state.update{it.copy(authCodePending=false)}
    }
    fun configure(token: String,agent: String) {
        Diagnostics.button("native_music_save_access")
        if(!NativeProtocol.validAccess(token,agent)){_state.update{it.copy(error=MusicError.INVALID_ACCESS)};return}
        run("configure") {g->
            withContext(Dispatchers.IO){vault.save(MusicAccess(token,agent))};active(g)
            _state.value=NativeMusicState(initialized=true,configured=true,busy=true,stage="configure")
            sessions?.reload()
            probe(g)
        }
    }
    fun checkAccess(){Diagnostics.button("native_music_probe");run("probe"){probe(it)}}
    private suspend fun probe(g: Int) {
        _state.update{it.copy(probe=null,page=null,selected=null,catalog=null,catalogError=null)}
        val access=access();val probe=api.library(access);active(g)
        withContext(Dispatchers.IO){vault.replace(access,access.withSession(verifiedAt=System.currentTimeMillis()))}
        sessions?.reload()
        acceptProbe(g,access,probe)
    }
    private suspend fun acceptProbe(g: Int,access: MusicAccess,probe: NativeProbe) {
        active(g)
        _state.update{it.copy(probe=probe,page=probe.library,pageOffset=0,catalogOffset=0,selected=null,catalog=null,catalogError=null)}
        Diagnostics.event("native_music_sample",mapOf("stage" to "library","entries" to probe.library.tracks.size,"expected_count" to probe.library.count,"page_limit" to NativeProtocol.LIMIT,"complete" to false))
        try {
            val catalog=api.catalog(access,probe.owner);active(g)
            _state.update{it.copy(catalog=catalog,message="Музыкальный доступ работает. Списки показаны постранично; скачивание читает все позиции выбранного источника.")}
            Diagnostics.event("native_music_sample",mapOf("stage" to "catalog","entries" to catalog.lists.size,"expected_count" to catalog.count,"complete" to false))
        }catch(e: CancellationException){throw e}
         catch(e: Exception){active(g);val error=(e as? MusicFailure)?.code?:MusicError.RESPONSE;_state.update{it.copy(catalogError=error)}}
    }
    fun select(list: NativePlaylist?) {
        Diagnostics.button(if(list==null)"native_music_library" else "native_music_playlist")
        val probe=_state.value.probe?:return
        if(list==null){if(!_state.value.busy)_state.update{it.copy(page=probe.library,pageOffset=0,selected=null)};return}
        run("playlist") {g->
            val page=api.playlist(access(),list);active(g);_state.update{it.copy(page=page,pageOffset=0,selected=list)}
            Diagnostics.event("native_music_sample",mapOf("stage" to "playlist","entries" to page.tracks.size,"expected_count" to page.count,"complete" to false))
        }
    }
    fun page(direction: Int) {
        val s=_state.value;val page=s.page?:return;val owner=s.probe?.owner?:return
        val offset=if(direction<0)(s.pageOffset-NativeProtocol.LIMIT).coerceAtLeast(0) else s.pageOffset+page.tracks.size
        if(offset>=page.count||offset==s.pageOffset)return
        Diagnostics.button("native_music_page")
        run("page") {g->
            val a=access();val next=s.selected?.let{api.playlistPage(a,it,offset)}?:api.libraryPage(a,owner,offset)
            active(g);musicCheck(next.tracks.isNotEmpty()||next.count==0,MusicError.INCOMPLETE)
            _state.update{it.copy(page=next,pageOffset=offset)}
        }
    }
    fun catalogPage(direction: Int) {
        val s=_state.value;val catalog=s.catalog?:return;val owner=s.probe?.owner?:return
        val offset=if(direction<0)(s.catalogOffset-NativeProtocol.LIMIT).coerceAtLeast(0) else s.catalogOffset+catalog.lists.size
        if(offset>=catalog.count||offset==s.catalogOffset)return
        Diagnostics.button("native_music_catalog_page")
        run("catalog_page") {g->val next=api.catalogPage(access(),owner,offset);active(g)
            musicCheck(next.lists.isNotEmpty()||next.count==0,MusicError.INCOMPLETE)
            _state.update{it.copy(catalog=next,catalogOffset=offset)}
        }
    }
    /** Preparing metadata stays in the visible Activity; audio is handed to a persisted UIDT queue. */
    fun prepare(allCatalog: Boolean=false,kind: PlaylistKind?=null) {
        val s=_state.value;val owner=s.probe?.owner?:return
        Diagnostics.button(if(allCatalog)"audio_queue_prepare_all" else "audio_queue_prepare_source")
        run("prepare") {g->
            withContext(Dispatchers.IO) {
                AudioExportRuntime.initialize(getApplication())
                musicCheck(!AudioExportRuntime.state.value.unfinished&&!AudioExportRuntime.active.get(),MusicError.BUSY)
                val a=access();val tasks=mutableListOf<AudioExportTask>();var issues=0
                suspend fun read(list: NativePlaylist?): NativePage = MusicPagination.tracks({offset->
                    active(g);if(list==null)api.libraryPage(a,owner,offset)else api.playlistPage(a,list,offset)
                }){n,total->_state.update{it.copy(preparing="Получено позиций: $n/$total")}}
                if(allCatalog||s.selected==null) {
                    tasks.addAll(ExportLayout.library(read(null)))
                }else {
                    val list=s.selected;tasks.addAll(ExportLayout.playlist(read(list),list,kind?:list.kind))
                }
                if(allCatalog) {
                    val catalog=MusicPagination.catalog({offset->active(g);api.catalogPage(a,owner,offset)}){n,total->
                        _state.update{it.copy(preparing="Получено подборок: $n/$total")}
                    }
                    issues+=(catalog.count-catalog.lists.size).coerceAtLeast(0)
                    for((i,list) in catalog.lists.withIndex()) {
                        active(g);_state.update{it.copy(preparing="Чтение подборки ${i+1}/${catalog.lists.size}")}
                        try{tasks.addAll(ExportLayout.playlist(read(list),list))}
                        catch(e: MusicFailure) {
                            if(e.code !in setOf(MusicError.DENIED,MusicError.UNAVAILABLE,MusicError.METHOD,MusicError.INCOMPLETE,MusicError.LIBRARY_CHANGED))throw e
                            issues++;Diagnostics.event("audio_queue_source_skipped",mapOf("code" to e.code.name))
                        }
                    }
                }
                active(g);AudioExportRuntime.replace(getApplication(),tasks,issues)
                active(g);AudioExportRuntime.start(getApplication())
                _state.update{it.copy(message="Очередь готова. Файлы сохраняются в Music/VK Music Export. Можно свернуть приложение."+
                    if(issues>0)" Не удалось прочитать источников: $issues. См. итог очереди." else "")}
            }
        }
    }
    fun download(track: NativeTrack,position: Int) {
        Diagnostics.button("native_music_download_one")
        val s=_state.value
        run("queue_one") {g->withContext(Dispatchers.IO){
            musicCheck(position>0,MusicError.RESPONSE)
            val tasks=if(s.selected==null)listOf(ExportLayout.task(track,listOf("Моя музыка"),position))
                else ExportLayout.playlist(NativePage(listOf(track),1),s.selected).map{ExportLayout.task(it.track,it.folders,position)}
            active(g);AudioExportRuntime.replace(getApplication(),tasks);active(g);AudioExportRuntime.start(getApplication())
        }}
    }
    fun resumeQueue(retry: Boolean=false) {
        Diagnostics.button(if(retry)"audio_queue_retry" else "audio_queue_resume")
        run("queue_resume"){g->withContext(Dispatchers.IO){active(g);AudioExportRuntime.start(getApplication(),retry)}}
    }
    fun pauseQueue() {
        Diagnostics.button("audio_queue_pause")
        viewModelScope.launch(Dispatchers.IO){try{AudioExportRuntime.pause(getApplication())}catch(_: Exception){_state.update{it.copy(error=MusicError.STORAGE)}}}
    }
    fun clearQueue() {
        Diagnostics.button("audio_queue_clear")
        run("queue_clear"){g->withContext(Dispatchers.IO){active(g);AudioExportRuntime.clear(getApplication())}}
    }
    fun clearRecentFiles() {
        Diagnostics.button("audio_recent_clear")
        run("recent_clear"){g->withContext(Dispatchers.IO){active(g);AudioExportRuntime.clearRecentFiles(getApplication())}}
    }
    fun cancel()=stop("native_stop_button")
    fun stopForWindow()=stop("native_window_closed")
    fun stopInBackground() {
        setForeground(false)
        // Every browser-auth phase survives Chrome, including code validation and music verification.
        if(_state.value.stage=="browser_auth")return
        stop("activity_onStop")
    }
    private fun stop(detector: String) {
        if(!_state.value.busy||_state.value.stopping)return
        if(detector=="native_stop_button")Diagnostics.button("native_music_cancel")
        Diagnostics.event("native_music_cancel",mapOf("detector" to detector))
        generation++;authSession=null;authOpened.value=false;authCodes?.cancel();authCodes=null
        _state.update{it.copy(stopping=true,authPhase=null,authIssue=null,authCodeIssue=null,
            authCodePending=false,message="Завершаем остановку операции…")}
        work?.cancel()
    }
    fun forget() {
        if(_state.value.busy)return
        Diagnostics.button("native_music_forget_access")
        run("forget") {g->
            withContext(Dispatchers.IO){vault.delete()};active(g);sessions?.reload()
            _state.value=NativeMusicState(initialized=true,busy=true,stage="forget",message="Доступ ВК удалён с устройства.")
        }
    }
    override fun onCleared(){work?.cancel();super.onCleared()}
}
