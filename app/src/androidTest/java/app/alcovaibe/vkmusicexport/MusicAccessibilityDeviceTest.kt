package app.alcovaibe.vkmusicexport

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.alcovaibe.vkmusicexport.music.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MusicAccessibilityDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val track=NativeTrack("12_1","Очень длинное название музыкального произведения","Исполнитель",180)

    @Test fun playerPositionAndLoadingPauseHaveMeaningAtLargeTextAndNarrowWidth() {
        for(scale in listOf(1.3f,2f)) {
            ui.runOnUiThread{ui.activity.setContent {
                val density=LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density,scale)) {
                    MusicTheme {Box(Modifier.width(360.dp).fillMaxHeight()) {
                        PlaybackScreen(PlaybackState(entries=listOf(PlaybackEntry(track,"Тестовая очередь")),
                            positionMs=65_000,durationMs=180_000,seekable=true,buffering=true,playWhenReady=true),{},{},false)
                    }}
                }
            }}
            ui.onNodeWithTag("player_seek").performScrollTo()
                .assertContentDescriptionEquals("Позиция воспроизведения")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"1 мин. 5 сек. из 3 мин. 0 сек."))
                .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            ui.onNodeWithTag("player_toggle").performScrollTo().assertIsDisplayed().assertContentDescriptionEquals("Пауза")
            ui.onNodeWithTag("player_collapse").performScrollTo().assertHasClickAction()
        }
    }
    @Test fun filledSearchRetainsItsLabelAndTrackSelectionHasAnExplicitAction() {
        var selected=0
        ui.runOnUiThread{ui.activity.setContent {MusicTheme {
            Column(Modifier.width(360.dp)) {
                FriendSearch("Исполнитель","Поиск музыки","a11y_search",{})
                FriendTrackRow(track,1,false,false,{selected++},null,null,true,{},ExportSnapshot(),null,null,{}, {},true)
            }
        }}}
        ui.onNodeWithText("Поиск музыки").assertExists()
        ui.onNodeWithTag("friend_select_action_1").assertHasClickAction().performClick()
        ui.runOnIdle{assertEquals(1,selected)}
        val actions=ui.onNodeWithTag("friend_track_1").fetchSemanticsNode().config[SemanticsProperties.CustomActions]
        assertTrue(actions.any{it.label=="Выбрать ${track.displayTitle}"})
    }
}
