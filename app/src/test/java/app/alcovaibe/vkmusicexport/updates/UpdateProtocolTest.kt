package app.alcovaibe.vkmusicexport.updates

import java.io.File
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class UpdateProtocolTest {
    private val hash="a".repeat(64)
    private fun raw(code: Int=1048)="""{"schemaVersion":1,"platform":"android","channel":"stable","applicationId":"app.alcovaibe.vkmusicexport","release":{"versionName":"0.6.0","versionCode":$code,"minSdk":36,"tag":"v0.6.0-build.$code","apk":"VK-Music-Export-0.6.0-$code.apk","size":100,"sha256":"$hash"}}""".toByteArray()
    @Test fun strictPublicManifestRequiresMatchingPackageTagApkAndDigest() {
        assertEquals(1048,UpdateProtocol.latest(raw()).code)
        val text=String(raw())
        for(bad in listOf(text.replace("stable","beta"),text.replace("app.alcovaibe.vkmusicexport","other"),text.replace("\"schemaVersion\":1","\"schemaVersion\":2"),text.replace("\"minSdk\":36","\"minSdk\":\"36\""),text.replace("v0.6.0-build.1048","v0.6.0"),text.replace(".apk",".exe"),text.replace(hash,"bad"),text.replace("\"size\":100","\"size\":0"),text.replace("\"size\":100","\"size\":104857601"),text.replace("\"versionCode\":1048","\"versionCode\":2147483647"),text.replace("\"release\":{","\"url\":\"https://evil.test\",\"release\":{"))) {
            assertThrows(UpdateProblem::class.java){UpdateProtocol.latest(bad.toByteArray())}
        }
        assertThrows(UpdateProblem::class.java){UpdateProtocol.latest(ByteArray(16385))}
        assertThrows(UpdateProblem::class.java){UpdateProtocol.latest(byteArrayOf(0xff.toByte()))}
    }
    @Test fun publicRequestsNeverContainAuthorizationOrCookiesAndRedirectsAreBoundedToGithubCdn() {
        val r=UpdateProtocol.latest(raw())
        assertEquals("https://github.com/alcovaibe/vk-music-export/releases/download/v0.6.0-build.1048/VK-Music-Export-0.6.0-1048.apk",UpdateProtocol.downloadUrl(r))
        for(url in listOf(UpdateProtocol.MANIFEST,UpdateProtocol.downloadUrl(r),"https://release-assets.githubusercontent.com/x?sig=PRIVATE")) {
            val req=GithubUpdates.request(url,"application/octet-stream")
            assertNull(req.header("Authorization"));assertNull(req.header("Cookie"));assertNull(req.header("X-GitHub-Api-Version"))
        }
        for(url in listOf("http://release-assets.githubusercontent.com/x","https://evil.test/x","https://api.github.com/other","https://u:p@release-assets.githubusercontent.com/x","https://release-assets.githubusercontent.com:444/x","https://release-assets.githubusercontent.com/x#y","https://release-assets.githubusercontent.com\\@evil.test/x")) {
            assertThrows(UpdateProblem::class.java){UpdateProtocol.redirect(UpdateProtocol.downloadUrl(r),url)}
        }
    }
    @Test fun rejectsDowngradeOtherPackageSignerDebuggableAndIncompatibleSdk() {
        val release=UpdateProtocol.latest(raw())
        fun verify(pkg: String=UpdateProtocol.PACKAGE,code: Long=1048,name: String="0.6.0",min: Int=36,debug: Boolean=false,signers: Set<String> = setOf("signer"),installed: Long=1047)=
            UpdateProtocol.identity(release,installed,36,pkg,code,name,min,debug,signers,setOf("signer"))
        verify()
        for(check in listOf<()->Unit>({verify(pkg="other")},{verify(code=1047)},{verify(name="0.6.1")},{verify(min=37)},{verify(debug=true)},{verify(signers=setOf("wrong"))},{verify(signers=emptySet())},{verify(installed=1048)}))assertThrows(UpdateProblem::class.java){check()}
    }
    @Test fun publicManifestSupportsEtagAndRejectsRollbackAndUnsolicitedNotModified()=runBlocking {
        val seen=mutableListOf<Request>();var mode=0
        val client=GithubUpdates.newClient().newBuilder().addInterceptor { chain ->
            val req=chain.request();seen.add(req)
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).message("Fixture").apply {
                if(mode==1)code(304).body("".toResponseBody())
                else code(200).header("ETag","\"fixture\"").body(raw(if(mode==2)1047 else 1048).toResponseBody("text/plain; charset=utf-8".toMediaType()))
            }.build()
        }.build()
        val service=GithubUpdates(null,client)
        try {
            assertEquals(1048,service.latest().code);mode=1;assertEquals(1048,service.latest().code)
            assertEquals("\"fixture\"",seen.last().header("If-None-Match"));assertNull(seen.last().header("Authorization"))
            mode=2
            try{service.latest();fail("Rollback accepted")}catch(e: UpdateProblem){assertEquals("INVALID_RESPONSE",e.code)}
        }finally{service.close()}
        val noCache=GithubUpdates(null,GithubUpdates.newClient().newBuilder().addInterceptor {chain->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("Fixture").code(304).body("".toResponseBody()).build()
        }.build())
        try{noCache.latest();fail("Unsolicited 304 accepted")}catch(e: UpdateProblem){assertEquals("INVALID_RESPONSE",e.code)}finally{noCache.close()}
    }
    @Test fun sourceErrorsAreFixedCodesAndNeverIncludeResponseBodySecrets()=runBlocking {
        for((status,expected) in listOf(401 to "ACCESS",429 to "RATE_LIMIT",302 to "NETWORK")) {
            val service=GithubUpdates(null,GithubUpdates.newClient().newBuilder().addInterceptor {chain->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("SECRET").body("PRIVATE_PASSWORD".toResponseBody()).build()
            }.build())
            try{service.latest();fail("Expected error")}catch(e: UpdateProblem){assertEquals(expected,e.code);assertFalse(e.message!!.contains("PRIVATE"))}finally{service.close()}
        }
    }
    @Test fun actualApkTransferFollowsAllowedRedirectsAndRejectsTruncatedData()=runBlocking {
        val release=UpdateProtocol.latest(raw());var truncated=false
        val seen=mutableListOf<Request>()
        val service=GithubUpdates(null,GithubUpdates.newClient().newBuilder().addInterceptor {chain->
            val req=chain.request();seen.add(req)
            val b=Response.Builder().request(req).protocol(Protocol.HTTP_1_1).message("Fixture")
            if(req.url.host=="github.com")b.code(302).header("Location","https://release-assets.githubusercontent.com/file?sig=PRIVATE").body("".toResponseBody()).build()
            else b.code(200).body(ByteArray(if(truncated)99 else 100).toResponseBody()).build()
        }.build())
        val file=File.createTempFile("vkm-transfer", ".apk")
        try {
            service.transfer(release,file);assertEquals(100L,file.length());assertEquals(2,seen.size)
            assertTrue(seen.all{it.header("Authorization")==null&&it.header("Cookie")==null})
            truncated=true
            try{service.transfer(release,file);fail("Truncated APK accepted")}catch(e: UpdateProblem){assertEquals("INVALID_APK",e.code)}
        }finally{service.close();file.delete()}
    }
}
