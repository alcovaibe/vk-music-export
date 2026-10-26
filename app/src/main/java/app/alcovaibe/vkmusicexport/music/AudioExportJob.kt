package app.alcovaibe.vkmusicexport.music

import android.app.*
import android.app.job.*
import android.content.*
import app.alcovaibe.vkmusicexport.MainActivity
import app.alcovaibe.vkmusicexport.R
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object AudioExportRuntime {
    const val JOB_ID=10051;const val NOTIFICATION_ID=10052;const val CHANNEL="music-downloads"
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var ready: Deferred<Unit>?=null
    private val controls=Mutex()
    private lateinit var app: Context
    private lateinit var database: AudioExportStore
    private lateinit var engine: AudioExportEngine
    val active=AtomicBoolean(false)
    private val mutable=MutableStateFlow(ExportSnapshot());val state=mutable.asStateFlow()
    @Synchronized private fun readiness(context: Context): Deferred<Unit> {
        ready?.let{return it}
        app=context.applicationContext;database=AudioExportStore(app)
        engine=AudioExportEngine(database,SharedAudioStore(app),File(app.cacheDir,"audio-export-transfer"),api=VkAudioClient(sessions=MusicSessions.get(app)),credential={
            MusicAccessStore(app).read()?:throw MusicFailure(MusicError.ACCESS)
        })
        return scope.async{engine.recover();refresh()}.also{ready=it}
    }
    suspend fun initialize(context: Context) {
        try{readiness(context).await()}
        catch(e: CancellationException){throw e}
        catch(_: Exception){mutable.update{it.copy(status="PAUSED",error=MusicError.STORAGE)};throw MusicFailure(MusicError.STORAGE)}
    }
    suspend fun replace(context: Context,tasks: List<AudioExportTask>,sourceIssues: Int=0) {
        initialize(context);controls.withLock {
            musicCheck(!active.get()&&state.value.status!="QUEUED",MusicError.BUSY)
            database.replace(tasks.distinctBy{it.key},sourceIssues);refresh()
        }
    }
    suspend fun start(context: Context,retryFailed: Boolean=false) {
        initialize(context);controls.withLock {
        if(active.get()||state.value.status=="QUEUED")return
        if(retryFailed)database.retryFailed()
        if(!database.snapshot().unfinished){refresh();return}
        database.status("QUEUED");refresh()
        val result=withContext(Dispatchers.Main) {
            try {
                context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(JOB_ID,ComponentName(context,AudioExportJob::class.java))
                    .setUserInitiated(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setRequiresStorageNotLow(true).build())
            }catch(_: Exception){JobScheduler.RESULT_FAILURE}
        }
        if(result!=JobScheduler.RESULT_SUCCESS){database.status("PAUSED");refresh()}
        Diagnostics.event("audio_queue_schedule",mapOf("accepted" to (result==JobScheduler.RESULT_SUCCESS),"transport" to "user_initiated_job"))
        }
    }
    suspend fun pause(context: Context) {
        initialize(context);controls.withLock {
            database.status("PAUSED",database.snapshot().error);refresh()
            context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        }
    }
    suspend fun clear(context: Context) {
        initialize(context);controls.withLock {
            musicCheck(!active.get()&&state.value.status!="QUEUED",MusicError.BUSY)
            context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
            database.clearQueue();refresh()
        }
    }
    fun progress(value: AudioProgress){mutable.update{it.copy(progress=value)}}
    suspend fun <T> editSaved(context: Context,block: suspend()->T): T {
        initialize(context)
        return controls.withLock {
            musicCheck(!active.get()&&state.value.status!="QUEUED",MusicError.BUSY)
            block()
        }
    }
    suspend fun clearRecentFiles(context: Context) {
        initialize(context);controls.withLock {database.clearRecentFiles();refresh()}
    }
    @Synchronized fun refresh(){
        val next=database.snapshot();val previous=mutable.value
        mutable.value=next.copy(progress=previous.progress.takeIf{next.status=="RUNNING"&&next.currentTask!=null&&next.currentTask.key==previous.currentTask?.key})
    }
    suspend fun run(update: suspend(ExportSnapshot)->Unit){engine.run{refresh();update(state.value)}}
    suspend fun stopped(){withContext(NonCancellable+Dispatchers.IO){database.releaseRunning();if(database.snapshot().unfinished)database.status("PAUSED",database.snapshot().error);refresh()}}
    fun notification(context: Context,snapshot: ExportSnapshot): Notification {
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"Скачивание музыки",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause=PendingIntent.getBroadcast(context,0,Intent(context,AudioExportPauseReceiver::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val ongoing=snapshot.status in setOf("RUNNING","QUEUED")
        val text=when(snapshot.status){"FINISHED"->"Музыка сохранена";"FINISHED_WITH_ERRORS"->"Завершено с пропусками: ${snapshot.failed+snapshot.sourceIssues}";"PAUSED"->"Приостановлено. Продолжить можно в приложении";else->"Сохранено ${snapshot.done} из ${snapshot.total}"}
        return Notification.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_music_note).setContentTitle("VK Music Export")
            .setContentText(text).setContentIntent(open).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(ongoing).apply{if(ongoing){setProgress(snapshot.total,snapshot.done+snapshot.failed,snapshot.total==0);addAction(Notification.Action.Builder(R.drawable.ic_music_note,"Пауза",pause).build())}}
            .build()
    }
}

class AudioExportJob: JobService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var work: Job?=null;@Volatile private var stopped=false
    override fun onStartJob(params: JobParameters): Boolean {
        if(!AudioExportRuntime.active.compareAndSet(false,true))return false
        stopped=false
        setNotification(params,AudioExportRuntime.NOTIFICATION_ID,AudioExportRuntime.notification(this,AudioExportRuntime.state.value),JOB_END_NOTIFICATION_POLICY_DETACH)
        work=scope.launch {
            try {
                AudioExportRuntime.initialize(this@AudioExportJob)
                AudioExportRuntime.run{snapshot->withContext(Dispatchers.Main){if(!stopped)setNotification(params,AudioExportRuntime.NOTIFICATION_ID,AudioExportRuntime.notification(this@AudioExportJob,snapshot),JOB_END_NOTIFICATION_POLICY_DETACH)}}
            }catch(e: CancellationException){throw e}
             catch(_: Exception){Diagnostics.event("audio_queue_failure",mapOf("code" to "STORAGE"))}
             finally {
                withContext(NonCancellable){
                    try{AudioExportRuntime.stopped()}catch(_: Exception){Diagnostics.event("audio_queue_failure",mapOf("code" to "STORAGE"))}
                    finally{AudioExportRuntime.active.set(false)}
                    withContext(Dispatchers.Main){if(!stopped){
                        try{setNotification(params,AudioExportRuntime.NOTIFICATION_ID,AudioExportRuntime.notification(this@AudioExportJob,AudioExportRuntime.state.value),JOB_END_NOTIFICATION_POLICY_DETACH)}
                        finally{jobFinished(params,false)}
                    }}
                }
             }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        stopped=true;Diagnostics.event("audio_queue_system_stop",mapOf("reason" to params.stopReason))
        work?.cancel();return false // Persisted queue remains available for an explicit resume.
    }
    override fun onDestroy(){work?.cancel();scope.cancel();super.onDestroy()}
}

class AudioExportPauseReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val pending=goAsync()
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch{try{AudioExportRuntime.pause(context)}catch(_: Exception){Diagnostics.event("audio_queue_failure",mapOf("code" to "STORAGE"))}finally{pending.finish()}}
    }
}
