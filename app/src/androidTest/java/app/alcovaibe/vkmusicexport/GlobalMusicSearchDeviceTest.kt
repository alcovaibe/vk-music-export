package app.alcovaibe.vkmusicexport

import android.content.Intent
import android.net.Uri
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
        ui.onNodeWithTag("music_nav_SEARCH").performClick()
        ui.onNodeWithTag("music_search_screen").assertExists()
        ui.onNodeWithTag("music_search_query").performTextInput("a")
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("music_search_screen").assertExists()
        ui.onNodeWithTag("music_search_query").assertTextContains("a")
        ui.onNodeWithTag("music_nav_SEARCH").assertIsSelected()
        androidx.test.espresso.Espresso.pressBack()
        ui.waitForIdle()
        // On API 37, the first system Back may dismiss the IME restored after
        // Activity recreation rather than navigate away from search.
        if (ui.onAllNodesWithTag("music_search_screen").fetchSemanticsNodes().isNotEmpty()) {
            androidx.test.espresso.Espresso.pressBack()
        }
        ui.onNodeWithTag("native_music_screen").assertExists()
        ui.onNodeWithTag("music_nav_LIBRARY").assertIsSelected()
    }
    @Test fun webLinkShowsAppChoiceAndSearchProvidesSettings() {
        ui.onNodeWithTag("music_nav_SEARCH").performClick()
        ui.onNodeWithText("Введите не менее двух символов для поиска.").assertDoesNotExist()
        ui.onNodeWithTag("open_vk_music_link").performClick()
        ui.onNodeWithTag("vk_link_settings").assertExists()
        ui.onNodeWithText("Отмена").performClick()

        ui.activityRule.scenario.onActivity { activity ->
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://vk.ru/audio12_34"), activity,
                MainActivity::class.java))
        }
        ui.onNodeWithTag("vk_link_open_here").assertExists()
        ui.onNodeWithTag("vk_link_open_official").assertExists()
    }

    @Test fun searchIsOnlyInBottomNavigationAndKeepsMiniPlayerSpace() {
        ui.onNodeWithTag("music_nav_SEARCH").assertExists().performClick()
        ui.onNodeWithTag("music_search_screen").assertExists()
        ui.onNodeWithTag("music_nav_SAVED").assertExists()
        ui.onNodeWithContentDescription("Меню").performClick()
        ui.onNodeWithTag("open_music_search").assertDoesNotExist()
    }
}
