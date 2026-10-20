package app.alcovaibe.vkmusicexport

import android.app.Application
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SettingsNavigationDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun settingsAreASeparateRestorableScreenAndBackReturnsToCollection() {
        ui.onNodeWithContentDescription("Настройки").performClick()
        ui.onNodeWithTag("settings_screen").assertExists()
        ui.onAllNodes(isDialog()).assertCountEquals(0)
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("settings_screen").assertExists()
        ui.onNodeWithContentDescription("Назад").performClick()
        ui.onNodeWithText("Моя коллекция").assertExists()
        ui.onNodeWithTag("settings_screen").assertDoesNotExist()
    }
}

@RunWith(AndroidJUnit4::class)
class NativeBrowserAuthDeviceTest {
    @Test fun approvedPairingSurvivesChromeBackgroundAndSavesOnlyAfterMusicSucceeds()=exercise(false)
    @Test fun approvedPairingWithMusicDeniedKeepsPreviousEncryptedCredential()=exercise(true)

    private fun exercise(denied: Boolean) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val application=instrumentation.targetContext.applicationContext as Application
        val vault=MusicAccessStore(application)
        val old="vk1.a."+"PREVIOUS_TEST_TOKEN_".repeat(6)
        val fresh="vk1.a."+"NEW_TEST_TOKEN_".repeat(8)
        val anon="ANONYMOUS_TEST_SECRET";val hash="PAIRING_TEST_SECRET"
        val polls=AtomicInteger();val musicRequests=AtomicInteger()
        val api=object: MusicApi {
            override suspend fun library(access: MusicAccess): NativeProbe {
                musicRequests.incrementAndGet();assertEquals(fresh,access.token)
                if(denied)throw MusicFailure(MusicError.DENIED,15)
                return NativeProbe(12,NativePage(listOf(NativeTrack("12_34","Synthetic track","CI",1)),1))
            }
            override suspend fun catalog(access: MusicAccess,owner: Long)=NativeCatalog(emptyList(),0)
            override suspend fun playlist(access: MusicAccess,list: NativePlaylist): NativePage=error("Not called")
            override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("Not called")
        }
        val auth=object: DeviceAuthApi {
            override suspend fun begin(deviceName: String)=DeviceSession(anon,hash,"https://vk.com/activate#SYNTHETIC",SystemClock.elapsedRealtime()+20000)
            override suspend fun poll(session: DeviceSession): DeviceCheck {
                polls.incrementAndGet();return DeviceCheck.Approved(MusicAccess(fresh,NativeProtocol.DEFAULT_AGENT))
            }
        }
        val store=ViewModelStore();lateinit var model: NativeMusicViewModel
        try {
            vault.save(MusicAccess(old,"Previous synthetic client"))
            instrumentation.runOnMainSync {
                val factory=object: ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T: ViewModel> create(modelClass: Class<T>): T =
                        NativeMusicViewModel(application,api,auth,authPollMs=1,authBrowser={_,_->true}) as T
                }
                model=ViewModelProvider(store,factory)[NativeMusicViewModel::class.java]
            }
            await{model.state.value.initialized}
            instrumentation.runOnMainSync{model.setForeground(true);model.authenticate()}
            await{model.state.value.authPhase==DeviceAuthPhase.READY}
            assertEquals(0,polls.get())
            instrumentation.runOnMainSync {
                model.setForeground(false);model.stopInBackground()
                model.openAuthChrome(application) // Injected dispatcher; no actual Chrome or VK request.
            }
            assertTrue(model.state.value.busy);assertEquals(0,polls.get())
            assertEquals(old,vault.read()!!.token)
            instrumentation.runOnMainSync{model.setForeground(true)}
            await{!model.state.value.busy}
            assertEquals(1,polls.get());assertEquals(1,musicRequests.get())
            if(denied) {
                assertEquals(DeviceAuthPhase.FAILED,model.state.value.authPhase)
                assertEquals(DeviceAuthIssue.MUSIC_CHECK,model.state.value.authIssue)
                assertEquals(MusicError.DENIED,model.state.value.error)
                assertEquals(old,vault.read()!!.token)
            }else {
                assertEquals(DeviceAuthPhase.CONNECTED,model.state.value.authPhase)
                assertEquals(fresh,vault.read()!!.token)
                assertEquals(1,model.state.value.page!!.tracks.size)
            }
            val log=Diagnostics.export()
            for(secret in listOf(old,fresh,anon,hash))assertFalse(log.contains(secret))
        }finally{
            instrumentation.runOnMainSync{store.clear()}
            vault.delete()
        }
    }
    private fun await(condition: ()->Boolean)=runBlocking {
        val deadline=SystemClock.elapsedRealtime()+15000
        while(!condition()){assertTrue("Native fixture timed out",SystemClock.elapsedRealtime()<deadline);delay(10)}
    }
}
