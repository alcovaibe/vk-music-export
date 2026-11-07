package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MusicAuthCoordinatorTest {
    private val token="a".repeat(64)
    private val candidate=MusicAccess(token,"CI")
    private inner class Fixture(scope: TestScope) {
        val state=MutableStateFlow(NativeMusicState(initialized=true,configured=true))
        val foreground=MutableStateFlow(true)
        val events=mutableListOf<String>()
        val saved=mutableListOf<MusicAccess>()
        var failure: Exception?=null
        var generation=0
        var job: Job?=null
        var verifyRead: suspend(MusicAccess)->NativeProbe={NativeProbe(12,NativePage(emptyList(),0))}
        var pollCheck: suspend()->DeviceCheck={DeviceCheck.Approved(candidate)}
        var validations=0
        val coordinator=MusicAuthCoordinator(state,
            object: DeviceAuthApi {
                override suspend fun begin(deviceName: String)=DeviceSession("anonymous","hash","https://vk.com/activate",60_000)
                override suspend fun poll(session: DeviceSession)=pollCheck()
                override suspend fun validate(session: DeviceSession,code: DeviceConfirmationCode): DeviceValidation {
                    validations++;return DeviceValidation.ACCEPTED
                }
            },
            object: MusicApi {
                override suspend fun library(access: MusicAccess)=verifyRead(access)
                override suspend fun catalog(access: MusicAccess,owner: Long)=error("No catalog")
                override suspend fun playlist(access: MusicAccess,list: NativePlaylist)=error("No playlist")
                override suspend fun media(access: MusicAccess,track: NativeTrack)=error("No media")
            },foreground,{0L},100,{_,_->true},
            operation={stage,block->
                val g=++generation
                state.update{it.copy(busy=true,stage=stage)}
                job=scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
                    try{block(g)}
                    catch(e: CancellationException){throw e}
                    catch(e: Exception){failure=e}
                    finally{coordinatorReset();state.update{it.copy(busy=false)}}
                }
            },
            active={g->currentCoroutineContext().ensureActive();if(g!=generation)throw CancellationException()},
            isCurrent={it==generation},
            save={saved.add(it);events.add("save")},
            reload={events.add("reload")},
            acceptProbe={_,_,_->events.add("publish")},
            stopOnline={events.add("stop")},
            deviceName={"CI device"})
        private fun coordinatorReset(){coordinator.reset()}
        fun cancel(){generation++;job?.cancel();coordinator.reset()}
    }

    @Test fun replacementIsVerifiedBeforeCommitAndThenPublished()=runTest {
        val f=Fixture(this)
        f.verifyRead={f.events.add("verify");NativeProbe(12,NativePage(emptyList(),0))}
        f.coordinator.configure(token,"CI")
        assertEquals(listOf("verify","stop","save","reload","publish"),f.events)
        assertEquals(12L,f.saved.single().accountId)
        assertNull(f.failure)
    }

    @Test fun failedMusicCheckPreservesExistingCredential()=runTest {
        val f=Fixture(this)
        f.verifyRead={throw MusicFailure(MusicError.ACCESS)}
        f.coordinator.configure(token,"CI")
        assertTrue(f.saved.isEmpty());assertTrue(f.events.isEmpty())
        assertTrue(f.state.value.configured);assertTrue(f.failure is MusicFailure)
    }

    @Test fun lateNonCooperativeVerificationCannotCommitAfterCancellation()=runTest {
        val f=Fixture(this)
        val result=CompletableDeferred<NativeProbe>()
        f.verifyRead={withContext(NonCancellable){result.await()}}
        f.coordinator.configure(token,"CI")
        f.cancel()
        result.complete(NativeProbe(12,NativePage(emptyList(),0)))
        runCurrent()
        assertTrue(f.saved.isEmpty());assertTrue(f.events.isEmpty())
    }

    @Test fun browserDispatchDoesNotProveLoginAndBackgroundPausesPolling()=runTest {
        val f=Fixture(this)
        var polls=0
        f.pollCheck={polls++;DeviceCheck.Approved(candidate)}
        f.foreground.value=false
        f.coordinator.authenticate()
        assertEquals(DeviceAuthPhase.READY,f.state.value.authPhase)
        f.coordinator.openBrowser{true}
        assertTrue(f.saved.isEmpty());assertEquals(0,polls)
        f.foreground.value=true
        runCurrent()
        assertEquals(1,polls);assertEquals(12L,f.saved.single().accountId)
        assertEquals(DeviceAuthPhase.CONNECTED,f.state.value.authPhase)
    }

    @Test fun cancelledRequestCannotReuseBufferedConfirmationCode()=runTest {
        val f=Fixture(this)
        f.pollCheck={DeviceCheck.VerificationRequired}
        f.coordinator.authenticate()
        f.coordinator.openBrowser{true}
        runCurrent()
        assertEquals(DeviceAuthPhase.ENTER_CODE,f.state.value.authPhase)
        f.foreground.value=false
        f.coordinator.submitAuthCode("123456")
        assertEquals(0,f.validations)
        f.cancel()
        f.coordinator.submitAuthCode("123456")
        f.foreground.value=true
        f.coordinator.authenticate()
        f.coordinator.openBrowser{true}
        runCurrent()
        assertEquals(0,f.validations);assertTrue(f.saved.isEmpty())
        f.cancel()
    }

    @Test fun invalidManualAccessDoesNotStartOperation()=runTest {
        val f=Fixture(this)
        f.coordinator.configure("short","CI")
        assertEquals(0,f.generation);assertEquals(MusicError.INVALID_ACCESS,f.state.value.error)
    }
}
