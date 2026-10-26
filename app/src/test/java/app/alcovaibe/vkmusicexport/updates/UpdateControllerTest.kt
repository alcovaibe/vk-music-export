package app.alcovaibe.vkmusicexport.updates

import java.io.File
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
    private fun service(check: suspend ()->UpdateRelease={release},download: suspend ((Long,Long)->Unit)->File={error("No transfer")},verify: suspend ()->Unit={})=object: UpdateService {
        override suspend fun latest()=check()
        override suspend fun download(release: UpdateRelease,progress: (Long,Long)->Unit)=download(progress)
        override suspend fun verify(file: File,release: UpdateRelease)=verify()
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
}
