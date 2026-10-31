package app.alcovaibe.vkmusicexport

import android.app.Application
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MaintenanceBackgroundDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val inst=InstrumentationRegistry.getInstrumentation()
    private suspend fun until(check:()->Boolean){withTimeout(25000){while(!check())delay(25)}}
    private fun shell(command: String){ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(command)).use{it.readBytes()}}
    private fun isolated(real: Application,dir: File)=object: Application(){init{attachBaseContext(real)}
        override fun getNoBackupFilesDir()=File(dir,"private").apply{mkdirs()}
        override fun getFilesDir()=File(dir,"files").apply{mkdirs()}
        override fun getCacheDir()=File(dir,"cache").apply{mkdirs()}
    }
    private fun model(holder: ViewModelStore,app: Application,api: MusicApi,before:suspend(String,Int)->Unit={_,_->}): NativeMusicViewModel =
        ViewModelProvider(holder,object: ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")override fun <T: androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T=
                NativeMusicViewModel(app,api,maintenanceBeforeItem=before) as T
        })[NativeMusicViewModel::class.java]

    @Test fun allThreeOperationsContinueWithActivityStoppedAndViewModelClearedAndNotificationReopensProgress()=runBlocking {
        val real=ui.activity.application;lateinit var home: NativeMusicViewModel
        ui.runOnUiThread{home=ViewModelProvider(ui.activity)[NativeMusicViewModel::class.java]}
        until{home.state.value.initialized}
        val granted=real.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED
        shell("pm grant ${real.packageName} android.permission.POST_NOTIFICATIONS")
        try {
            for((number,stage) in MaintenanceRuntime.stages.withIndex()) {
                val dir=File(real.cacheDir,"maintenance-life-${UUID.randomUUID()}").apply{mkdirs()};val app=isolated(real,dir)
                val holder=ViewModelStore();val nextHolder=ViewModelStore();val rows=mutableListOf<PublishedAudio>()
                val reached=CompletableDeferred<Unit>();val proceed=CompletableDeferred<Unit>()
                val calls=mutableListOf<String>();lateinit var current: NativeMusicViewModel;lateinit var reopened: NativeMusicViewModel
                val api=object: MusicApi {
                    override suspend fun library(access: MusicAccess)=NativeProbe(12,NativePage(emptyList(),0))
                    override suspend fun catalog(access: MusicAccess,owner: Long)=NativeCatalog(emptyList(),0)
                    override suspend fun playlist(access: MusicAccess,list: NativePlaylist)=error("No playlist")
                    override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("No audio download")
                    override suspend fun metadata(access: MusicAccess,id: String): NativeTrack? {
                        calls.add(id);return rows.firstOrNull{it.track.id==id&&it==rows.first()}?.track?.copy(subtitle="Cover")
                    }
                    override suspend fun artworkCandidates(access: MusicAccess,track: NativeTrack)=emptyList<ArtworkCandidate>()
                }
                try {
                    withContext(Dispatchers.IO){
                        AudioExportRuntime.clear(real)
                        AudioExportStore(app).use{store->
                            for(index in 1..2) {
                                val t=NativeTrack("12_${991000+number*10+index}","Maintenance $index","CI",1)
                                val task=ExportLayout.task(t,listOf("CI maintenance ${dir.name}"),index,ExportSource(12,"library","my"))
                                val input=File(dir,"input.mp3").apply{writeBytes(inst.context.assets.open("native-audio/tone.mp3").use{it.readBytes()})}
                                val row=SharedAudioStore(app).publish(task,TransferResult(input,ProcessedAudio("mp3","audio/mpeg",1200))){}
                                store.done(task,row);rows.add(row)
                            }
                        }
                        MusicAccessStore(app).save(MusicAccess("a".repeat(64),"CI/1",accountId=12))
                    }
                    ui.runOnUiThread{current=model(holder,app,api){kind,index->if(kind==stage&&index==0){reached.complete(Unit);proceed.await()}}}
                    until{current.state.value.initialized}
                    ui.runOnUiThread {
                        current.setForeground(true)
                        when(stage){"track_metadata"->{current.showTrackMetadata();current.updateSavedTrackMetadata()};"artwork"->{current.showArtwork();current.addSavedArtwork()};else->current.checkSavedFiles()}
                    }
                    withTimeout(20000){reached.await()}
                    until{real.getSystemService(NotificationManager::class.java).activeNotifications.any{it.id==MaintenanceService.NOTIFICATION_ID}}
                    ui.runOnUiThread{current.stopInBackground();holder.clear()}
                    ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                    assertTrue(MaintenanceRuntime.busy)
                    proceed.complete(Unit)
                    until{!MaintenanceRuntime.busy}
                    assertEquals(MaintenancePhase.COMPLETED,MaintenanceRuntime.status.value.phase)
                    when(stage) {
                        "track_metadata"->{assertEquals(2,MaintenanceRuntime.metadata.value.processed);assertEquals(1,MaintenanceRuntime.metadata.value.updated);assertEquals(1,MaintenanceRuntime.metadata.value.unavailable);assertNotNull(MaintenanceRuntime.metadata.value.issues.single().target)}
                        "artwork"->{assertEquals(2,MaintenanceRuntime.artwork.value.processed);assertEquals(2,MaintenanceRuntime.artwork.value.noCover)}
                        else->{assertEquals(2,MaintenanceRuntime.files.value.processed);assertTrue(MaintenanceRuntime.files.value.problems.isEmpty());assertTrue(calls.isEmpty())}
                    }
                    until{real.getSystemService(NotificationManager::class.java).activeNotifications.any{it.id==MaintenanceService.NOTIFICATION_ID&&it.notification.extras.getString("android.text").orEmpty().contains("Завершено")}}
                    ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                    ui.runOnUiThread{reopened=model(nextHolder,app,api);ui.activity.setContent{MusicTheme{NativeMusicPanel(reopened,true,{})}}}
                    until{reopened.state.value.initialized}
                    val note=real.getSystemService(NotificationManager::class.java).activeNotifications.single{it.id==MaintenanceService.NOTIFICATION_ID}
                    note.notification.contentIntent.send()
                    until{when(stage){"track_metadata"->reopened.trackMetadata.value.visible;"artwork"->reopened.artwork.value.visible;else->reopened.savedFiles.value.visible}}
                    until{!reopened.state.value.busy}
                    assertFalse(reopened.state.value.busy)
                    withContext(Dispatchers.IO) {
                        val checkpoint=Json{ignoreUnknownKeys=true}.decodeFromString<MaintenanceCheckpoint>(File(app.noBackupFilesDir,"maintenance.json").readText())
                        assertEquals(MaintenancePhase.COMPLETED,checkpoint.status.phase)
                        for(row in rows)assertTrue(SharedAudioStore(app).present(AudioExportStore(app).use{it.existing(row.taskKey)!!}))
                    }
                }finally {
                    proceed.complete(Unit);MaintenanceRuntime.pause();until{!MaintenanceRuntime.busy}
                    ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                    ui.runOnUiThread{ui.activity.setContent{MusicTheme{Text("CI")}};holder.clear();nextHolder.clear()}
                    withContext(Dispatchers.IO){rows.forEach{real.contentResolver.delete(Uri.parse(it.uri),null,null)};MaintenanceRuntime.initialize(real);dir.deleteRecursively()}
                }
            }
        }finally{real.getSystemService(NotificationManager::class.java).cancel(MaintenanceService.NOTIFICATION_ID);if(!granted)shell("pm revoke ${real.packageName} android.permission.POST_NOTIFICATIONS")}
    }

    @Test fun savedCheckpointRestoresPausedCursorReportsAndNeverRestoresManualConsent()=runBlocking<Unit>(Dispatchers.IO) {
        val real=ui.activity.application;lateinit var home: NativeMusicViewModel
        ui.runOnUiThread{home=ViewModelProvider(ui.activity)[NativeMusicViewModel::class.java]}
        until{home.state.value.initialized}
        val dir=File(real.cacheDir,"maintenance-checkpoint-${UUID.randomUUID()}").apply{mkdirs()}
        val app=isolated(real,dir)
        try {
            val row=PublishedAudio("key",NativeTrack("12_1","CI","Artist",1),"content://media/external_primary/audio/media/1","CI.mp3",ExportLayout.ROOT+"Моя музыка/","mp3","audio/mpeg",1000,10,"a".repeat(64))
            val state=MaintenanceCheckpoint(MaintenanceStatus("saved_files_background","ci-run",MaintenancePhase.RUNNING),targets=listOf(row,row.copy(taskKey="other")),next=1,search=true,
                files=SavedFilesState(processed=1,total=2,problems=listOf(SavedFileProblem(row,SavedFileReason.MISSING)),forget=row,selecting=row))
            File(app.noBackupFilesDir,"maintenance.json").writeText(Json.encodeToString(MaintenanceCheckpoint.serializer(),state))
            MaintenanceRuntime.initialize(app)
            assertEquals(MaintenancePhase.PAUSED,MaintenanceRuntime.status.value.phase)
            assertEquals(1,MaintenanceRuntime.files.value.processed);assertTrue(MaintenanceRuntime.files.value.resumable)
            assertNull(MaintenanceRuntime.files.value.forget);assertNull(MaintenanceRuntime.files.value.selecting)
            assertTrue(MaintenanceRuntime.savedSearch())
            val batch=MaintenanceRuntime.batch("saved_files_background",0,emptyList(),search=true)
            assertTrue(batch.resumed);assertEquals(1,batch.next);assertEquals(2,batch.rows.size)
            assertEquals(1,MaintenanceRuntime.files.value.problems.size)
        }finally{MaintenanceRuntime.initialize(real);dir.deleteRecursively()}
    }
}
