package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MusicAvailabilityControllerTest {
    private val access=MusicAccess("a".repeat(64),"CI",accountId=12)
    private val item=NativeTrack("12_5","Test","Artist",0,restricted=true)
    private class Api: MusicApi {
        var resolveCall: suspend ()->Unit={}
        override suspend fun library(access: MusicAccess)=NativeProbe(12,NativePage(emptyList(),0))
        override suspend fun resolve(access: MusicAccess,track: NativeTrack): ResolvedAudio {
            resolveCall()
            return ResolvedAudio(track,"https://audio.userapi.com/test.mp3")
        }
        override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("No audio transfer")
        override suspend fun catalog(access: MusicAccess,owner: Long): NativeCatalog=error("Not a catalog read")
        override suspend fun playlist(access: MusicAccess,list: NativePlaylist): NativePage=error("Not a playlist read")
    }
    private fun TestScope.controller(api: Api,read: suspend ()->MusicAccess={access},
        checked: suspend(MusicAccess)->MusicAccess={it})=
        MusicAvailabilityController(backgroundScope,api,read,checked,{listOf(item)},{false},pageDelayMs=0,io=UnconfinedTestDispatcher(testScheduler))

    @Test fun pausingAvailabilityDoesNotCancelAnIndependentCatalogJob()=runTest {
        val entered=CompletableDeferred<Unit>()
        val gate=CompletableDeferred<Unit>()
        val api=Api()
        api.resolveCall={entered.complete(Unit);gate.await()}
        val catalogJob=backgroundScope.launch {delay(100);entered.complete(Unit)}
        val reader=controller(api)
        reader.show()
        reader.check()
        withTimeout(10_000){entered.await()}
        assertTrue(reader.state.value.running)
        reader.pause()
        assertFalse(reader.state.value.running)
        gate.complete(Unit)
        catalogJob.join()
        assertTrue(catalogJob.isCompleted)
        assertTrue(reader.state.value.results.isEmpty())
    }

    @Test fun staleResponseCannotOverwriteNewCheckAfterPause()=runTest {
        val stale=CompletableDeferred<Unit>()
        val fresh=CompletableDeferred<Unit>()
        val firstStarted=CompletableDeferred<Unit>()
        var calls=0
        val api=Api()
        api.resolveCall={
            if(++calls==1) {
                firstStarted.complete(Unit)
                withContext(NonCancellable){stale.await()}
            }else fresh.await()
        }
        val reader=controller(api)
        reader.show();reader.check()
        withTimeout(10_000){firstStarted.await()}
        reader.pause();reader.check()
        withTimeout(10_000){while(calls!=2)yield()}
        stale.complete(Unit)
        assertTrue(reader.state.value.running)
        assertTrue(reader.state.value.results.isEmpty())
        fresh.complete(Unit)
        withTimeout(10_000){while(reader.state.value.running)yield()}
        assertEquals(1,reader.state.value.results.size)
        assertTrue(reader.state.value.results.single().linkAvailable)
    }

    @Test fun independentLoginRejectsReadBeforeShowingResults()=runTest {
        var current=access
        val waiting=CompletableDeferred<Unit>()
        val api=Api()
        api.resolveCall={waiting.await()}
        val reader=controller(api,{current}){initial->
            MusicReadIdentity(initial).require(current)
            current
        }
        reader.show();reader.check()
        runCurrent()
        current=MusicAccess("b".repeat(64),"CI",accountId=12,readSessionId="new-login")
        waiting.complete(Unit)
        withTimeout(10_000){while(reader.state.value.running)yield()}
        assertTrue(reader.state.value.results.isEmpty())
        assertNotNull(reader.state.value.message)
    }
}
