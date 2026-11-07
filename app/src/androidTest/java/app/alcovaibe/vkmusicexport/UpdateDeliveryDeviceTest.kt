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
import java.io.InputStream
import okio.buffer
import okio.source
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Two separate instrumentation processes, with an actual host force-stop between them. */
@RunWith(AndroidJUnit4::class)
class UpdateDeliveryDeviceTest {
    @Test fun signedPublicApkPassesUpdaterAndSystemInstallerPreservesPrivateData()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val host=instrumentation.targetContext
        val automation=instrumentation.uiAutomation
        fun shell(command: String)=ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use{it.readText().trim()}
        assertTrue("Dedicated updater host required",host.packageName.endsWith(".updatecheck"))
        assertEquals("Disposable emulator required","1",shell("getprop ro.kernel.qemu"))
        val phase=InstrumentationRegistry.getArguments().getString("phase")
        assertTrue("Explicit test phase required",phase in setOf("prepare","install"))
        val production=UpdateProtocol.PACKAGE
        val pm=host.packageManager
        val before=pm.getPackageInfo(production,PackageManager.GET_SIGNING_CERTIFICATES)
        assertEquals(1343L,before.longVersionCode)
        val assets=instrumentation.context.assets
        val json=assets.open("update-fixtures/manifest.json").use{it.readBytes()}
        val release=UpdateProtocol.latest(json)
        // Keep APK-sized fixtures off the heap, including on API 29's small test heap.
        val candidate=File(host.cacheDir,"candidate-fixture.apk")
        assets.open("update-fixtures/candidate.apk").use{input->candidate.outputStream().use{input.copyTo(it)}}
        fun hash(input: InputStream): String {
            val digest=MessageDigest.getInstance("SHA-256")
            val buffer=ByteArray(8192)
            while(true) {
                val count=input.read(buffer)
                if(count<0)break
                digest.update(buffer,0,count)
            }
            return digest.digest().joinToString(""){"%02x".format(it)}
        }
        // Select the real installed public baseline for identity/certificate comparison;
        // retain the isolated harness cache and real PackageManager.
        val baselineContext=object: ContextWrapper(host){override fun getPackageName()=production}
        val client=GithubUpdates.newClient().newBuilder().addInterceptor { chain ->
            check(phase=="prepare"){"No network request is permitted after process restart"}
            val request=chain.request()
            assertNull(request.header("Authorization"));assertNull(request.header("Cookie"))
            val metadata=request.url.toString()==UpdateProtocol.MANIFEST
            if(!metadata)assertEquals(UpdateProtocol.downloadUrl(release),request.url.toString())
            val body=if(metadata)json.toResponseBody("application/json".toMediaType()) else object: ResponseBody() {
                private val stream=candidate.source().buffer()
                override fun contentType()="application/octet-stream".toMediaType()
                override fun contentLength()=candidate.length()
                override fun source()=stream
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("public byte fixture")
                .body(body).build()
        }.build()
        val service=GithubUpdates(baselineContext,client)
        var controller: UpdateController?=null
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        try {
            if(phase=="prepare") {
                assertFalse("Permission must be absent before the real process interruption",pm.canRequestPackageInstalls())
                val bad=File(host.cacheDir,"wrong-signer.apk")
                assets.open("update-fixtures/wrong-signer.apk").use{input->bad.outputStream().use{input.copyTo(it)}}
                try {
                    val wrong=release.copy(apk=UpdateAsset(bad.length(),bad.inputStream().use{hash(it)}))
                    try{service.verify(bad,wrong);fail("Wrong actual signer was accepted")}catch(e: UpdateProblem){assertEquals("INVALID_APK",e.code)}
                }finally{bad.delete()}
                // Repacking removes the signing block; altering classes.dex invalidates v1 too.
                // Matching size/hash force rejection by actual signature verification.
                val tampered=File(host.cacheDir,"tampered.apk")
                try {
                    java.util.zip.ZipInputStream(candidate.inputStream()).use{input->
                        java.util.zip.ZipOutputStream(tampered.outputStream()).use{output->
                            while(true){val entry=input.nextEntry ?: break
                                output.putNextEntry(java.util.zip.ZipEntry(entry.name))
                                if(entry.name=="classes.dex") {
                                    val first=input.read()
                                    if(first>=0)output.write(first xor 1)
                                }
                                input.copyTo(output)
                                output.closeEntry()
                            }
                        }
                    }
                    val altered=release.copy(apk=UpdateAsset(tampered.length(),tampered.inputStream().use{hash(it)}))
                    try{service.verify(tampered,altered);fail("Tampered APK was accepted")}catch(e: UpdateProblem){assertEquals("INVALID_APK",e.code)}
                }finally{tampered.delete()}
                assertEquals(release,service.latest())
                val apk=service.download(release){received,total->assertTrue(received in 0..total)}
                service.verify(apk,release)
                File(host.filesDir,"updater-prepared.json").writeText("""{"sha256":"${release.apk.hash}","permission":false,"pid":${android.os.Process.myPid()}}""")
                return@runBlocking // close() must retain the verified pending file.
            }
            val c=withContext(Dispatchers.Main) {
                UpdateController(scope,object: UpdatePreferences {
                    override var lastAttempt=System.currentTimeMillis()
                    override var deferred=0
                },service,1343,Build.VERSION.SDK_INT).also{it.resume()}
            }
            controller=c
            val ready=withTimeout(10000){c.state.first{it.initialized}}
            assertEquals("APK must be restored without another request",UpdateStatus.READY,ready.status)
            assertEquals(release,ready.release)
            val prepared=org.json.JSONObject(File(host.filesDir,"updater-prepared.json").readText())
            assertNotEquals("The test must use a different actual process",prepared.getInt("pid"),android.os.Process.myPid())
            val apk=c.verifiedApk() ?: error("Restored APK did not verify")
            val install=UpdateInstaller.install(host,apk)
            assertEquals(Intent.ACTION_VIEW,install.action)
            assertEquals("${host.packageName}.updates",install.data!!.authority)
            assertTrue(install.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)
            assertEquals(release.apk.hash,host.contentResolver.openInputStream(install.data!!)!!.use{hash(it)})
            val resolved=pm.resolveActivity(install,PackageManager.MATCH_DEFAULT_ONLY) ?: error("System APK installer missing")
            assertTrue("Installer must be a system component",resolved.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0)
            val installer=resolved.activityInfo.packageName
            val installerPackages=setOf(installer,"com.android.packageinstaller","com.google.android.packageinstaller", "com.android.permissioncontroller","com.google.android.permissioncontroller")
            automation.serviceInfo=automation.serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS}
            assertTrue(pm.canRequestPackageInstalls())
            host.getSharedPreferences("updates",0).edit().putLong("last_attempt",System.currentTimeMillis()).commit()
            host.startActivity(Intent(host,UpdateHarnessActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(1000)
            host.startActivity(install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            fun clickInstall(node: AccessibilityNodeInfo?): Boolean {
                if(node==null)return false
                if(node.packageName?.toString() in installerPackages && node.text?.toString()?.lowercase() in setOf("update","install","обновить","установить") && node.isEnabled && node.isClickable)
                    if(pm.getApplicationInfo(node.packageName.toString(),0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0)
                        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                for(i in 0 until node.childCount)if(clickInstall(node.getChild(i)))return true
                return false
            }
            // Only known emulator framework ANRs may be dismissed; an app ANR remains a failure.
            val frameworkActions=mapOf("System UI isn't responding" to "Wait", "Quickstep isn't responding" to "Close app")
            fun frameworkAction(node: AccessibilityNodeInfo?): String? {
                if(node==null)return null
                if(node.viewIdResourceName=="android:id/alertTitle")frameworkActions[node.text?.toString()]?.let{return it}
                for(i in 0 until node.childCount)frameworkAction(node.getChild(i))?.let{return it}
                return null
            }
            fun clickFrameworkAction(node: AccessibilityNodeInfo?, action: String): Boolean {
                if(node==null)return false
                if(node.packageName?.toString()=="android" && node.text?.toString()==action && node.isEnabled && node.isClickable)
                    return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                for(i in 0 until node.childCount)if(clickFrameworkAction(node.getChild(i),action))return true
                return false
            }
            val until=System.currentTimeMillis()+90000
            var confirmed=false
            var frameworkDialogs=0
            while(System.currentTimeMillis()<until) {
                if(pm.getPackageInfo(production,0).longVersionCode==release.code.toLong())break
                val roots=(listOf(automation.rootInActiveWindow)+automation.windows.map{it.root}).filterNotNull()
                var frameworkHandled=false
                for(root in roots) {
                    val action=frameworkAction(root) ?: continue
                    assertTrue("Repeated emulator framework ANR prevents a valid test",frameworkDialogs<3)
                    if(clickFrameworkAction(root,action)) {
                        frameworkDialogs++
                        android.util.Log.i("VKUpdateDelivery","Handled known emulator framework dialog: "+action)
                        frameworkHandled=true
                        break
                    }
                }
                if(frameworkHandled){Thread.sleep(500);continue}
                if(!confirmed)confirmed=roots.any{clickInstall(it)}
                Thread.sleep(500)
            }
            assertTrue("System installer confirmation not observed",confirmed)
            val after=pm.getPackageInfo(production,PackageManager.GET_SIGNING_CERTIFICATES)
            assertEquals(release.code.toLong(),after.longVersionCode)
            assertEquals(before.applicationInfo!!.uid,after.applicationInfo!!.uid)
            try{service.verify(apk,release);fail("Installed equal version was accepted again")}catch(e: UpdateProblem){assertEquals("INVALID_APK",e.code)}
            service.discard(apk)
            assertNull(service.restore())
            File(host.filesDir,"updater-proof.json").writeText("""{"api":${Build.VERSION.SDK_INT},"from":1343,"to":${release.code},"sha256":"${release.apk.hash}","system_installer":true,"process_restart":true,"offline_restore":true,"emulator_framework_dialogs":$frameworkDialogs}""")
        }finally{withContext(Dispatchers.Main){controller?.close()};scope.cancel();service.close();candidate.delete()}
    }
}
