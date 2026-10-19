package app.alcovaibe.vkmusicexport.updates

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class UpdateProtocolTest {
    private val token="github_pat_"+"A".repeat(80)
    private val hash="a".repeat(64)
    private fun raw(tag: String="v0.6.0-build.1048",extra: String="",digest: String="sha256:$hash")="""{"draft":false,"prerelease":false,"tag_name":"$tag","assets":[{"id":1,"size":100,"digest":"$digest","state":"uploaded","name":"VK-Music-Export-0.6.0-1048.apk"},{"id":2,"size":100,"digest":"sha256:$hash","state":"uploaded","name":"build-info.json"}$extra]}""".toByteArray()
    @Test fun parsesOnlyStableExpectedTagAndExactlyOneUploadedApk() {
        assertEquals(1048,UpdateProtocol.latest(raw()).code)
        for(bytes in listOf(raw("v9.0.0"),raw("v0.6.0-build.2147483647"),raw(digest="sha1:$hash"),String(raw()).replace("\"draft\":false","\"draft\":true").toByteArray(),String(raw()).replace("uploaded","pending").toByteArray(),raw(extra=",{\"id\":3,\"size\":100,\"digest\":\"sha256:$hash\",\"state\":\"uploaded\",\"name\":\"VK-Music-Export-0.6.0-1048.apk\"}"))) {
            assertThrows(UpdateProblem::class.java){UpdateProtocol.latest(bytes)}
        }
    }
    @Test fun buildInfoDigestAndIdentityMustMatchTheApiRelease() {
        val info="""{"versionName":"0.6.0","versionCode":1048,"minSdk":36,"applicationId":"app.alcovaibe.vkmusicexport","commit":"${"a".repeat(40)}","tag":"v0.6.0-build.1048","apk":"VK-Music-Export-0.6.0-1048.apk"}""".toByteArray()
        val assets=UpdateProtocol.latest(raw()).copy(info=UpdateAsset(2,info.size.toLong(),UpdateProtocol.hash(info)))
        assertEquals(36,UpdateProtocol.release(info,assets).minSdk)
        assertThrows(UpdateProblem::class.java){UpdateProtocol.release(info+byteArrayOf(32),assets)}
        val wrong=String(info).replace("1048","1047").toByteArray()
        assertThrows(UpdateProblem::class.java){UpdateProtocol.release(wrong,assets.copy(info=UpdateAsset(2,wrong.size.toLong(),UpdateProtocol.hash(wrong))))}
    }
    @Test fun credentialsAreOnlySentToFixedApiPathsAndNeverToRedirectTargets() {
        assertTrue(UpdateProtocol.validToken(token));assertFalse(UpdateProtocol.validToken(token+"\r\nX: Y"))
        assertEquals("Bearer $token",GithubUpdates.request("${UpdateProtocol.API}/latest",token,"application/json").header("Authorization"))
        assertEquals("Bearer $token",GithubUpdates.request(UpdateProtocol.assetUrl(3),token,"application/octet-stream").header("Authorization"))
        for(url in listOf("https://release-assets.githubusercontent.com/a?sig=PRIVATE","https://objects.githubusercontent.com/a","https://api.github.com/other","https://api.github.com.evil.test/")) {
            assertNull(GithubUpdates.request(url,token,"application/octet-stream").header("Authorization"))
        }
        for(url in listOf("http://release-assets.githubusercontent.com/x","https://evil.test/x","https://api.github.com/other","https://u:p@release-assets.githubusercontent.com/x","https://release-assets.githubusercontent.com:444/x","https://release-assets.githubusercontent.com/x#y","https://release-assets.githubusercontent.com\\@evil.test/x")) {
            assertThrows(UpdateProblem::class.java){UpdateProtocol.redirect(UpdateProtocol.assetUrl(1),url)}
        }
        assertTrue(UpdateProtocol.redirect(UpdateProtocol.assetUrl(1),"https://release-assets.githubusercontent.com/x?sig=PRIVATE").contains("sig="))
    }
    @Test fun rejectsDowngradeOtherPackageSignerDebuggableAndIncompatibleSdk() {
        val release=UpdateRelease("0.6.0",1048,36,UpdateAsset(1,100,hash))
        fun verify(pkg: String=UpdateProtocol.PACKAGE,code: Long=1048,name: String="0.6.0",min: Int=36,debug: Boolean=false,signers: Set<String> = setOf("signer"),installed: Long=1047)=
            UpdateProtocol.identity(release,installed,36,pkg,code,name,min,debug,signers,setOf("signer"))
        verify()
        for(check in listOf<()->Unit>({verify(pkg="other")},{verify(code=1047)},{verify(name="0.6.1")},{verify(min=37)},{verify(debug=true)},{verify(signers=setOf("wrong"))},{verify(installed=1048)}))assertThrows(UpdateProblem::class.java){check()}
    }
    @Test fun githubAccessErrorsRemainFixedCodesAndDoNotIncludeBodySecrets()=runBlocking {
        val client=GithubUpdates.newClient().newBuilder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(401).message("PRIVATE_TOKEN").body("PRIVATE_PASSWORD".toResponseBody()).build()
        }.build()
        val service=GithubUpdates(null,client)
        try {service.latest(token);fail("Expected access error")}catch(e: UpdateProblem){assertEquals("ACCESS",e.code);assertEquals("ACCESS",e.message)}finally{service.close()}
    }
    @Test fun actualMetadataAssetRedirectDropsAuthorizationAndChecksDigest()=runBlocking {
        val info="""{"versionName":"0.6.0","versionCode":1048,"minSdk":36,"applicationId":"app.alcovaibe.vkmusicexport","commit":"${"a".repeat(40)}","tag":"v0.6.0-build.1048","apk":"VK-Music-Export-0.6.0-1048.apk"}""".toByteArray()
        val seen=java.util.Collections.synchronizedList(mutableListOf<Request>())
        val latest=String(raw()).replace("\"id\":2,\"size\":100,\"digest\":\"sha256:$hash\"","\"id\":2,\"size\":${info.size},\"digest\":\"sha256:${UpdateProtocol.hash(info)}\"")
        val client=GithubUpdates.newClient().newBuilder().addInterceptor { chain ->
            val r=chain.request();seen.add(r)
            val builder=Response.Builder().request(r).protocol(Protocol.HTTP_1_1).message("Fixture")
            when(r.url.toString()) {
                "${UpdateProtocol.API}/latest"->builder.code(200).header("Content-Type","application/json").body(latest.toResponseBody("application/json".toMediaType())).build()
                UpdateProtocol.assetUrl(2)->builder.code(302).header("Location","https://release-assets.githubusercontent.com/info?sig=PRIVATE").body("".toResponseBody()).build()
                else->builder.code(200).body(info.toResponseBody()).build()
            }
        }.build()
        val service=GithubUpdates(null,client)
        try {
            assertEquals(1048,service.latest(token).code);assertEquals(3,seen.size)
            assertEquals("Bearer $token",seen[0].header("Authorization"));assertEquals("Bearer $token",seen[1].header("Authorization"))
            assertNull(seen[2].header("Authorization"));assertNull(seen[2].header("X-GitHub-Api-Version"))
        }finally{service.close()}
    }
}
