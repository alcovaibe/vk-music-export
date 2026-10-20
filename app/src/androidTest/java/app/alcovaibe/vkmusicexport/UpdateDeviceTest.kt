package app.alcovaibe.vkmusicexport

import android.content.Intent
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
    @Test fun realKeystoreEncryptionReloadTamperingDeletionAndScopedInstallerUri() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=KeystoreUpdateAccess(context)
        val token="github_pat_"+"SYNTHETIC_TEST_".repeat(6)
        try {
            store.delete();store.save(token)
            val file=File(context.noBackupFilesDir,"update-access.bin")
            assertTrue(file.exists());assertFalse(file.readBytes().toString(Charsets.UTF_8).contains(token))
            assertEquals(token,KeystoreUpdateAccess(context).read())
            val broken=file.readBytes();broken[broken.lastIndex]=(broken.last().toInt() xor 1).toByte();file.writeBytes(broken)
            assertThrows(Exception::class.java){KeystoreUpdateAccess(context).read()}
            store.save(token);assertEquals(token,store.read());store.delete();assertNull(store.read());assertFalse(file.exists())
            assertFalse(Diagnostics.export().contains(token))
        }finally{store.delete()}
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
    @Test fun nativeUpdatesPanelAndInvalidCredentialDoNotWriteAccessOrCollection() {
        val updates=androidx.lifecycle.ViewModelProvider(ui.activity)[UpdateViewModel::class.java]
        ui.waitUntil(10000){updates.controller.state.value.initialized}
        val collection=ui.activity.getFileStreamPath("collection.json").takeIf{it.exists()}?.readBytes()
        ui.onNodeWithContentDescription("Настройки").performClick()
        ui.onNodeWithText("Обновления приложения").performScrollTo().performClick()
        ui.onNodeWithText("Настроить доступ GitHub").performScrollTo().performClick()
        ui.onNodeWithTag("update_token").performTextInput("not-a-valid-token")
        ui.onNodeWithText("Сохранить и проверить").performScrollTo().performClick()
        ui.onNodeWithText("Нужен fine-grained токен github_pat_… длиной до 256 символов.").assertExists()
        assertFalse(File(ui.activity.noBackupFilesDir,"update-access.bin").exists())
        ui.onNodeWithText("Отмена").performScrollTo().performClick();ui.onNodeWithText("Закрыть").performScrollTo().performClick()
        val after=ui.activity.getFileStreamPath("collection.json").takeIf{it.exists()}?.readBytes()
        assertArrayEquals(collection,after)
    }
}
