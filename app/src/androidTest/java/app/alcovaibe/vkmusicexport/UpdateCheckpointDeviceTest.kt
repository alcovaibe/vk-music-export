package app.alcovaibe.vkmusicexport

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.updates.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateCheckpointDeviceTest {
    @Test fun malformedCheckpointAndOutOfDirectoryPathNeverDeleteAnUnrelatedFile()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=context.getSharedPreferences("updates-transfer",0)
        val private=File(context.filesDir,"updater-private-fixture").apply{writeText("KEEP")}
        val release=UpdateRelease("99.0.0",2_000_000_000,29,UpdateAsset(4,UpdateProtocol.hash("KEEP".toByteArray())))
        try {
            for(name in listOf("../updater-private-fixture",private.absolutePath)) {
                prefs.edit().putString("file",name).putString("manifest",UpdateProtocol.manifest(release)).commit()
                val service=GithubUpdates(context)
                try{assertNull(service.restore());assertEquals("KEEP",private.readText());assertTrue(prefs.all.isEmpty())}finally{service.close()}
            }
            prefs.edit().putInt("file",17).putString("manifest","bad JSON").commit()
            val service=GithubUpdates(context)
            try{assertNull(service.restore());assertEquals("KEEP",private.readText())}finally{service.close()}
        }finally{prefs.edit().clear().commit();private.delete()}
    }
    @Test fun corruptOwnedApkIsRemovedAndCanNeverRestoreReady()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.cacheDir,"updates").apply{mkdirs()}
        val file=File(directory,"vkm-corrupt-fixture.apk").apply{writeText("NOT AN APK")}
        val release=UpdateRelease("99.0.0",2_000_000_000,29,UpdateAsset(file.length(),UpdateProtocol.hash(file.readBytes())))
        val prefs=context.getSharedPreferences("updates-transfer",0)
        prefs.edit().putString("file",file.name).putString("manifest",UpdateProtocol.manifest(release)).commit()
        val service=GithubUpdates(context)
        try{assertNull(service.restore());assertFalse(file.exists());assertTrue(prefs.all.isEmpty())}
        finally{service.close();prefs.edit().clear().commit();file.delete()}
    }
}
