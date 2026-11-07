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
            until{!model.artists.value.busy&&model.artists.value.error!=null}
            assertEquals(MusicError.TIMEOUT,model.artists.value.error)
            assertEquals(listOf(artist),model.artists.value.artists);assertFalse(model.artists.value.loaded)
            inst.runOnMainSync{model.selectArtist(artist)}
            until{!model.artists.value.busy&&model.artists.value.details!=null}
            inst.runOnMainSync{model.showArtists(true)}
            until{!model.artists.value.busy&&model.artists.value.loaded}
            assertEquals(2,requests);assertNull(model.artists.value.error)
        }finally{inst.runOnMainSync{vmStore.clear()};vault.delete()}
    }
    @Test fun catalogRefreshCompletesWhileArtistReadWaitsAndLeavingCancelsOnlyThatReader()=runBlocking {
        val inst=InstrumentationRegistry.getInstrumentation()
        val app=inst.targetContext.applicationContext as android.app.Application
        val vault=MusicAccessStore(app);val store=ViewModelStore()
        val entered=CompletableDeferred<Unit>();val cancelled=CompletableDeferred<Unit>();var catalogReads=0
        val api=object: MusicApi {
            override suspend fun library(access: MusicAccess)=NativeProbe(12,NativePage(emptyList(),0))
            override suspend fun catalog(access: MusicAccess,owner: Long): NativeCatalog {catalogReads++;return NativeCatalog(emptyList(),0)}
            override suspend fun playlist(access: MusicAccess,list: NativePlaylist): NativePage=error("No playlist")
            override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("No audio request")
            override suspend fun followedArtists(access: MusicAccess,owner: Long,progress:(List<MusicArtist>,Int)->Unit): List<MusicArtist> {
                entered.complete(Unit)
                try{awaitCancellation()}finally{cancelled.complete(Unit)}
            }
        }
        lateinit var model: NativeMusicViewModel
        suspend fun until(check:()->Boolean){withTimeout(10_000){while(!check())delay(20)}}
        try {
            vault.delete()
            inst.runOnMainSync{model=ViewModelProvider(store,object: ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T: androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T=NativeMusicViewModel(app,api) as T
            })[NativeMusicViewModel::class.java]}
            until{model.state.value.initialized}
            inst.runOnMainSync{model.configure("vk1.a."+"SYNTHETIC".repeat(12),"CI/1")}
            until{model.state.value.probe!=null&&!model.state.value.busy}
            inst.runOnMainSync{model.showArtists()};withTimeout(10_000){entered.await()}
            assertTrue(model.artists.value.busy);assertFalse(model.state.value.busy)
            val before=catalogReads
            inst.runOnMainSync{model.refreshCatalog()}
            until{catalogReads>before&&!model.state.value.busy}
            assertTrue(model.artists.value.busy)
            inst.runOnMainSync{model.pauseArtists()};withTimeout(10_000){cancelled.await()}
            assertFalse(model.artists.value.busy);assertNotNull(model.state.value.catalog)
        }finally{inst.runOnMainSync{store.clear()};vault.delete()}
    }

}
