package app.alcovaibe.vkmusicexport

import android.app.Notification
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class BackgroundCheckDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext
    private fun track(n: Int)=NativeTrack("12_$n","Synthetic background song","Synthetic artist",1)
    private class Credentials(var value: MusicAccess?): MusicCredentialStore {
        override fun read()=value
        override fun replace(expected: MusicAccess,next: MusicAccess): Boolean {
            if(value?.credentialId!=expected.credentialId)return false
            value=next;return true
        }
    }
    private fun context(dir: File)=object: ContextWrapper(app) {
        override fun getCacheDir()=File(dir,"cache").apply{mkdirs()}
        override fun getNoBackupFilesDir()=File(dir,"private").apply{mkdirs()}
    }
    private fun transfer(downloads: AtomicInteger): AudioTransfer {
        val client=NetworkClients.media().newBuilder().addInterceptor{chain->
            downloads.incrementAndGet()
            val bytes=instrumentation.context.assets.open("native-audio/tone.mp3").use{it.readBytes()}
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
                .header("Content-Type","audio/mpeg").body(bytes.toResponseBody()).build()
        }.build()
        return AudioTransfer(client,observe={_,_->})
    }
    @Test fun scanOutlivesActivityAndFinishesWithoutSendingAnyDeletion()=runBlocking<Unit> {
        val dir=File(app.cacheDir,"background-scan-lifecycle").apply{deleteRecursively();mkdirs()};val context=context(dir)
        val downloads=AtomicInteger();val waiting=CompletableDeferred<Unit>();val resume=CompletableDeferred<Unit>();val rows=(1..2).map(::track)
        val credentials=Credentials(MusicAccess("a".repeat(64),"Synthetic"));val serviceScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val api=object: MusicApi {
            override suspend fun library(access: MusicAccess)=NativeProbe(12,NativePage(rows,2))
            override suspend fun media(access: MusicAccess,track: NativeTrack): String {
                if(track.id=="12_2"){waiting.complete(Unit);resume.await()}
                return "https://audio.userapi.com/${track.id}.mp3"
            }
            override suspend fun catalog(access: MusicAccess,owner: Long)=error("Unused")
            override suspend fun playlist(access: MusicAccess,list: NativePlaylist)=error("Unused")
            override suspend fun removeLibraryTrack(access: MusicAccess,owner: Long,track: NativeTrack)=error("Background task must never delete")
        }
        val task=DuplicateCheckTask(context,api,credentials,null,hasherFactory={cache,progress->DuplicateAudioHasher(context,api,transfer(downloads),cache,progress)})
        try {
            val id=withContext(Dispatchers.IO){task.prepare()}
            val scenario=ActivityScenario.launch(MainActivity::class.java)
            try {
                val work=serviceScope.launch{task.execute(id)}
                withTimeout(15000){waiting.await()}
                scenario.moveToState(Lifecycle.State.CREATED);scenario.close()
                assertTrue(work.isActive);assertEquals(1,downloads.get());assertEquals(1,task.state.value.savedChecks)
                resume.complete(Unit);withTimeout(15000){work.join()}
                assertEquals(DuplicatePhase.READY,task.state.value.phase);assertEquals(1,task.state.value.found)
                assertNull(task.state.value.consent);assertEquals(2,downloads.get())
            }finally{scenario.close()}
        }finally{serviceScope.cancel();dir.deleteRecursively()}
    }
    @Test fun pauseAndTaskRecreationReuseCompletedHashesButNewLoginCannotResumeAnOldScheduledRequest()=runBlocking<Unit>(Dispatchers.IO) {
        val dir=File(app.cacheDir,"background-scan-resume").apply{deleteRecursively();mkdirs()};val context=context(dir)
        val downloads=AtomicInteger();val waiting=CompletableDeferred<Unit>();var wait=true;val rows=(1..2).map(::track)
        val credentials=Credentials(MusicAccess("a".repeat(64),"Synthetic"))
        val api=object: MusicApi {
            override suspend fun library(access: MusicAccess)=NativeProbe(12,NativePage(rows,2))
            override suspend fun media(access: MusicAccess,track: NativeTrack): String {
                if(track.id=="12_2"&&wait){waiting.complete(Unit);awaitCancellation()}
                return "https://audio.userapi.com/${track.id}.mp3"
            }
            override suspend fun catalog(access: MusicAccess,owner: Long)=error("Unused")
            override suspend fun playlist(access: MusicAccess,list: NativePlaylist)=error("Unused")
        }
        fun task()=DuplicateCheckTask(context,api,credentials,null,hasherFactory={cache,progress->DuplicateAudioHasher(context,api,transfer(downloads),cache,progress)})
        try {
            val first=task();val id=first.prepare();val work=launch{first.execute(id)}
            withTimeout(15000){waiting.await()};first.pause(id);work.cancelAndJoin()
            assertEquals(DuplicatePhase.BLOCKED,first.state.value.phase);assertEquals(1,downloads.get())
            val restored=task();restored.restore();assertEquals(1,restored.state.value.savedChecks);assertNull(restored.state.value.consent)
            val newId=restored.prepare();wait=false;restored.execute(newId)
            assertEquals(DuplicatePhase.READY,restored.state.value.phase);assertEquals(2,downloads.get());assertEquals(1,restored.state.value.progress.reused)
            val scheduled=task();val stale=scheduled.prepare()
            scheduled.pause(newId);assertEquals(DuplicatePhase.QUEUED,scheduled.state.value.phase)
            credentials.value=MusicAccess("b".repeat(64),"Synthetic")
            scheduled.execute(stale);assertEquals(DuplicatePhase.BLOCKED,scheduled.state.value.phase);assertEquals(2,downloads.get())
            scheduled.discard();assertEquals(DuplicatePhase.INTRO,scheduled.state.value.phase)
            assertEquals(0,DuplicateScanCache(context).count());assertFalse(File(context.noBackupFilesDir,"duplicate-job.json").exists())
            // A verified renewal while the accepted job waits for network is still the same read.
            val now=1_800_000_000_000L;var clock=now
            val renewal=WebRenewal("p".repeat(64),12,emptyList())
            credentials.value=MusicAccess("a".repeat(64),"Synthetic",now+600_000,renewal,credentialId="before-wait",persisted=true)
            val sessions=MusicSessionManager(credentials,object: MusicRenewer {
                override suspend fun renew(access: MusicAccess)=MusicAccess("b".repeat(64),"Synthetic",clock+3_600_000,
                    renewal,credentialId="after-wait",persisted=true)
            },{NativeProbe(12,NativePage(rows,2))},{clock},io=Dispatchers.Unconfined)
            val queued=DuplicateCheckTask(context,api,credentials,sessions,hasherFactory={cache,progress->DuplicateAudioHasher(context,api,transfer(downloads),cache,progress)})
            val queuedId=queued.prepare();clock+=400_000;sessions.current()
            assertEquals("after-wait",credentials.value!!.credentialId)
            queued.execute(queuedId);assertEquals(DuplicatePhase.READY,queued.state.value.phase)
            assertEquals("after-wait",queued.state.value.report!!.credentialId)
            queued.discard()
        }finally{dir.deleteRecursively()}
    }
    @Test fun systemUidtJobHasSeparatePrivateNotificationAndCanRunFromVisibleActivity()=runBlocking<Unit> {
        if(android.os.Build.VERSION.SDK_INT<34) {
            val legacy=app.packageManager.getServiceInfo(ComponentName(app,LegacyDuplicateCheckService::class.java),0)
            assertFalse(legacy.exported)
            assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,legacy.foregroundServiceType)
            assertFalse(BackgroundTransport.usesUserInitiatedJobs(android.os.Build.VERSION.SDK_INT))
            val note=DuplicateCheckRuntime.notification(app,DuplicatesState(phase=DuplicatePhase.SCANNING,scanId="test",
                progress=DuplicateProgress("Synthetic check",1,2,track(1),1024),savedChecks=1))
            assertEquals(Notification.VISIBILITY_PRIVATE,note.visibility)
            assertEquals("Пауза",note.actions.single().title.toString())
            return@runBlocking
        }
        val scheduler=app.getSystemService(JobScheduler::class.java);val notifications=app.getSystemService(NotificationManager::class.java)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")).use{it.readBytes()}
        val id=UUID.randomUUID().toString();val info=DuplicateCheckRuntime.job(app,id)
        assertTrue(info.isUserInitiated);assertNotEquals(AudioExportRuntime.JOB_ID,info.id)
        val service=app.packageManager.getServiceInfo(ComponentName(app,DuplicateCheckJob::class.java),PackageManager.ComponentInfoFlags.of(0))
        assertFalse(service.exported);assertEquals("android.permission.BIND_JOB_SERVICE",service.permission)
        val note=DuplicateCheckRuntime.notification(app,DuplicatesState(phase=DuplicatePhase.SCANNING,scanId=id,
            progress=DuplicateProgress("Synthetic check",1,2,track(1),1024),savedChecks=1))
        assertEquals(Notification.VISIBILITY_PRIVATE,note.visibility);assertEquals("Пауза",note.actions.single().title.toString())
        assertNotNull(note.contentIntent);assertEquals(1,note.extras.getInt(Notification.EXTRA_PROGRESS))
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            scenario.onActivity{}
            try {
                notifications.cancel(DuplicateCheckRuntime.NOTIFICATION_ID)
                assertEquals(JobScheduler.RESULT_SUCCESS,withContext(Dispatchers.Main){scheduler.schedule(info)})
                withTimeout(20000){while(notifications.activeNotifications.none{it.id==DuplicateCheckRuntime.NOTIFICATION_ID})delay(50)}
                withTimeout(20000){while(scheduler.getPendingJob(DuplicateCheckRuntime.JOB_ID)!=null||DuplicateCheckRuntime.active.get())delay(50)}
            }finally{scheduler.cancel(DuplicateCheckRuntime.JOB_ID);notifications.cancel(DuplicateCheckRuntime.NOTIFICATION_ID)}
        }
    }
    @Test fun staleRegionFlagCanDownloadOnlyAfterFreshUnrestrictedExactIdAndExplicitRestrictionStillSkips()=runBlocking<Unit>(Dispatchers.IO) {
        val dir=File(app.cacheDir,"fresh-availability-download").apply{deleteRecursively();mkdirs()};val context=context(dir)
        val downloads=AtomicInteger();val tasks=(1..2).map{ExportLayout.task(track(it).copy(restricted=true,duration=0),listOf("Моя музыка","CI Region checks"),it)}
        val client=NetworkClients.api().newBuilder().addInterceptor{chain->
            val form=chain.request().body as FormBody;val id=(0 until form.size).first{form.name(it)=="audios"}.let{form.value(it)}
            val restricted=if(id=="12_2")",\"is_restricted\":true" else ""
            val body="""{"response":[{"owner_id":12,"id":${id.substringAfter('_')},"title":"Synthetic background song","artist":"Synthetic artist","duration":1,"url":"https://audio.userapi.com/synthetic.mp3"$restricted}]}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic").body(body.toResponseBody()).build()
        }.build()
        val api=VkAudioClient(client,observe={_,_->});val shared=SharedAudioStore(context);var saved: PublishedAudio?=null
        try{AudioExportStore(context).use{store->
            store.replace(tasks);store.status("QUEUED")
            val engine=AudioExportEngine(store,shared,File(context.cacheDir,"transfer"),api,transfer(downloads),
                credential={MusicAccess("a".repeat(64),"Synthetic")},observe={_,_->})
            engine.run{}
            saved=store.existing(tasks[0].key)
            assertEquals(1,store.snapshot().done);assertEquals(1,store.snapshot().failed);assertEquals(1,downloads.get())
            assertEquals(MusicError.UNAVAILABLE,store.snapshot().failures.single().error)
            assertTrue(shared.valid(saved!!));assertEquals(tasks[0].track.id,saved!!.track.id)
            assertEquals(1,saved!!.track.duration);assertFalse(saved!!.track.restricted)
            assertEquals(listOf(tasks[1].track),store.unavailableTracks())
        }}finally{saved?.let{context.contentResolver.delete(Uri.parse(it.uri),null,null)};dir.deleteRecursively()}
    }
}
