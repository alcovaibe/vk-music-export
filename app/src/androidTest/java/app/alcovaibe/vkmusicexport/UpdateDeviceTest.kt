package app.alcovaibe.vkmusicexport

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.updates.*
import app.alcovaibe.vkmusicexport.diagnostics.Diagnostics
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateAccessDeviceTest {
    @Test fun legacyGithubCredentialRemovalPreservesMusicAccessAndScopedInstallerUri() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val old=File(context.noBackupFilesDir,"update-access.bin").apply{writeText("legacy encrypted envelope fixture")}
        val music=File(context.noBackupFilesDir,"native-music-access.bin")
        val before=music.takeIf{it.exists()}?.readBytes()
        LegacyUpdateAccess.cleanup(context)
        assertFalse(old.exists())
        assertArrayEquals(before,music.takeIf{it.exists()}?.readBytes())
        LegacyUpdateAccess.cleanup(context)
        val apk=File(context.cacheDir,"updates/synthetic.apk").apply{parentFile!!.mkdirs();writeText("Synthetic provider data, not an installable APK")}
        val unrelated=File(context.cacheDir,"unrelated-private.txt").apply{writeText("PRIVATE")}
        try {
            val intent=UpdateInstaller.install(context,apk)
            assertEquals(Intent.ACTION_VIEW,intent.action);assertEquals("application/vnd.android.package-archive",intent.type)
            assertEquals("content",intent.data!!.scheme);assertEquals("${context.packageName}.updates",intent.data!!.authority)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)
            assertArrayEquals(apk.readBytes(),context.contentResolver.openInputStream(intent.data!!)!!.use{it.readBytes()})
            assertThrows(IllegalArgumentException::class.java){UpdateInstaller.install(context,unrelated)}
            assertEquals("package:${context.packageName}",UpdateInstaller.permission(context).data.toString())
        }finally{apk.delete();unrelated.delete()}
    }
}

@RunWith(AndroidJUnit4::class)
class UpdateUiDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun publicUpdatesUseHomeButtonAndNativeSheetWithoutSettingsEntry() {
        val collection=ui.activity.getFileStreamPath("collection.json").takeIf{it.exists()}?.readBytes()
        ui.onNodeWithTag("check_updates").assertExists()
        ui.onNodeWithContentDescription("Меню").performClick()
        ui.onNodeWithTag("open_app_updates").assertIsDisplayed().performClick()
        ui.onNodeWithTag("music_app_updates").assertExists()
        ui.onNodeWithTag("check_updates").assertExists()
        androidx.test.espresso.Espresso.pressBack()
        // A fixed local source proves the result sheet; this test never contacts GitHub or installs an APK.
        lateinit var controller: UpdateController
        val scope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.Main.immediate)
        ui.runOnUiThread {
            controller=UpdateController(scope,object: UpdatePreferences {
                override var lastAttempt=System.currentTimeMillis()
                override var deferred=0
            },object: UpdateService {
                override suspend fun latest()=UpdateRelease("99.0.0",2_000_000_000,36,UpdateAsset(100,"a".repeat(64)))
                override suspend fun download(release: UpdateRelease,progress: (Long,Long)->Unit): File=error("No download")
                override suspend fun verify(file: File,release: UpdateRelease)=Unit
            },1,36)
            ui.activity.setContent {MusicTheme {UpdatePanel(controller)}}
        }
        try {
            ui.waitUntil(10000){controller.state.value.initialized}
            ui.runOnUiThread{controller.resume()}
            ui.onNodeWithTag("check_updates").performClick()
            ui.onNodeWithTag("updates_sheet").assertExists()
            ui.onNodeWithText("Доступно обновление 99.0.0").assertExists()
            ui.onNodeWithText("Позже").performClick()
            ui.onNodeWithTag("updates_sheet").assertDoesNotExist()
            ui.onNodeWithTag("check_updates").assertExists()
            assertFalse(File(ui.activity.noBackupFilesDir,"update-access.bin").exists())
            assertArrayEquals(collection,ui.activity.getFileStreamPath("collection.json").takeIf{it.exists()}?.readBytes())
        }finally{ui.runOnUiThread{controller.close();scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()}}
    }
}
