package app.alcovaibe.vkmusicexport

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** The logged-out Friends prompt stays centered inside the content area, not top-left. */
@RunWith(AndroidJUnit4::class)
class FriendsSignedOutUiDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()

    @Test fun loginPromptAndActionAreCenteredAndUsable() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val clicked=AtomicBoolean(false)
        val api=object: MusicApi {
            override suspend fun library(access: MusicAccess)=NativeProbe(12,NativePage(emptyList(),0))
            override suspend fun catalog(access: MusicAccess,owner: Long)=NativeCatalog(emptyList(),0)
            override suspend fun playlist(access: MusicAccess,list: NativePlaylist)=NativePage(emptyList(),0)
            override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("Not called")
        }
        val controller=FriendMusicController(ui.activity.application,api,scope)
        try {
            ui.activity.runOnUiThread {
                ui.activity.setContent {
                    MusicTheme {
                        MusicLibraryScaffold(
                            title="Друзья",destination=MusicDestination.FRIENDS,
                            onDestination={},queue=ExportSnapshot(),onDownloads={},onAbout={},onBack={},
                            tag="friends_signed_out_screen"
                        ) {
                            friendMusicItems(
                                state=FriendMusicState(),controller=controller,configured=false,enabled=false,
                                queue=ExportSnapshot(),queueItems=emptyMap(),tasks=emptyList(),saved=emptyMap(),
                                preview={null},onPause={},onDownloads={},onOpenVk={},
                                onConnect={clicked.set(true)}
                            )
                        }
                    }
                }
            }
            ui.waitForIdle()
            val viewport=ui.onNodeWithTag("friends_signed_out_screen_list").fetchSemanticsNode().boundsInRoot
            val group=ui.onNodeWithTag("friends_connect").fetchSemanticsNode().boundsInRoot
            val prompt=ui.onNodeWithTag("friends_connect_prompt").fetchSemanticsNode().boundsInRoot
            val button=ui.onNodeWithTag("friends_connect_button").fetchSemanticsNode().boundsInRoot

            val tolerance=16f*ui.activity.resources.displayMetrics.density
            assertTrue("Content should fill the visible Friends viewport",group.height>viewport.height*0.8f)
            assertTrue("Prompt should be horizontally centered",abs(prompt.center.x-viewport.center.x)<tolerance)
            assertTrue("Button should be horizontally centered",abs(button.center.x-viewport.center.x)<tolerance)
            assertTrue("Prompt and action should occupy vertical center",
                abs((prompt.top+button.bottom)/2f-viewport.center.y)<tolerance)
            ui.onNodeWithTag("friends_connect_button").assertIsDisplayed().performClick()
            assertTrue(clicked.get())
        }finally {
            scope.cancel()
        }
    }
}
