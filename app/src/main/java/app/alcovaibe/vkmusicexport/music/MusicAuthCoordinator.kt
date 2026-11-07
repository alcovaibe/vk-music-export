package app.alcovaibe.vkmusicexport.music

import android.content.Context
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** Owns the device-auth conversation, including its one-use code channel.
 * The host admits operations; credentials are committed only after music verification.
 */
internal class MusicAuthCoordinator(
    private val _state: MutableStateFlow<NativeMusicState>,
    private val deviceAuth: DeviceAuthApi,
    private val api: MusicApi,
    private val authForeground: MutableStateFlow<Boolean>,
    private val authClock: ()->Long,
    private val authPollMs: Long,
    private val authBrowser: (Context,String)->Boolean,
    private val operation: (String,suspend(Int)->Unit)->Unit,
    private val active: suspend(Int)->Unit,
    private val isCurrent: (Int)->Boolean,
    private val save: suspend(MusicAccess)->Unit,
    private val reload: suspend()->Unit,
    private val acceptProbe: suspend(Int,MusicAccess,NativeProbe)->Unit,
    private val stopOnline: ()->Unit,
    private val deviceName: ()->String={"VK Music Export / "+android.os.Build.MANUFACTURER.take(24)+" "+android.os.Build.MODEL.take(48)}
) {
    private val authOpened=MutableStateFlow(false)
    private var authSession: DeviceSession?=null
    private var authCodes: Channel<DeviceConfirmationCode>?=null

    fun reset() {
        authSession=null;authOpened.value=false;authCodes?.cancel();authCodes=null
    }
    private fun authPhase(phase: DeviceAuthPhase) {
        if(_state.value.authPhase==phase)return
        _state.update{it.copy(authPhase=phase,
            authCodePending=if(phase==DeviceAuthPhase.AWAITING_APPROVAL)false else it.authCodePending)}
        Diagnostics.event("native_auth_phase",mapOf("phase" to phase.name))
    }
    fun authenticate() {
        Diagnostics.button("native_auth_start")
        operation("browser_auth") {g->
            authSession=null;authOpened.value=false
            authPhase(DeviceAuthPhase.PREPARING)
            val device=deviceName()
            val session=deviceAuth.begin(device);active(g);authSession=session
            authPhase(DeviceAuthPhase.READY)
            val codes=Channel<DeviceConfirmationCode>(1);authCodes=codes
            val access=try {
                awaitDeviceAccess(deviceAuth,session,authOpened,authForeground,authClock,authPollMs,codes,
                    onIncorrectCode={if(isCurrent(g))_state.update{it.copy(authCodeIssue=DeviceCodeIssue.INCORRECT,authCodePending=false)}},
                    onPhase={if(isCurrent(g))authPhase(it)})
            }finally{codes.cancel();if(authCodes===codes)authCodes=null}
            active(g);authPhase(DeviceAuthPhase.VERIFYING)
            val result=try{api.library(access)}catch(e: CancellationException){throw e}
                catch(e: MusicFailure){throw DeviceAuthFailure(DeviceAuthIssue.MUSIC_CHECK,e.remoteCode,e.code)}
            active(g);authForeground.first{it};active(g)
            if(access.renewal!=null&&access.renewal.owner!=result.owner)
                throw DeviceAuthFailure(DeviceAuthIssue.MUSIC_CHECK,musicError=MusicError.ACCESS)
            // Only a real successful audio.get may replace an already saved credential.
            stopOnline()
            try{save(access.withSession(verifiedAt=System.currentTimeMillis(),accountId=result.owner))}
            catch(e: CancellationException){throw e}
            catch(_: Exception){throw DeviceAuthFailure(DeviceAuthIssue.STORAGE)}
            active(g)
            reload()
            _state.update{it.copy(configured=true,accessDamaged=false)}
            acceptProbe(g,access,result);active(g)
            authPhase(DeviceAuthPhase.CONNECTED)
        }
    }
    fun openAuthChrome(context: Context)=openBrowser{authBrowser(context,it)}
    internal fun openBrowser(dispatch: (String)->Boolean) {
        Diagnostics.button("native_auth_open_chrome")
        val s=authSession
        if(!_state.value.busy||_state.value.stopping||_state.value.stage!="browser_auth"||s==null)return
        if(authClock()>=s.expiresAt) {
            _state.update{it.copy(authIssue=DeviceAuthIssue.EXPIRED)}
            return
        }
        val opened=dispatch(s.url)
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
        operation("configure") {g->
            val candidate=MusicAccess(token,agent)
            val proof=api.library(candidate);active(g)
            stopOnline()
            active(g)
            save(candidate.withSession(verifiedAt=System.currentTimeMillis(),accountId=proof.owner))
            active(g)
            _state.update{it.copy(configured=true,accessDamaged=false)}
            reload()
            acceptProbe(g,candidate,proof)
        }
    }
}
