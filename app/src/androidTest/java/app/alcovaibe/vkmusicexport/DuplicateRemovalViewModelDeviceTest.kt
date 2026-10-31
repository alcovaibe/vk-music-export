package app.alcovaibe.vkmusicexport

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DuplicateRemovalViewModelDeviceTest {
    @Test fun readyResultAcceptsRemovalDuringJobCleanupAndConfirmedActionDeletesOneRecord()=runBlocking {
        val inst=InstrumentationRegistry.getInstrumentation();val app=inst.targetContext.applicationContext as android.app.Application
        val vault=MusicAccessStore(app);val holder=ViewModelStore()
        val tracks=(1..2).map{NativeTrack("12_${99660+it}","CI duplicate","CI",1)}.toMutableList()
        var deleted=0;var downloads=0
        val api=object: MusicApi {
            override suspend fun library(access: MusicAccess)=NativeProbe(12,NativePage(tracks.toList(),tracks.size))
            override suspend fun catalog(access: MusicAccess,owner: Long)=NativeCatalog(emptyList(),0)
            override suspend fun playlist(access: MusicAccess,list: NativePlaylist)=error("Unused")
            override suspend fun media(access: MusicAccess,track: NativeTrack)="https://audio.userapi.com/${track.id}.mp3"
            override suspend fun removeLibraryTrack(access: MusicAccess,owner: Long,track: NativeTrack){assertEquals(12L,owner);assertEquals(tracks[1],track);deleted++;tracks.remove(track)}
        }
        val bytes=inst.context.assets.open("native-audio/tone.mp3").use{it.readBytes()}
        val transfer=AudioTransfer(NetworkClients.media().newBuilder().addInterceptor{chain->
            downloads++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
                .header("Content-Type","audio/mpeg").body(bytes.toResponseBody()).build()
        }.build(),observe={_,_->})
        lateinit var model: NativeMusicViewModel
        suspend fun until(check:()->Boolean){withTimeout(20_000){while(!check())delay(20)}}
        try {
            withContext(Dispatchers.IO){AudioExportRuntime.clear(app);vault.delete()}
            inst.runOnMainSync{model=ViewModelProvider(holder,object: ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T: androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T=NativeMusicViewModel(app,api,duplicateTransfer=transfer) as T
            })[NativeMusicViewModel::class.java]}
            until{model.state.value.initialized}
            inst.runOnMainSync{model.setForeground(true);model.configure("vk1.a."+"SYNTHETIC".repeat(12),"CI/1")}
            until{!model.state.value.busy&&model.state.value.probe!=null}
            val access=withContext(Dispatchers.IO){vault.read()!!}
            val signature=withContext(Dispatchers.IO){DuplicateAudioHasher(app,api,transfer).use{it.signature(access,tracks[0])}}
            val row=MusicDuplicate(tracks[0],tracks[1],signature)
            DuplicateCheckRuntime.mutable.value=DuplicatesState(true,DuplicatePhase.READY,"ci-ready",
                DuplicateReport(12,access.credentialId,2,2,listOf(row),emptyList()))
            DuplicateCheckRuntime.active.set(true)
            inst.runOnMainSync{model.requestDuplicateRemoval(row)}
            assertNotNull(model.duplicates.value.consent);assertEquals(0,deleted)
            DuplicateCheckRuntime.active.set(false)
            inst.runOnMainSync{model.confirmDuplicateRemoval()}
            until{!model.state.value.busy&&model.duplicates.value.phase!=DuplicatePhase.DELETING}
            assertEquals(DuplicatePhase.READY,model.duplicates.value.phase)
            assertEquals(1,deleted);assertEquals(1,tracks.size);assertEquals(3,downloads)
            assertEquals(1,model.duplicates.value.removed)
            inst.runOnMainSync{model.requestDuplicateRemoval(row.copy(status=DuplicateStatus.DELETED));model.confirmDuplicateRemoval()}
            assertEquals(1,deleted)
        }finally {
            DuplicateCheckRuntime.active.set(false)
            inst.runOnMainSync{holder.clear()}
            DuplicateCheckRuntime.mutable.value=DuplicatesState()
            withContext(Dispatchers.IO){vault.delete()}
        }
    }
}
