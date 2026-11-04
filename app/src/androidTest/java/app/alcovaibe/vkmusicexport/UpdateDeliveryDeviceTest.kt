package app.alcovaibe.vkmusicexport

import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.alcovaibe.vkmusicexport.updates.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Dedicated disposable AVD job only. Uses real public signed APKs, not fabricated PackageInfo. */
@RunWith(AndroidJUnit4::class)
class UpdateDeliveryDeviceTest {
    @Test fun signedPublicApkPassesUpdaterAndSystemInstallerPreservesPrivateData()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val host=instrumentation.targetContext
        val automation=instrumentation.uiAutomation
        fun shell(command: String)=ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use{it.readText().trim()}
        assertTrue("Dedicated updater host required",host.packageName.endsWith(".updatecheck"))
        assertEquals("Disposable emulator required","1",shell("getprop ro.kernel.qemu"))
        android.util.Log.i("VKUpdateDelivery","emulator_guard_passed")
        val production=UpdateProtocol.PACKAGE
        val pm=host.packageManager
        val before=pm.getPackageInfo(production,PackageManager.GET_SIGNING_CERTIFICATES)
        assertEquals(1343L,before.longVersionCode)
        android.util.Log.i("VKUpdateDelivery","installed_baseline_read")
        val assets=instrumentation.context.assets
        val json=assets.open("update-fixtures/manifest.json").use{it.readBytes()}
        val release=UpdateProtocol.latest(json)
        val bytes=assets.open("update-fixtures/candidate.apk").use{it.readBytes()}
        fun hash(data: ByteArray)=MessageDigest.getInstance("SHA-256").digest(data).joinToString(""){"%02x".format(it)}
        // The wrapper selects the real installed public baseline for identity/certificate comparison.
        // It preserves the isolated harness's cache and does not replace PackageManager.
        val baselineContext=object: ContextWrapper(host){override fun getPackageName()=production}
        val client=GithubUpdates.newClient().newBuilder().addInterceptor { chain ->
            val request=chain.request()
            assertNull(request.header("Authorization"));assertNull(request.header("Cookie"))
            val metadata=request.url.toString()==UpdateProtocol.MANIFEST
            if(!metadata)assertEquals(UpdateProtocol.downloadUrl(release),request.url.toString())
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("public byte fixture")
                .body((if(metadata)json else bytes).toResponseBody((if(metadata)"application/json" else "application/octet-stream").toMediaType())).build()
        }.build()
        val service=GithubUpdates(baselineContext,client)
        var downloaded: File?=null
        try {
            val bad=File(host.cacheDir,"wrong-signer.apk")
            assets.open("update-fixtures/wrong-signer.apk").use{input->bad.outputStream().use{input.copyTo(it)}}
            try {
                val wrong=release.copy(apk=UpdateAsset(bad.length(),hash(bad.readBytes())))
                try{service.verify(bad,wrong);fail("Wrong actual signer was accepted")}catch(e: UpdateProblem){assertEquals("INVALID_APK",e.code)}
            }finally{bad.delete()}
        android.util.Log.i("VKUpdateDelivery","wrong_signer_rejected")
            assertEquals(release,service.latest())
            val probe=File(host.cacheDir,"candidate-probe.apk").apply{writeBytes(bytes)}
            try {
                val inspected=pm.getPackageArchiveInfo(probe.absolutePath,PackageManager.GET_SIGNING_CERTIFICATES)
                android.util.Log.i("VKUpdateDelivery","identity api=${Build.VERSION.SDK_INT} installed=${before.longVersionCode} package=${inspected?.packageName} code=${inspected?.longVersionCode} name=${inspected?.versionName} min=${inspected?.applicationInfo?.minSdkVersion} flags=${inspected?.applicationInfo?.flags} actualSigners=${inspected?.signingInfo?.apkContentsSigners?.map{hash(it.toByteArray())}} installedSigners=${before.signingInfo?.apkContentsSigners?.map{hash(it.toByteArray())}}")
            }finally{probe.delete()}
            val apk=service.download(release){received,total->assertTrue(received in 0..total)}
            downloaded=apk
            service.verify(apk,release)
        android.util.Log.i("VKUpdateDelivery","download_verified")
            val install=UpdateInstaller.install(host,apk)
            assertEquals(Intent.ACTION_VIEW,install.action)
            assertEquals("${host.packageName}.updates",install.data!!.authority)
            assertTrue(install.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)
            assertEquals(release.apk.hash,host.contentResolver.openInputStream(install.data!!)!!.use{hash(it.readBytes())})
            val resolved=pm.resolveActivity(install,PackageManager.MATCH_DEFAULT_ONLY) ?: error("System APK installer missing")
            assertTrue("Installer must be a system component",resolved.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0)
            val installer=resolved.activityInfo.packageName
            host.getSharedPreferences("updates",0).edit().putLong("last_attempt",System.currentTimeMillis()).commit()
            shell("appops set ${host.packageName} REQUEST_INSTALL_PACKAGES allow")
            assertTrue(pm.canRequestPackageInstalls())
            host.startActivity(Intent(host,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(1000)
        android.util.Log.i("VKUpdateDelivery","opening_system_installer")
            host.startActivity(install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            fun clickInstall(node: AccessibilityNodeInfo?): Boolean {
                if(node==null)return false
                val label=node.text?.toString().orEmpty()
                if(node.packageName?.toString()==installer && label.lowercase() in setOf("update","install","обновить","установить") && node.isEnabled && node.isClickable)
                    return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                for(i in 0 until node.childCount)if(clickInstall(node.getChild(i)))return true
                return false
            }
            val until=System.currentTimeMillis()+90000
            var confirmed=false
            while(System.currentTimeMillis()<until) {
                if(pm.getPackageInfo(production,0).longVersionCode==release.code.toLong())break
                if(!confirmed)confirmed=clickInstall(automation.rootInActiveWindow)
                Thread.sleep(500)
            }
        android.util.Log.i("VKUpdateDelivery","installer_wait_finished")
            assertTrue("System installer confirmation not observed",confirmed)
            val after=pm.getPackageInfo(production,PackageManager.GET_SIGNING_CERTIFICATES)
            assertEquals(release.code.toLong(),after.longVersionCode)
            assertEquals(before.applicationInfo!!.uid,after.applicationInfo!!.uid)
            try{service.verify(apk,release);fail("Installed equal version was accepted again")}catch(e: UpdateProblem){assertEquals("INVALID_APK",e.code)}
            File(host.filesDir,"updater-proof.json").writeText("""{"api":${Build.VERSION.SDK_INT},"from":1343,"to":${release.code},"sha256":"${release.apk.hash}","system_installer":true}""")
        }finally{downloaded?.delete();service.close()}
    }
}
