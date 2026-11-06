package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FriendAvailabilityReaderTest {
    private val access=MusicAccess("a".repeat(64),"CI",accountId=12)
    private class Api: MusicApi {
        val calls=mutableListOf<Long>();val cancelled=mutableListOf<Long>()
        var probe: suspend (Long)->Unit={}
        override suspend fun probeFriendAudio(access: MusicAccess,owner: Long) {
            calls.add(owner)
            try{probe(owner)}catch(e: CancellationException){cancelled.add(owner);throw e}
        }
        override suspend fun library(access: MusicAccess)=error("Not a library read")
        override suspend fun catalog(access: MusicAccess,owner: Long): NativeCatalog=error("No catalog read")
        override suspend fun playlist(access: MusicAccess,list: NativePlaylist): NativePage=error("No playlist read")
        override suspend fun media(access: MusicAccess,track: NativeTrack)=error("No media transfer")
    }
    private class Fixture(val scope: CoroutineScope,initial: MusicAccess) {
        val api=Api();var now=1000L;val cache=FriendAvailabilityCache({now})
        var current: MusicAccess?=initial;var changed=0;var busy=false
        val results=mutableListOf<Pair<Long,FriendAudioAccess>>()
        val reader=FriendAvailabilityReader(scope,api,cache,
            checked={before->
                val after=current?:throw MusicFailure(MusicError.ACCESS)
                if(after.readSessionId!=before.readSessionId)throw MusicReadChanged()
                after
            },blocked={busy},publish={id,status->results.add(id to status)},changedSession={changed++})
        init{reader.bind(initial)}
    }
    @Test fun hiddenScreenDoesNotProbeAndPauseCancelsBothWorkersBeforeAnotherRequest()=runTest {
        val f=Fixture(backgroundScope,access)
        f.api.probe={awaitCancellation()}
        f.reader.visible(listOf(34,35,36));runCurrent();assertTrue(f.api.calls.isEmpty())
        f.reader.setActive(true);runCurrent();assertEquals(listOf(34L,35L),f.api.calls)
        f.reader.setActive(false);runCurrent();assertEquals(setOf(34L,35L),f.api.cancelled.toSet())
        advanceTimeBy(60_000);runCurrent();assertEquals(2,f.api.calls.size);assertTrue(f.results.isEmpty())
    }
    @Test fun returnReusesFreshResultsAndChecksOnlyVisibleCardsWithBoundedConcurrency()=runTest {
        val f=Fixture(backgroundScope,access)
        f.reader.visible(listOf(34,35));f.reader.setActive(true);runCurrent()
        assertEquals(listOf(34L,35L),f.api.calls)
        f.reader.setActive(false);f.reader.setActive(true);runCurrent()
        assertEquals(2,f.api.calls.size)
        f.reader.visible(listOf(35,36));runCurrent();assertEquals(listOf(34L,35L,36L),f.api.calls)
        f.reader.setActive(false);f.now+=15*60_000;f.reader.setActive(true);runCurrent()
        assertEquals(listOf(35L,36L),f.api.calls.takeLast(2))
    }
    @Test fun cancelledLateSuccessAndDenialCannotPublishOrPoisonTheNextSessionCache()=runTest {
        for(denied in listOf(false,true)) {
            val f=Fixture(backgroundScope,access);val release=CompletableDeferred<Unit>()
            f.api.probe={withContext(NonCancellable){release.await()};if(denied)throw MusicFailure(MusicError.DENIED,201)}
            f.reader.visible(listOf(34));f.reader.setActive(true);runCurrent()
            f.reader.clear();f.current=access.withSession(readSessionId="new-login")
            f.reader.bind(f.current!!);f.reader.visible(listOf(35));f.reader.setActive(false)
            release.complete(Unit);runCurrent()
            assertTrue(f.results.isEmpty());assertNull(f.cache.get(34));assertEquals(0,f.changed)
        }
    }
    @Test fun independentLoginAndLogoutInvalidateEvenWhenTheOldRequestFails()=runTest {
        for(logout in listOf(false,true)) {
            val f=Fixture(backgroundScope,access);val release=CompletableDeferred<Unit>()
            f.api.probe={release.await();throw MusicFailure(MusicError.DENIED,201)}
            f.reader.visible(listOf(34));f.reader.setActive(true);runCurrent()
            f.current=if(logout)null else access.withSession(readSessionId="independent")
            release.complete(Unit);runCurrent()
            assertEquals(1,f.changed);assertTrue(f.results.isEmpty());assertNull(f.cache.get(34))
        }
    }
    @Test fun verifiedRenewalContinuesButBusyAndHiddenCardsNeverStartNewProbes()=runTest {
        val f=Fixture(backgroundScope,access);val release=CompletableDeferred<Unit>()
        f.busy=true;f.reader.visible(listOf(34));f.reader.setActive(true);runCurrent()
        assertTrue(f.api.calls.isEmpty())
        f.busy=false;f.api.probe={release.await()};advanceTimeBy(151);runCurrent()
        assertEquals(listOf(34L),f.api.calls)
        f.current=MusicAccess("b".repeat(64),"CI",accountId=12,readSessionId=access.readSessionId)
        release.complete(Unit);runCurrent();assertEquals(listOf(34L to FriendAudioAccess.AVAILABLE),f.results)
        f.reader.visible(emptyList());runCurrent();assertEquals(1,f.api.calls.size)
    }
    @Test fun pausingTheReaderCancelsTheActualOkHttpCall()=runBlocking {
        val entered=java.util.concurrent.CountDownLatch(1)
        val cancelled=java.util.concurrent.CountDownLatch(1)
        val client=NetworkClients.api().newBuilder().addInterceptor{chain->
            entered.countDown()
            val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
            while(!chain.call().isCanceled()&&System.nanoTime()<deadline)Thread.sleep(5)
            if(chain.call().isCanceled())cancelled.countDown()
            throw java.io.IOException("Synthetic blocked request")
        }.build()
        val results=mutableListOf<Long>()
        val reader=FriendAvailabilityReader(this,VkAudioClient(client,observe={_,_->}),FriendAvailabilityCache(),
            checked={it},blocked={false},publish={id,_->results.add(id)},changedSession={fail("Cancellation is not a new login")})
        try {
            reader.bind(access);reader.visible(listOf(34));reader.setActive(true)
            assertTrue(withContext(Dispatchers.IO){entered.await(5,java.util.concurrent.TimeUnit.SECONDS)})
            reader.setActive(false)
            assertTrue(withContext(Dispatchers.IO){cancelled.await(5,java.util.concurrent.TimeUnit.SECONDS)})
            assertTrue(results.isEmpty())
        }finally{reader.clear();client.dispatcher.executorService.shutdown()}
    }

    @Test fun aRateLimitStopsThePassWithoutInventingDenialsForUnrequestedCards()=runTest {
        val f=Fixture(backgroundScope,access)
        f.api.probe={throw MusicFailure(MusicError.RATE_LIMIT)}
        f.reader.visible(listOf(34,35,36));f.reader.setActive(true);runCurrent()
        assertEquals(listOf(34L),f.api.calls)
        assertEquals(listOf(34L to FriendAudioAccess.CHECK_FAILED),f.results)
        assertNull(f.cache.get(34));assertNull(f.cache.get(35))
    }
}
