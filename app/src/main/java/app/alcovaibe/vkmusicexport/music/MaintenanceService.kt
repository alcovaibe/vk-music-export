package app.alcovaibe.vkmusicexport.music

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import app.alcovaibe.vkmusicexport.MainActivity
import app.alcovaibe.vkmusicexport.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/** File maintenance includes local hashing/tagging and must also work without a network. */
class MaintenanceService: Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var observer: Job?=null
    private var served=""
    private var wake: PowerManager.WakeLock?=null
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        val id=intent?.getStringExtra(MaintenanceRuntime.RUN_EXTRA)
        if(id==null||id!=MaintenanceRuntime.status.value.id||!MaintenanceRuntime.busy){stopSelfResult(startId);return START_NOT_STICKY}
        served=id
        try {
            startForeground(NOTIFICATION_ID,notification(this),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            wake?.takeIf{it.isHeld}?.release()
            wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"$packageName:maintenance").apply {
                setReferenceCounted(false);acquire(6L*60*60*1000)
            }
            observer?.cancel()
            observer=scope.launch {
                // Snapshot writes happen after each completed file. A small ticker also displays the current file.
                while(isActive&&MaintenanceRuntime.status.value.id==id) {
                    val status=MaintenanceRuntime.status.value
                    runCatching{getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,notification(this@MaintenanceService))}
                    if(!status.busy){stopForeground(STOP_FOREGROUND_DETACH);stopSelfResult(startId);break}
                    delay(500)
                }
            }
            MaintenanceRuntime.admitted(id)
        }catch(_: Exception){MaintenanceRuntime.pause(id);stopForeground(STOP_FOREGROUND_DETACH);stopSelfResult(startId)}
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int,fgsType: Int){MaintenanceRuntime.pause(served);stopForeground(STOP_FOREGROUND_DETACH);stopSelf(startId)}
    override fun onDestroy(){MaintenanceRuntime.pause(served);observer?.cancel();scope.cancel();wake?.takeIf{it.isHeld}?.release();wake=null;super.onDestroy()}
    companion object {
        const val NOTIFICATION_ID=10072;const val CHANNEL="file-maintenance"
        internal fun notification(context: Context): Notification {
            val s=MaintenanceRuntime.status.value
            val manager=context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL,"Обработка сохранённой музыки",NotificationManager.IMPORTANCE_LOW))
            val title=when(s.stage){"track_metadata"->"Названия и пометки";"artwork"->"Обложки сохранённых треков";else->"Проверка сохранённых файлов"}
            val meta=MaintenanceRuntime.metadata.value;val cover=MaintenanceRuntime.artwork.value;val files=MaintenanceRuntime.files.value
            val done=when(s.stage){"track_metadata"->meta.processed;"artwork"->cover.processed;else->files.processed}
            val total=when(s.stage){"track_metadata"->meta.total;"artwork"->cover.total;else->files.total}
            val detail=when(s.stage){"track_metadata"->meta.name?:meta.message;"artwork"->cover.current?:cover.message;else->files.message}
            val phase=when(s.phase){MaintenancePhase.QUEUED->"Подготовка";MaintenancePhase.RUNNING->"Обработано: $done/$total";MaintenancePhase.STOPPING->"Завершаем текущую запись";MaintenancePhase.COMPLETED->"Завершено: $done/$total";else->"Приостановлено: $done/$total"}
            val open=PendingIntent.getActivity(context,NOTIFICATION_ID,Intent(context,MainActivity::class.java)
                .putExtra(MaintenanceRuntime.OPEN_EXTRA,s.stage).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return Notification.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_music_note).setContentTitle(title).setContentText(phase)
                .setStyle(Notification.BigTextStyle().bigText(listOfNotNull(phase,detail).joinToString("\n")))
                .setContentIntent(open).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE).setOngoing(s.busy)
                .apply{if(s.busy){setProgress(total,done,total<=0)
                    val pause=PendingIntent.getBroadcast(context,NOTIFICATION_ID,Intent(context,MaintenancePauseReceiver::class.java).putExtra(MaintenanceRuntime.RUN_EXTRA,s.id),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    addAction(Notification.Action.Builder(R.drawable.ic_music_note,"Пауза",pause).build())}}
                .build()
        }
    }
}
class MaintenancePauseReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent){intent.getStringExtra(MaintenanceRuntime.RUN_EXTRA)?.let{MaintenanceRuntime.pause(it)}}
}
