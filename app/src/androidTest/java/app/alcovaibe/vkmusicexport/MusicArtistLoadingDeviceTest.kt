package app.alcovaibe.vkmusicexport

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MusicArtistLoadingDeviceTest {
    @Test fun stalledSubscriptionRequestStopsSpinnerKeepsVerifiedArtistsAndCanRetry()=runBlocking {
        val inst=InstrumentationRegistry.getInstrumentation()
        val app=inst.targetContext.applicationContext as android.app.Application
        val vault=MusicAccessStore(app);val vmStore=ViewModelStore();val artist=MusicArtist("1","Подписка")
        var requests=0
        val api=object: MusicApi {
            override suspend fun library(access: MusicAccess)=NativeProbe(12,NativePage(emptyList(),0))
            override suspend fun catalog(access: MusicAccess,owner: Long)=NativeCatalog(emptyList(),0)
            override suspend fun playlist(access: MusicAccess,list: NativePlaylist): NativePage=error("No playlist")
            override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("No audio request")
            override suspend fun followedArtists(access: MusicAccess,owner: Long,progress:(List<MusicArtist>,Int)->Unit): List<MusicArtist> {
                requests++;progress(listOf(artist),1)
                if(requests==1)awaitCancellation()
                return listOf(artist)
            }
            override suspend fun artistDetails(access: MusicAccess,artist: MusicArtist)=MusicArtistDetails(artist,NativePage(emptyList(),0),emptyList())
        }
        lateinit var model: NativeMusicViewModel
        suspend fun until(check:()->Boolean){withTimeout(10_000){while(!check())delay(20)}}
        try {
            vault.delete()
            inst.runOnMainSync{model=ViewModelProvider(vmStore,object: ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T: androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T=NativeMusicViewModel(app,api,artistTimeoutMs=500) as T
            })[NativeMusicViewModel::class.java]}
            until{model.state.value.initialized}
            inst.runOnMainSync{model.configure("vk1.a."+"SYNTHETIC".repeat(12),"CI/1")}
            until{!model.state.value.busy&&model.state.value.probe!=null}
            inst.runOnMainSync{model.showArtists()}
            until{!model.state.value.busy&&model.artists.value.error!=null}
            assertEquals(MusicError.TIMEOUT,model.artists.value.error)
            assertEquals(listOf(artist),model.artists.value.artists);assertFalse(model.artists.value.loaded)
            inst.runOnMainSync{model.selectArtist(artist)}
            until{!model.state.value.busy&&model.artists.value.details!=null}
            inst.runOnMainSync{model.showArtists(true)}
            until{!model.state.value.busy&&model.artists.value.loaded}
            assertEquals(2,requests);assertNull(model.artists.value.error)
        }finally{inst.runOnMainSync{vmStore.clear()};vault.delete()}
    }
}
