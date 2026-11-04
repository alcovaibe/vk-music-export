package app.alcovaibe.vkmusicexport.updates

import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class UpdateControllerTest {
    private val release=UpdateRelease("0.6.1",1049,36,UpdateAsset(100,"a".repeat(64)))
    private class Preferences: UpdatePreferences {
        override var lastAttempt=100_000_000L
        override var deferred=0
    }
    private suspend fun UpdateController.waitFor(status: UpdateStatus)=withTimeout(5000){state.first{it.initialized&&it.status==status}}
    private suspend fun UpdateController.ready(){withTimeout(5000){state.first{it.initialized}}}
    private fun service(check: suspend ()->UpdateRelease={release},download: suspend ((Long,Long)->Unit)->File={error("No transfer")},verify: suspend ()->Unit={},restoring: suspend ()->Pair<UpdateRelease,File>?={null})=object: UpdateService {
        override suspend fun latest()=check()
        override suspend fun download(release: UpdateRelease,progress: (Long,Long)->Unit)=download(progress)
        override suspend fun verify(file: File,release: UpdateRelease)=verify()
        override suspend fun restore()=restoring()
    }
    @Test fun automaticIntervalManualCooldownAndRollback()=runBlocking {
        val prefs=Preferences().apply{lastAttempt=0};var wall=100_000_000L;var elapsed=1000L;var calls=0
        val c=UpdateController(this,prefs,service(check={calls++;release}),1048,37,{wall},{elapsed})
        c.ready();c.resume();c.waitFor(UpdateStatus.AVAILABLE);assertEquals(1,calls)
        c.defer();c.pause();c.resume();assertEquals(1,calls)
        c.check();c.waitFor(UpdateStatus.AVAILABLE);assertEquals(2,calls)
        c.check();assertEquals(2,calls)
        c.defer();wall+=86400001;c.pause();c.resume();c.waitFor(UpdateStatus.IDLE)
        withTimeout(5000){while(calls<3)yield()};assertEquals(3,calls)
        c.pause();wall-=86400002;c.resume();assertEquals(wall,prefs.lastAttempt);assertEquals(3,calls)
        elapsed+=5001;c.check();c.waitFor(UpdateStatus.AVAILABLE);assertEquals(4,calls);c.close()
    }
    @Test fun publicChecksNeedNoCredentialAndAutomaticFailuresAreSilent()=runBlocking {
        var calls=0
        val c=UpdateController(this,Preferences().apply{lastAttempt=0},service(check={calls++;throw UpdateProblem("ACCESS")}),1048,37,{100_000_000L},{1000L})
        c.ready();c.resume()
        withTimeout(5000){while(calls<1)yield()}
        c.waitFor(UpdateStatus.IDLE);assertEquals(1,calls)
        c.check();c.waitFor(UpdateStatus.ACCESS);assertEquals(2,calls);c.close()
    }
    @Test fun equalOlderAndIncompatibleReleasesAreNotOffered()=runBlocking {
        for(r in listOf(release.copy(code=1048),release.copy(code=1047),release.copy(minSdk=38))) {
            val c=UpdateController(this,Preferences(),service(check={r}),1048,37,{100_000_000L},{1000L})
            c.ready();c.resume();c.check();c.waitFor(UpdateStatus.CURRENT);assertNull(c.state.value.release);c.close()
        }
    }
    @Test fun pendingMetadataCannotReopenAResultAfterPause()=runBlocking {
        val pending=CompletableDeferred<UpdateRelease>();val entered=CompletableDeferred<Unit>()
        val c=UpdateController(this,Preferences(),service(check={entered.complete(Unit);withContext(NonCancellable){pending.await()}}),1048,37,{100_000_000L},{1000L})
        c.ready();c.resume();c.check();entered.await();c.pause();pending.complete(release);yield()
        assertEquals(UpdateStatus.IDLE,c.state.value.status);c.resume();assertEquals(UpdateStatus.IDLE,c.state.value.status);c.close()
    }
    @Test fun downloadSurvivesPauseAndReadyApkIsReverifiedOnEveryInstallTap()=runBlocking {
        val pending=CompletableDeferred<File>();var verifies=0
        val file=File.createTempFile("update-fixture",".apk")
        val c=UpdateController(this,Preferences(),service(download={p->p(50,100);pending.await()},verify={verifies++}),1048,37,{100_000_000L},{1000L})
        try {
            c.ready();c.resume();c.check();c.waitFor(UpdateStatus.AVAILABLE);c.download()
            withTimeout(5000){c.state.first{it.received==50L}}
            c.pause();pending.complete(file);c.waitFor(UpdateStatus.READY);assertNull(c.verifiedApk())
            c.resume();assertEquals(file,c.verifiedApk());assertEquals(file,c.verifiedApk());assertEquals(2,verifies)
            c.defer();assertEquals(UpdateStatus.IDLE,c.state.value.status)
        }finally{c.close();file.delete()}
    }
    @Test fun cancellationIgnoresLateProgressAndClearsTheOfferedRelease()=runBlocking {
        var progress: ((Long,Long)->Unit)?=null
        val c=UpdateController(this,Preferences(),service(download={p->progress=p;awaitCancellation()}),1048,37,{100_000_000L},{1000L})
        c.ready();c.resume();c.check();c.waitFor(UpdateStatus.AVAILABLE);c.download()
        withTimeout(5000){while(progress==null)yield()};c.defer();progress!!(99,100)
        assertEquals(UpdateStatus.IDLE,c.state.value.status);assertEquals(0L,c.state.value.received)
        assertNull(c.state.value.release);c.close()
    }
    @Test fun cancelledTransferDeletesAFileReturnedByANonCooperativeService()=runBlocking {
        var completion: Continuation<File>?=null
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val file=File.createTempFile("late-update",".apk")
        val c=UpdateController(scope,Preferences(),service(download={suspendCoroutine{completion=it}}),1048,37,{100_000_000L},{1000L})
        try {
            c.ready();c.resume();c.check();c.waitFor(UpdateStatus.AVAILABLE);c.download()
            withTimeout(5000){while(completion==null)yield()}
            c.cancel();completion!!.resume(file)
            withTimeout(5000){while(file.exists())delay(5)}
            assertEquals(UpdateStatus.IDLE,c.state.value.status);assertNull(c.state.value.apk)
        }finally{c.close();scope.cancel();file.delete()}
    }
    @Test fun internalDownloadTimeoutLeavesAnActionableError()=runBlocking {
        val c=UpdateController(this,Preferences(),service(download={withTimeout(1){awaitCancellation()}}),1048,37,{100_000_000L},{1000L})
        try {
            c.ready();c.resume();c.check();c.waitFor(UpdateStatus.AVAILABLE);c.download()
            c.waitFor(UpdateStatus.ERROR);assertNull(c.state.value.apk)
        }finally{c.close()}
    }
    @Test fun verifiedCheckpointRestoresReadyWithoutMetadataOrAnotherDownload()=runBlocking {
        val file=File.createTempFile("restored-update",".apk");var requests=0;var verified=0
        val c=UpdateController(this,Preferences(),service(check={requests++;release},verify={verified++},restoring={release to file}),1048,37)
        try {
            c.ready();c.resume();c.waitFor(UpdateStatus.READY)
            assertEquals(file,c.verifiedApk());assertEquals(0,requests);assertEquals(1,verified)
            c.close();assertTrue(file.exists())
        }finally{c.close();file.delete()}
    }
    @Test fun cancellingDuringRestoreCannotResurrectTheDownloadedApk()=runBlocking {
        val entered=CompletableDeferred<Unit>();val complete=CompletableDeferred<Pair<UpdateRelease,File>?>()
        val file=File.createTempFile("cancelled-restore",".apk")
        val c=UpdateController(this,Preferences(),service(restoring={entered.complete(Unit);complete.await()}),1048,37)
        try {
            entered.await();c.cancel();complete.complete(release to file)
            c.ready();assertEquals(UpdateStatus.IDLE,c.state.value.status);assertFalse(file.exists())
        }finally{c.close();file.delete()}
    }

}
