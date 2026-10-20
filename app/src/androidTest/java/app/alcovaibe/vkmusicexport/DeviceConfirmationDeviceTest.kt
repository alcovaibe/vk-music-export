package app.alcovaibe.vkmusicexport

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.alcovaibe.vkmusicexport.music.*
import org.hamcrest.CoreMatchers.allOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceConfirmationUiDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()

    @Test fun codeScreenIsSecureClearsOnSubmitPauseAndStateRestoreAndDoesNotUseADialog() {
        var visible by mutableStateOf(true)
        var submitted: String?=null
        val state=NativeMusicState(initialized=true,busy=true,stage="browser_auth",authPhase=DeviceAuthPhase.ENTER_CODE)
        val restore=StateRestorationTester(ui)
        restore.setContent {
            MusicTheme {
                if(visible)DeviceConfirmationScreen(state,{submitted=it},{visible=false},{}) else Text("Отменено")
            }
        }
        ui.onNodeWithTag("native_auth_code_screen").assertExists()
        ui.onAllNodes(isDialog()).assertCountEquals(0)
        ui.runOnIdle{assertTrue(ui.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0)}
        ui.onNodeWithTag("native_auth_submit_code").assertIsNotEnabled()
        ui.onNodeWithTag("native_auth_confirmation_code").performTextInput("183947")
        ui.onNodeWithTag("native_auth_submit_code").assertIsEnabled()
        restore.emulateSavedInstanceStateRestore()
        assertEmptyCode()
        ui.onNodeWithTag("native_auth_confirmation_code").performTextInput("183947")
        assertReadyToSubmit("after state restoration")
        ui.onNodeWithTag("native_auth_submit_code").performScrollTo().performClick()
        ui.runOnIdle{assertEquals("183947",submitted);submitted=null}
        assertEmptyCode()
        ui.onNodeWithTag("native_auth_confirmation_code").performScrollTo().performTextInput("183947")
        ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        restoreTestTaskToFront(ui.activity)
        ui.waitForIdle();assertEmptyCode()
        ui.onNodeWithTag("native_auth_confirmation_code").performScrollTo().performTextInput("183947")
        assertReadyToSubmit("after Activity resume")
        ui.onNodeWithTag("native_auth_submit_code").performScrollTo().performClick()
        ui.runOnIdle{assertEquals("183947",submitted)}
        assertEmptyCode()
        ui.onNodeWithTag("native_auth_cancel_code").performScrollTo().performClick()
        ui.onNodeWithText("Отменено").assertExists()
        ui.runOnIdle{assertEquals(0,ui.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)}
    }

    @Test fun confirmationDispatchIsPinnedToChromeAndDisablesDefaultExternalRedirectHandling() {
        ui.setContent{Text("Browser dispatch fixture")}
        awaitTestWindowFocus(ui.activity)
        Intents.init()
        try {
            Intents.intending(hasAction(Intent.ACTION_VIEW)).respondWith(ActivityResult(Activity.RESULT_CANCELED,null))
            ui.runOnIdle{assertTrue(openDeviceAuthChrome(ui.activity,"https://qr.vk.ru/s?q=SYNTHETIC_ONE_TIME_LINK"))}
            awaitTestWindowFocus(ui.activity)
            Intents.intended(allOf(hasAction(Intent.ACTION_VIEW),hasPackage("com.android.chrome"),
                hasExtra(CustomTabsIntent.EXTRA_SEND_TO_EXTERNAL_DEFAULT_HANDLER,false),
                hasExtra(CustomTabsIntent.EXTRA_SHARE_STATE,CustomTabsIntent.SHARE_STATE_OFF),
                hasExtraWithKey(CustomTabsIntent.EXTRA_SESSION)))
        }finally{Intents.release()}
    }

    private fun assertEmptyCode() {
        assertEquals("",ui.onNodeWithTag("native_auth_confirmation_code").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        ui.onNodeWithTag("native_auth_submit_code").assertIsNotEnabled()
    }
    private fun assertReadyToSubmit(stage: String) {
        val length=ui.onNodeWithTag("native_auth_confirmation_code").fetchSemanticsNode().config[SemanticsProperties.EditableText].text.length
        ui.runOnIdle {
            assertEquals("$stage; lifecycle=${ui.activity.lifecycle.currentState}; windowFocused=${ui.activity.hasWindowFocus()}",6,length)
        }
        ui.onNodeWithTag("native_auth_submit_code").assertIsEnabled()
    }
}
