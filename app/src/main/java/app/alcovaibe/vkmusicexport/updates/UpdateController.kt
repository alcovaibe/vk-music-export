package app.alcovaibe.vkmusicexport.updates

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.alcovaibe.vkmusicexport.BuildConfig
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

internal enum class UpdateStatus { IDLE,CHECKING,AVAILABLE,CURRENT,ACCESS,ERROR,RATE_LIMIT,DOWNLOADING,READY,INVALID_APK,INSTALL_ERROR }
internal data class UpdateState(val initialized: Boolean=false,val configured: Boolean=false,val editing: Boolean=false,
    val automatic: Boolean=true,val status: UpdateStatus=UpdateStatus.IDLE,val release: UpdateRelease?=null,
    val received: Long=0,val total: Long=0,val apk: File?=null)
internal interface UpdatePreferences {
    var lastAttempt: Long
    var deferred: Int
    var automatic: Boolean
}

internal class UpdateController(private val scope: CoroutineScope,private val access: UpdateAccess,private val preferences: UpdatePreferences,
    private val service: UpdateService,private val installed: Long,private val sdk: Int,
    private val wall: ()->Long=System::currentTimeMillis,private val elapsed: ()->Long=SystemClock::elapsedRealtime,
    private val record: (String)->Unit={},private val prepare: ()->Unit={}) {
    private val mutable=MutableStateFlow(UpdateState())
    val state=mutable.asStateFlow()
    private var active=false
    private var checkJob: Job?=null
    private var transfer: Job?=null
    @Volatile private var generation=0L
    private var checkGeneration=0L
    private var lastManual: Long?=null
    init {
        scope.launch {
            val configured=withContext(Dispatchers.IO){runCatching{prepare();access.read()!=null}.getOrDefault(false)}
            mutable.value=UpdateState(initialized=true,configured=configured,automatic=preferences.automatic)
            automaticCheck()
        }
    }
    fun resume(){active=true;automaticCheck()}
    fun pause(){active=false;checkGeneration++;checkJob?.cancel();if(mutable.value.status==UpdateStatus.CHECKING)mutable.update{it.copy(status=UpdateStatus.IDLE)}}
    private fun automaticCheck() {
        val s=mutable.value
        if(!active||!s.initialized||!s.configured||!s.automatic||s.editing||s.status!=UpdateStatus.IDLE)return
        val now=wall();val last=preferences.lastAttempt
        if(now<last){preferences.lastAttempt=now;return}
        if(last==0L||now-last>=86400000)check(false)
    }
    fun check(manual: Boolean=true) {
        val s=mutable.value
        if(!active||!s.initialized||s.editing||checkJob?.isActive==true||s.status in transfers)return
        if(!s.configured){if(manual)mutable.update{it.copy(status=UpdateStatus.ACCESS)};return}
        if(manual&&lastManual?.let{elapsed()-it<5000}==true)return
        if(!requests.tryLock())return
        if(manual)lastManual=elapsed()else preferences.lastAttempt=wall()
        val gen=checkGeneration
        mutable.update{it.copy(status=UpdateStatus.CHECKING,release=null)};record("check_started")
        checkJob=scope.launch(start=CoroutineStart.UNDISPATCHED) {
            try {
                val token=withContext(Dispatchers.IO){access.read()} ?: throw UpdateProblem("ACCESS")
                val release=service.latest(token)
                if(!active||gen!=checkGeneration)return@launch
                val newer=release.code>installed&&release.minSdk<=sdk
                mutable.update{it.copy(status=when{newer&&(manual||release.code>preferences.deferred)->UpdateStatus.AVAILABLE;manual->UpdateStatus.CURRENT;else->UpdateStatus.IDLE},
                    release=release.takeIf{newer&&(manual||release.code>preferences.deferred)})}
                record(if(newer)"check_newer" else "check_current")
            }catch(e: TimeoutCancellationException){if(active&&gen==checkGeneration)error(UpdateStatus.ERROR,manual)}
            catch(e: CancellationException){throw e}
            catch(e: Exception){if(active&&gen==checkGeneration)error(problem(e),manual)}
            finally {requests.unlock()}
        }
    }
    private fun problem(e: Exception)=when((e as? UpdateProblem)?.code){"ACCESS"->UpdateStatus.ACCESS;"RATE_LIMIT"->UpdateStatus.RATE_LIMIT;"INVALID_APK"->UpdateStatus.INVALID_APK;else->UpdateStatus.ERROR}
    private fun error(status: UpdateStatus,manual: Boolean){mutable.update{it.copy(status=if(manual)status else UpdateStatus.IDLE)};record("check_failed")}
    fun automatic(value: Boolean){preferences.automatic=value;mutable.update{it.copy(automatic=value)};if(value)automaticCheck()}
    fun configure(value: String?) {
        if(!mutable.value.initialized||mutable.value.editing)return
        val token=value?.trim()
        if(token!=null&&!UpdateProtocol.validToken(token)){mutable.update{it.copy(status=UpdateStatus.ACCESS)};return}
        cancel();mutable.update{it.copy(editing=true,status=UpdateStatus.IDLE)}
        scope.launch {
            try {
                withContext(Dispatchers.IO){if(token==null)access.delete()else access.save(token)}
                preferences.lastAttempt=0;preferences.deferred=0;lastManual=null
                mutable.update{it.copy(editing=false,configured=token!=null,status=UpdateStatus.IDLE)}
                record(if(token==null)"access_removed" else "access_saved")
                if(token!=null)check()
            }catch(e: CancellationException){throw e}
            catch (_: Exception){mutable.update{it.copy(editing=false,status=UpdateStatus.ERROR)};record("access_failed")}
        }
    }
    fun download() {
        val s=mutable.value;val release=s.release ?: return
        if(!active||s.editing||transfer?.isActive==true||s.status in setOf(UpdateStatus.CHECKING,UpdateStatus.READY,UpdateStatus.INSTALL_ERROR))return
        val gen=++generation;mutable.update{it.copy(status=UpdateStatus.DOWNLOADING,received=0,total=release.apk.size)};record("download_started")
        transfer=scope.launch(start=CoroutineStart.UNDISPATCHED) {
            try {
                val token=withContext(Dispatchers.IO){access.read()} ?: throw UpdateProblem("ACCESS")
                val file=service.download(token,release){received,total->mutable.update{if(gen==generation&&it.status==UpdateStatus.DOWNLOADING&&it.release===release)it.copy(received=received,total=total)else it}}
                if(gen==generation){mutable.update{it.copy(status=UpdateStatus.READY,apk=file)};record("download_verified")}
                else withContext(Dispatchers.IO){file.delete()}
            }catch(e: CancellationException){throw e}
            catch(e: Exception){if(gen==generation){mutable.update{it.copy(status=problem(e))};record("download_failed")}}
        }
    }
    suspend fun verifiedApk(): File? {
        val s=mutable.value;val file=s.apk ?: return null;val release=s.release ?: return null
        if(!active)return null
        try{service.verify(file,release);return file.takeIf{active&&mutable.value==s}}
        catch(e: CancellationException){throw e}
        catch (_: Exception){withContext(Dispatchers.IO){file.delete()};if(mutable.value==s)mutable.update{it.copy(status=UpdateStatus.INVALID_APK,apk=null)};record("apk_rejected");return null}
    }
    fun installFailed(){mutable.update{it.copy(status=UpdateStatus.INSTALL_ERROR)};record("install_failed")}
    fun defer(){mutable.value.release?.let{preferences.deferred=maxOf(preferences.deferred,it.code)};cancel()}
    fun cancel() {
        generation++;checkGeneration++;checkJob?.cancel();transfer?.cancel()
        val file=mutable.value.apk
        if(file!=null)scope.launch(Dispatchers.IO){file.delete()}
        mutable.update{it.copy(status=UpdateStatus.IDLE,release=null,received=0,total=0,apk=null)}
    }
    fun close(){active=false;cancel()}
    private companion object {
        val requests=Mutex()
        val transfers=setOf(UpdateStatus.DOWNLOADING,UpdateStatus.READY,UpdateStatus.INVALID_APK,UpdateStatus.INSTALL_ERROR)
    }
}

internal class UpdateViewModel(app: Application): AndroidViewModel(app) {
    private val service=GithubUpdates(app)
    private val preferences=app.getSharedPreferences("updates",Context.MODE_PRIVATE)
    val controller=UpdateController(viewModelScope,KeystoreUpdateAccess(app),object: UpdatePreferences {
        override var lastAttempt: Long
            get()=preferences.getLong("last_attempt",0)
            set(v){preferences.edit().putLong("last_attempt",v).apply()}
        override var deferred: Int
            get()=preferences.getInt("deferred",0)
            set(v){preferences.edit().putInt("deferred",v).apply()}
        override var automatic: Boolean
            get()=preferences.getBoolean("automatic",true)
            set(v){preferences.edit().putBoolean("automatic",v).apply()}
    },service,BuildConfig.VERSION_CODE.toLong(),Build.VERSION.SDK_INT,record={Diagnostics.event("updater",mapOf("stage" to it))},
        prepare={service.purgeOrphans()})
    override fun onCleared(){controller.close();service.close()}
}
