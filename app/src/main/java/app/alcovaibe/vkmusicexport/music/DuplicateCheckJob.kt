package app.alcovaibe.vkmusicexport.music

import android.app.*
import android.app.job.*
import android.content.*
import android.os.PersistableBundle
import app.alcovaibe.vkmusicexport.MainActivity
import app.alcovaibe.vkmusicexport.R
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

internal object DuplicateCheckRuntime {
    const val JOB_ID=10061;const val NOTIFICATION_ID=10062;const val CHANNEL="duplicate-check"
    const val OPEN_EXTRA="open_duplicates";const val RUN_EXTRA="duplicate_run"
    val mutable=MutableStateFlow(DuplicatesState());val state=mutable.asStateFlow()
    private val execution=Mutex();val active=AtomicBoolean(false)
    val transferring get()=active.get()||state.value.phase in setOf(DuplicatePhase.QUEUED,DuplicatePhase.SCANNING)
    private var task: DuplicateCheckTask?=null
    @Synchronized private fun task(context: Context): DuplicateCheckTask=task?:context.applicationContext.let{app->
        DuplicateCheckTask(app,VkAudioClient(sessions=MusicSessions.get(app)),MusicAccessStore(app),MusicSessions.get(app),mutable)
    }.also{task=it}
    fun initialize(context: Context){task(context).restore()}
    fun forget(context: Context){
        musicCheck(!transferring,MusicError.BUSY)
        BackgroundTransport.stopDuplicates(context)
        task(context).discard()
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
    suspend fun start(context: Context) {
        var id: String?=null
        try {
            AudioExportRuntime.editSaved(context) {
                musicCheck(!active.get()&&!mutable.value.busy&&!AudioExportRuntime.state.value.unfinished,MusicError.BUSY)
                id=task(context).prepare()
            }
            val result=withContext(Dispatchers.Main) {
                if(BackgroundTransport.startDuplicates(context,id!!)) JobScheduler.RESULT_SUCCESS else JobScheduler.RESULT_FAILURE
            }
            if(result!=JobScheduler.RESULT_SUCCESS)task(context).pause(id!!,"Android не запустил проверку. Результаты сохранены; повтори из приложения.")
        }catch(e: Exception){
            id?.let{value->withContext(NonCancellable+Dispatchers.IO){
                BackgroundTransport.stopDuplicates(context);task(context).pause(value)
            }}
            throw e
        }
    }
    @androidx.annotation.RequiresApi(34)
    fun job(context: Context,id: String): JobInfo=JobInfo.Builder(JOB_ID,ComponentName(context,DuplicateCheckJob::class.java))
        .setUserInitiated(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setRequiresStorageNotLow(true)
        .setExtras(PersistableBundle().apply{putString(RUN_EXTRA,id)}).build()
    suspend fun run(context: Context,id: String)=execution.withLock {
        active.set(true)
        try{AudioExportRuntime.editSaved(context,duplicateCheck=true){
            musicCheck(!AudioExportRuntime.state.value.unfinished,MusicError.BUSY)
            task(context).execute(id)
        }}finally{active.set(false)}
    }
    /** A ready result may be tapped before the JobService finishes its cleanup. */
    suspend fun awaitIdle(){execution.withLock { }}
    fun interrupted(context: Context,id: String){
        task(context).pause(id)
        if(state.value.scanId==id&&state.value.phase==DuplicatePhase.BLOCKED)
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,notification(context,state.value))
    }
    suspend fun pause(context: Context,id: String) {
        if(mutable.value.scanId!=id||!mutable.value.busy)return
        task(context).pause(id)
        BackgroundTransport.stopDuplicates(context)
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,notification(context,state.value))
    }
    fun notification(context: Context,s: DuplicatesState): Notification {
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"Проверка дубликатов",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(context,JOB_ID,Intent(context,MainActivity::class.java).putExtra(OPEN_EXTRA,true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text=when(s.phase){DuplicatePhase.QUEUED->"Ожидаем сеть и запуск Android";DuplicatePhase.SCANNING->
            "${s.progress.done}/${s.progress.total} · готовых проверок: ${s.savedChecks}";DuplicatePhase.READY->"Готово · дубликатов: ${s.found}";else->"Приостановлено · готовых проверок: ${s.savedChecks}"}
        return Notification.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_music_note).setContentTitle("Дубликаты в музыке ВК")
            .setContentText(text).setContentIntent(open).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(s.busy).setStyle(Notification.BigTextStyle().bigText(listOfNotNull(text,s.progress.stage,
                s.progress.track?.let{"${it.artist} — ${it.displayTitle}"},s.progress.bytes.takeIf{it>0}?.let{"Получено: ${it/1024} КиБ"}).joinToString("\n")))
            .apply{if(s.busy){setProgress(s.progress.total,s.progress.done,s.progress.total<=0)
                val pause=PendingIntent.getBroadcast(context,JOB_ID,Intent(context,DuplicateCheckPauseReceiver::class.java)
                    .setAction("${context.packageName}.duplicate_pause.${s.scanId}").putExtra(RUN_EXTRA,s.scanId),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                addAction(Notification.Action.Builder(R.drawable.ic_music_note,"Пауза",pause).build())}}
            .build()
    }
}

@android.annotation.TargetApi(34)
class DuplicateCheckJob: JobService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private data class Running(val id: String,val stopped: AtomicBoolean=AtomicBoolean(false),var work: Job?=null)
    private var running: Running?=null
    override fun onStartJob(params: JobParameters): Boolean {
        val id=params.extras.getString(DuplicateCheckRuntime.RUN_EXTRA)?:return false
        val run=Running(id);running=run
        setNotification(params,DuplicateCheckRuntime.NOTIFICATION_ID,DuplicateCheckRuntime.notification(this,DuplicateCheckRuntime.state.value),JOB_END_NOTIFICATION_POLICY_DETACH)
        run.work=scope.launch {
            val updates=launch {
                DuplicateCheckRuntime.state.collect {
                    delay(500)
                    withContext(Dispatchers.Main){if(!run.stopped.get())setNotification(params,DuplicateCheckRuntime.NOTIFICATION_ID,
                        DuplicateCheckRuntime.notification(this@DuplicateCheckJob,DuplicateCheckRuntime.state.value),JOB_END_NOTIFICATION_POLICY_DETACH)}
                }
            }
            try{DuplicateCheckRuntime.run(this@DuplicateCheckJob,id)}
            catch(e: CancellationException){throw e}
            catch(_: Exception){DuplicateCheckRuntime.interrupted(this@DuplicateCheckJob,id)}
            finally{withContext(NonCancellable){
                updates.cancelAndJoin()
                withContext(Dispatchers.Main){if(!run.stopped.get()){
                    try{setNotification(params,DuplicateCheckRuntime.NOTIFICATION_ID,DuplicateCheckRuntime.notification(this@DuplicateCheckJob,DuplicateCheckRuntime.state.value),JOB_END_NOTIFICATION_POLICY_DETACH)}
                    finally{jobFinished(params,false)}
                }}
            }}
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        running?.takeIf{it.id==params.extras.getString(DuplicateCheckRuntime.RUN_EXTRA)}?.let{run->
            run.stopped.set(true);run.work?.cancel()
            CoroutineScope(SupervisorJob()+Dispatchers.IO).launch{runCatching{DuplicateCheckRuntime.interrupted(this@DuplicateCheckJob,run.id)}}
        }
        Diagnostics.event("audio_duplicates_system_stop",mapOf("reason" to if(android.os.Build.VERSION.SDK_INT>=31) params.stopReason else -1));return false
    }
    override fun onDestroy(){
        running?.let{run->
            run.stopped.set(true);run.work?.cancel()
            CoroutineScope(SupervisorJob()+Dispatchers.IO).launch{runCatching{DuplicateCheckRuntime.interrupted(this@DuplicateCheckJob,run.id)}}
        }
        scope.cancel();super.onDestroy()
    }
}
class DuplicateCheckPauseReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val id=intent.getStringExtra(DuplicateCheckRuntime.RUN_EXTRA)?:return
        val pending=goAsync()
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch{try{DuplicateCheckRuntime.pause(context,id)}catch(_: Exception){Diagnostics.event("audio_duplicates_pause_failure",mapOf("code" to "STORAGE"))}finally{pending.finish()}}
    }
}
