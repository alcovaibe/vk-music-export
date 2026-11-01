package app.alcovaibe.vkmusicexport.music

import android.app.Service
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** Only started on API 29–33 from an explicit foreground user action. */
class LegacyAudioExportService : Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var work: Job?=null
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(Build.VERSION.SDK_INT>=34){stopSelfResult(startId);return START_NOT_STICKY}
        if(work!=null)return START_NOT_STICKY
        // FGS must be promoted before any disk/network access.
        try{startForeground(AudioExportRuntime.NOTIFICATION_ID,
            AudioExportRuntime.notification(this,AudioExportRuntime.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)}
        catch(e: Exception) {
            Diagnostics.event("audio_queue_schedule_failure",mapOf("transport" to "foreground_service"))
            stopSelfResult(startId);return START_NOT_STICKY
        }
        if(!AudioExportRuntime.active.compareAndSet(false,true)){
            stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(startId);return START_NOT_STICKY
        }
        work=scope.launch(Dispatchers.IO) {
            try {
                AudioExportRuntime.initialize(this@LegacyAudioExportService)
                AudioExportRuntime.run { snapshot ->
                    withContext(Dispatchers.Main) {
                        getSystemService(NotificationManager::class.java).notify(AudioExportRuntime.NOTIFICATION_ID,
                            AudioExportRuntime.notification(this@LegacyAudioExportService,snapshot))
                    }
                }
            }catch(e: CancellationException){throw e}
            catch(_: Exception){Diagnostics.event("audio_queue_failure",mapOf("code" to "STORAGE"))}
            finally {
                withContext(NonCancellable) {
                    try{AudioExportRuntime.stopped()}catch(_: Exception){
                        Diagnostics.event("audio_queue_failure",mapOf("code" to "STORAGE"))
                    }finally{AudioExportRuntime.active.set(false)}
                    withContext(Dispatchers.Main) {
                        val notification=AudioExportRuntime.notification(this@LegacyAudioExportService,AudioExportRuntime.state.value)
                        getSystemService(NotificationManager::class.java).notify(AudioExportRuntime.NOTIFICATION_ID,notification)
                        stopForeground(STOP_FOREGROUND_DETACH)
                        stopSelfResult(startId)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int,fgsType: Int){work?.cancel();stopSelfResult(startId)}
    override fun onDestroy(){work?.cancel();scope.cancel();super.onDestroy()}
}

/** Duplicate scan uses the same state, checkpoints, mutual exclusion and hash cache as UIDT. */
class LegacyDuplicateCheckService : Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var work: Job?=null
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        val id=intent?.getStringExtra(DuplicateCheckRuntime.RUN_EXTRA)
        if(Build.VERSION.SDK_INT>=34||id==null||id!=DuplicateCheckRuntime.state.value.scanId ||
            !DuplicateCheckRuntime.state.value.busy){stopSelfResult(startId);return START_NOT_STICKY}
        if(work!=null)return START_NOT_STICKY
        try{startForeground(DuplicateCheckRuntime.NOTIFICATION_ID,
            DuplicateCheckRuntime.notification(this,DuplicateCheckRuntime.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)}
        catch(_: Exception) {
            DuplicateCheckRuntime.interrupted(this,id)
            stopSelfResult(startId);return START_NOT_STICKY
        }
        work=scope.launch(Dispatchers.IO) {
            val updates=launch {
                DuplicateCheckRuntime.state.collect { snapshot ->
                    withContext(Dispatchers.Main) {
                        getSystemService(NotificationManager::class.java).notify(DuplicateCheckRuntime.NOTIFICATION_ID,
                            DuplicateCheckRuntime.notification(this@LegacyDuplicateCheckService,snapshot))
                    }
                }
            }
            try{DuplicateCheckRuntime.run(this@LegacyDuplicateCheckService,id)}
            catch(e: CancellationException){throw e}
            catch(_: Exception){Diagnostics.event("audio_duplicates_failure",mapOf("code" to "STORAGE"))}
            finally{
                withContext(NonCancellable){
                    updates.cancelAndJoin()
                    DuplicateCheckRuntime.interrupted(this@LegacyDuplicateCheckService,id)
                    withContext(Dispatchers.Main){
                        getSystemService(NotificationManager::class.java).notify(DuplicateCheckRuntime.NOTIFICATION_ID,
                            DuplicateCheckRuntime.notification(this@LegacyDuplicateCheckService,DuplicateCheckRuntime.state.value))
                        stopForeground(STOP_FOREGROUND_DETACH)
                        stopSelfResult(startId)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int,fgsType: Int){work?.cancel();stopSelfResult(startId)}
    override fun onDestroy(){work?.cancel();scope.cancel();super.onDestroy()}
}
