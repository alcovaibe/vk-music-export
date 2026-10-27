package app.alcovaibe.vkmusicexport

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class DevicePairingViewModelDeviceTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as Application
    private val old="vk1.a."+"PREVIOUS_CREDENTIAL_FIXTURE_".repeat(5)
    private val fresh="vk1.a."+"NEW_CREDENTIAL_FIXTURE_".repeat(6)
    private val anon="ANONYMOUS_FIXTURE_SECRET"
    private val hash="PAIRING_FIXTURE_SECRET"
    private fun session()=DeviceSession(anon,hash,"https://qr.vk.ru/s?q=SYNTHETIC_ONE_TIME_SECRET",SystemClock.elapsedRealtime()+25000)

    @Test fun codeRetryAndSuperAppExchangeReplaceCredentialOnlyAfterMusicSucceeds()=exercise(false)
    @Test fun exchangedSuperAppTokenAndDeniedMusicKeepPreviousEncryptedCredential()=exercise(true)

    @Test fun journalSecretCheckIgnoresDigitsInsideNumericTimingsButRejectsSecretValues() {
        val timing=Json.parseToJsonElement("""{"elapsed_ns":111111183947222222,"wall_ms":1793111183947,"events":[{"data":{"phase":"CONNECTED"}}]}""")
        for(code in listOf("111111","183947","222222"))assertFalse(containsSecret(timing,code))
        // Parse escaped JSON strings too; raw substring matching would miss this value.
        assertTrue(containsSecret(Json.parseToJsonElement("""{"events":[{"data":{"value":"\u0031\u0038\u0033\u0039\u0034\u0037"}}]}"""),"183947"))
        assertTrue(containsSecret(Json.parseToJsonElement("""{"events":[{"data":{"value":183947}}]}"""),"183947"))
        assertTrue(containsSecret(Json.parseToJsonElement("""{"events":[{"data":{"value":"prefix183947suffix"}}]}"""),"183947"))
        assertTrue(containsSecret(Json.parseToJsonElement("""{"data":{"credential":"$fresh"}}"""),fresh))
        assertTrue(containsSecret(Json.parseToJsonElement("""{"data":{"183947":"unexpected key"}}"""),"183947"))
    }

    private fun containsSecret(value: JsonElement,secret: String): Boolean=when(value) {
        is JsonObject -> value.any{(key,child)->key.contains(secret)||containsSecret(child,secret)}
        is JsonArray -> value.any{containsSecret(it,secret)}
        // Timings/counters can coincidentally contain a six-digit code. A numeric
        // value equal to the code still fails, as do occurrences in any string.
        is JsonPrimitive -> if(value.isString)value.content.contains(secret) else value.content==secret
    }

    private fun exercise(denied: Boolean) {
        val vault=MusicAccessStore(app);val store=ViewModelStore()
        val requests=AtomicInteger();val validations=AtomicInteger();val musicRequests=AtomicInteger();val exchanges=AtomicInteger()
        val releaseMusic=CompletableDeferred<Unit>();val releaseValidation=CompletableDeferred<Unit>()
        val releaseExchange=CompletableDeferred<Unit>()
        lateinit var model: NativeMusicViewModel
        var modelCreated=false
        val s=session()
        val auth=object: DeviceAuthApi {
            override suspend fun begin(deviceName: String): DeviceSession{requests.incrementAndGet();return s}
            override suspend fun poll(session: DeviceSession): DeviceCheck {
                assertSame(s,session)
                return if(validations.get()<2)DeviceCheck.VerificationRequired else DeviceCheck.ExchangeRequired("SUPER_APP_SECRET_FIXTURE_".repeat(4))
            }
            override suspend fun validate(session: DeviceSession,code: DeviceConfirmationCode): DeviceValidation {
                assertSame(s,session)
                val n=validations.incrementAndGet();assertEquals(if(n==1)"111111" else "183947",code.value)
                if(n==2)releaseValidation.await()
                return if(n==1)DeviceValidation.INCORRECT else DeviceValidation.ACCEPTED
            }
            override suspend fun exchange(session: DeviceSession,result: DeviceCheck.ExchangeRequired): MusicAccess {
                assertSame(s,session);exchanges.incrementAndGet();releaseExchange.await()
                return MusicAccess(fresh,NativeProtocol.DEFAULT_AGENT)
            }
        }
        val api=object: FixtureMusicApi() {
            override suspend fun library(access: MusicAccess): NativeProbe {
                assertEquals(fresh,access.token);musicRequests.incrementAndGet();releaseMusic.await()
                if(denied)throw MusicFailure(MusicError.DENIED,15)
                return NativeProbe(12,NativePage(emptyList(),0))
            }
        }
        try {
            vault.save(MusicAccess(old,"Previous fixture client"));model=create(store,api,auth);modelCreated=true
            await{model.state.value.initialized}
            instrumentation.runOnMainSync{model.setForeground(true);model.authenticate()}
            await{model.state.value.authPhase==DeviceAuthPhase.READY}
            instrumentation.runOnMainSync{model.submitAuthCode("183947")}
            assertEquals(0,validations.get()) // No field/submission before the server challenge.
            instrumentation.runOnMainSync{model.setForeground(false);model.openAuthChrome(app);model.stopInBackground()}
            assertTrue(model.state.value.busy);assertEquals(old,vault.read()!!.token)
            instrumentation.runOnMainSync{model.setForeground(true)}
            await{model.state.value.authPhase==DeviceAuthPhase.ENTER_CODE}
            instrumentation.runOnMainSync{model.submitAuthCode("111111")}
            await{model.state.value.authCodeIssue==DeviceCodeIssue.INCORRECT&&!model.state.value.authCodePending}
            assertEquals(1,validations.get());assertEquals(1,requests.get());assertEquals(old,vault.read()!!.token)
            instrumentation.runOnMainSync{model.submitAuthCode("183947")}
            await{validations.get()==2&&model.state.value.authCodePending}
            instrumentation.runOnMainSync{model.submitAuthCode("222222")}
            assertEquals(2,validations.get());releaseValidation.complete(Unit)
            await{model.state.value.authPhase==DeviceAuthPhase.EXCHANGING&&exchanges.get()==1}
            assertEquals(old,vault.read()!!.token)
            instrumentation.runOnMainSync{model.stopInBackground()}
            releaseExchange.complete(Unit);runBlocking{delay(30)}
            assertEquals(0,musicRequests.get());assertTrue(model.state.value.busy);assertEquals(old,vault.read()!!.token)
            instrumentation.runOnMainSync{model.setForeground(true)}
            await{model.state.value.authPhase==DeviceAuthPhase.VERIFYING&&musicRequests.get()==1}
            assertEquals(old,vault.read()!!.token)
            instrumentation.runOnMainSync{model.stopInBackground()}
            releaseMusic.complete(Unit)
            if(!denied) {
                runBlocking{delay(30)};assertTrue(model.state.value.busy);assertEquals(old,vault.read()!!.token)
                instrumentation.runOnMainSync{model.setForeground(true)}
            }
            await{!model.state.value.busy}
            assertEquals(1,requests.get());assertEquals(2,validations.get());assertEquals(1,exchanges.get());assertEquals(1,musicRequests.get())
            if(denied) {
                assertEquals(DeviceAuthIssue.MUSIC_CHECK,model.state.value.authIssue);assertEquals(old,vault.read()!!.token)
            }else {
                assertEquals(DeviceAuthPhase.CONNECTED,model.state.value.authPhase);assertEquals(fresh,vault.read()!!.token)
            }
            val log=Json.parseToJsonElement(Diagnostics.export())
            for((index,secret) in listOf(old,fresh,anon,hash,"SUPER_APP_SECRET_FIXTURE","111111","183947","222222","SYNTHETIC_ONE_TIME_SECRET").withIndex())
                assertFalse("Journal contains secret fixture ${index+1}",containsSecret(log,secret))
        }finally {
            releaseValidation.complete(Unit);releaseExchange.complete(Unit);releaseMusic.complete(Unit);instrumentation.runOnMainSync{store.clear()}
            if(modelCreated)await{!model.state.value.busy}
            vault.delete()
        }
    }

    @Test fun nextActivityWaitsForCancelledOwnerInsteadOfReceivingBusy() {
        val firstStore=ViewModelStore();val secondStore=ViewModelStore()
        val entered=CompletableDeferred<Unit>();val releaseCleanup=CompletableDeferred<Unit>()
        val secondBegins=AtomicInteger();lateinit var first: NativeMusicViewModel;lateinit var second: NativeMusicViewModel
        var firstCreated=false;var secondCreated=false
        val firstAuth=object: DeviceAuthApi {
            override suspend fun begin(deviceName: String): DeviceSession {
                entered.complete(Unit)
                try{awaitCancellation()}finally{withContext(NonCancellable){releaseCleanup.await()}}
            }
            override suspend fun poll(session: DeviceSession): DeviceCheck=error("Not called")
            override suspend fun validate(session: DeviceSession,code: DeviceConfirmationCode): DeviceValidation=error("Not called")
        }
        val secondAuth=object: DeviceAuthApi {
            override suspend fun begin(deviceName: String): DeviceSession{secondBegins.incrementAndGet();return session()}
            override suspend fun poll(session: DeviceSession): DeviceCheck=error("Browser not opened")
            override suspend fun validate(session: DeviceSession,code: DeviceConfirmationCode): DeviceValidation=error("Not called")
        }
        try {
            first=create(firstStore,FixtureMusicApi(),firstAuth);firstCreated=true;await{first.state.value.initialized}
            instrumentation.runOnMainSync{first.setForeground(true);first.authenticate()}
            await{entered.isCompleted}
            instrumentation.runOnMainSync{first.cancel();first.authenticate()}
            assertTrue(first.state.value.busy);assertTrue(first.state.value.stopping)
            second=create(secondStore,FixtureMusicApi(),secondAuth);secondCreated=true;await{second.state.value.initialized}
            instrumentation.runOnMainSync{second.setForeground(true);second.authenticate()}
            assertTrue(second.state.value.busy);assertTrue(second.state.value.waitingForSlot)
            assertNull(second.state.value.error);assertEquals(0,secondBegins.get())
            releaseCleanup.complete(Unit)
            await{!first.state.value.busy&&second.state.value.authPhase==DeviceAuthPhase.READY}
            assertEquals(1,secondBegins.get());assertFalse(second.state.value.waitingForSlot);assertNull(second.state.value.error)
        }finally {
            releaseCleanup.complete(Unit)
            instrumentation.runOnMainSync{firstStore.clear();secondStore.clear()}
            if(firstCreated)await{!first.state.value.busy}
            if(secondCreated)await{!second.state.value.busy}
        }
    }

    private fun create(store: ViewModelStore,api: MusicApi,auth: DeviceAuthApi): NativeMusicViewModel {
        lateinit var model: NativeMusicViewModel
        instrumentation.runOnMainSync {
            val factory=object: ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T: ViewModel> create(modelClass: Class<T>): T =
                    NativeMusicViewModel(app,api,auth,authPollMs=20,authBrowser={_,_->true}) as T
            }
            model=ViewModelProvider(store,factory)[NativeMusicViewModel::class.java]
        }
        return model
    }
    private fun await(condition: ()->Boolean)=runBlocking {
        val deadline=SystemClock.elapsedRealtime()+15000
        while(!condition()){assertTrue("Pairing fixture timed out",SystemClock.elapsedRealtime()<deadline);delay(10)}
    }
    private open class FixtureMusicApi: MusicApi {
        override suspend fun library(access: MusicAccess): NativeProbe=error("Not called")
        override suspend fun catalog(access: MusicAccess,owner: Long)=NativeCatalog(emptyList(),0)
        override suspend fun playlist(access: MusicAccess,list: NativePlaylist): NativePage=error("Not called")
        override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("Not called")
    }
}
