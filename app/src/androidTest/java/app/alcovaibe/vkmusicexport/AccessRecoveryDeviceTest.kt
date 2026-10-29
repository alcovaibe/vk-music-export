package app.alcovaibe.vkmusicexport

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AccessRecoveryDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private suspend fun await(predicate:()->Boolean){withTimeout(15000){while(!predicate())delay(25)}}
    private fun application(root: File)=object: Application() {
        init{attachBaseContext(instrumentation.targetContext.applicationContext)}
        override fun getNoBackupFilesDir()=File(root,"private").apply{mkdirs()}
        override fun getFilesDir()=File(root,"files").apply{mkdirs()}
        override fun getCacheDir()=File(root,"cache").apply{mkdirs()}
    }
    private fun model(app: Application,api: MusicApi,holder: ViewModelStore): NativeMusicViewModel {
        lateinit var result: NativeMusicViewModel
        instrumentation.runOnMainSync{result=ViewModelProvider(holder,object: ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T: androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T=NativeMusicViewModel(app,api) as T
        })[NativeMusicViewModel::class.java]}
        return result
    }
    private fun api(probe: suspend(MusicAccess)->NativeProbe)=object: MusicApi {
        override suspend fun library(access: MusicAccess)=probe(access)
        override suspend fun catalog(access: MusicAccess,owner: Long)=NativeCatalog(emptyList(),0)
        override suspend fun playlist(access: MusicAccess,list: NativePlaylist)=error("Unexpected playlist request")
        override suspend fun media(access: MusicAccess,track: NativeTrack)=error("Unexpected media request")
    }
    @Test fun rejectedOrCancelledManualCandidatePreservesEncryptedOldAccess()=runBlocking<Unit> {
        val root=File(instrumentation.targetContext.cacheDir,"audit-manual-access").apply{deleteRecursively();mkdirs()}
        val app=application(root);val holder=ViewModelStore();val vault=MusicAccessStore(app)
        val old=MusicAccess("a".repeat(64),"CI/1",accountId=12)
        val started=CompletableDeferred<Unit>();var waiting=false
        withContext(Dispatchers.IO){vault.save(old)}
        val sealed=withContext(Dispatchers.IO){File(app.noBackupFilesDir,"native-music-access.bin").readBytes()}
        val model=model(app,api{
            if(waiting){started.complete(Unit);awaitCancellation()}
            throw MusicFailure(MusicError.ACCESS)
        },holder)
        try {
            await{model.state.value.initialized}
            instrumentation.runOnMainSync{model.configure("b".repeat(64),"CI/1")}
            await{!model.state.value.busy&&model.state.value.error==MusicError.ACCESS}
            assertArrayEquals(sealed,withContext(Dispatchers.IO){File(app.noBackupFilesDir,"native-music-access.bin").readBytes()})
            assertEquals(old.credentialId,withContext(Dispatchers.IO){vault.read()!!.credentialId})
            waiting=true;instrumentation.runOnMainSync{model.configure("c".repeat(64),"CI/1")}
            withTimeout(15000){started.await()};instrumentation.runOnMainSync{model.cancel()}
            await{!model.state.value.busy}
            assertArrayEquals(sealed,withContext(Dispatchers.IO){File(app.noBackupFilesDir,"native-music-access.bin").readBytes()})
            assertEquals(old.credentialId,withContext(Dispatchers.IO){vault.read()!!.credentialId})
        }finally{instrumentation.runOnMainSync{holder.clear()};root.deleteRecursively()}
    }
    @Test fun damagedEnvelopeAllowsFreshManualAccessAndExplicitLocalReset()=runBlocking<Unit> {
        val root=File(instrumentation.targetContext.cacheDir,"audit-damaged-access").apply{deleteRecursively();mkdirs()}
        val app=application(root);val holder=ViewModelStore();val vault=MusicAccessStore(app)
        withContext(Dispatchers.IO){File(app.noBackupFilesDir,"native-music-access.bin").writeBytes(ByteArray(64){7})}
        val model=model(app,api{NativeProbe(12,NativePage(emptyList(),0))},holder)
        try {
            await{model.state.value.initialized};assertTrue(model.state.value.accessDamaged)
            instrumentation.runOnMainSync{model.configure("d".repeat(64),"CI/1")}
            await{!model.state.value.busy&&model.state.value.configured}
            assertFalse(model.state.value.accessDamaged)
            assertEquals(12L,withContext(Dispatchers.IO){vault.read()!!.accountId})
            instrumentation.runOnMainSync{model.forget()}
            await{!model.state.value.busy&&!model.state.value.configured}
            assertNull(withContext(Dispatchers.IO){vault.read()})
        }finally{instrumentation.runOnMainSync{holder.clear()};root.deleteRecursively()}
    }
}
