package app.alcovaibe.vkmusicexport

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Navigate through the actual launcher activity; a single-character query never reaches VK. */
@RunWith(AndroidJUnit4::class)
class GlobalMusicSearchDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun searchIsReachableAndBackAndActivityRecreationKeepTheUserRoute() {
        ui.onNodeWithContentDescription("Меню").performClick()
        ui.onNodeWithTag("open_music_search").performClick()
        ui.onNodeWithTag("music_search_screen").assertExists()
        ui.onNodeWithTag("music_search_query").performTextInput("a")
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("music_search_screen").assertExists()
        ui.onNodeWithTag("music_search_query").assertTextContains("a")
        ui.onNodeWithText("Исполнитель или название").assertExists()
        androidx.test.espresso.Espresso.pressBack()
        ui.waitForIdle()
        // On API 37, the first system Back may dismiss the IME restored after
        // Activity recreation rather than navigate away from search.
        if (ui.onAllNodesWithTag("music_search_screen").fetchSemanticsNodes().isNotEmpty()) {
            androidx.test.espresso.Espresso.pressBack()
        }
        ui.onNodeWithTag("native_music_screen").assertExists()
    }
}
