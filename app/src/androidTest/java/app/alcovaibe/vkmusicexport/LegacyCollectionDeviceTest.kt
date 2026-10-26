package app.alcovaibe.vkmusicexport

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyCollectionDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()

    @Test fun obsoleteCollectionIsIgnoredAndNeverRewrittenAfterUpgradeOrNavigation() {
        val file=ui.activity.getFileStreamPath("collection.json")
        val previous=file.takeIf{it.exists()}?.readBytes()
        val legacy="Invalid legacy collection must not block downloads".toByteArray()
        try {
            file.writeBytes(legacy)
            ui.activityRule.scenario.recreate()
            ui.onNodeWithText("VK Music").assertExists()
            ui.onNodeWithText("Моя коллекция").assertDoesNotExist()
            ui.onNodeWithText("VK Music").performScrollTo().performClick()
            ui.onNodeWithTag("native_music_screen").assertExists()
            ui.onNodeWithText("Сохранить эту страницу в коллекцию…").assertDoesNotExist()
            ui.onNodeWithContentDescription("Назад").performClick()
            ui.onNodeWithContentDescription("Меню").performClick()
            ui.onNodeWithText("Очистить коллекцию").assertDoesNotExist()
            assertArrayEquals(legacy,file.readBytes())
        } finally {
            if(previous==null)file.delete() else file.writeBytes(previous)
        }
    }
}
