package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DeviceAuthTest {
    private val anon="ANONYMOUS_SECRET_FIXTURE"
    private val verify="VERIFICATION_SECRET_FIXTURE"
    private val hash="PAIRING_SECRET_FIXTURE"
    private val token="vk1.a."+"TOKEN_SECRET_".repeat(8)
    private val url="https://vk.com/activate#ONE_TIME_URL_SECRET"
    private val bootstrap=DeviceBootstrap(anon,verify)
    private fun json(raw: String)=Json.parseToJsonElement(raw)
    private fun failure(issue: DeviceAuthIssue,block: ()->Unit) {
        val e=assertThrows(DeviceAuthFailure::class.java){block()}
        assertEquals(issue,e.issue)
        for(secret in listOf(anon,verify,hash,token,url))assertFalse(e.toString().contains(secret))
    }
    @Test fun bootstrapRejectsAmbiguousOrMissingValuesAndNeverPrintsCredentials() {
        val raw="{\"anonymous_token\":\""+anon+"\",\"code_auth_verification_hash\":\""+verify+"\"}"
        val b=DeviceAuthProtocol.bootstrap(raw+raw)
        assertEquals(anon,b.anonymous);assertEquals(verify,b.verification)
        assertFalse(b.toString().contains(anon));assertFalse(b.toString().contains(verify))
        failure(DeviceAuthIssue.RESPONSE){DeviceAuthProtocol.bootstrap(raw+"{\"anonymous_token\":\"ANOTHER_SECRET_VALUE\"}")}
        failure(DeviceAuthIssue.RESPONSE){DeviceAuthProtocol.bootstrap("<html>Login required</html>")}
    }
    @Test fun pairingExpiryIsBoundedAndOnlyFirstPartyHttpsBrowserLinksAreAccepted() {
        val raw="""{"auth_hash":"PAIRING_SECRET_FIXTURE","auth_url":"https://vk.com/activate#ONE_TIME_URL_SECRET","expires_in":600}"""
        val session=DeviceAuthProtocol.session(json(raw),bootstrap,1000)
        assertEquals(301000L,session.expiresAt);assertEquals(url,session.url)
        assertFalse(session.toString().contains(hash));assertFalse(session.toString().contains(url))
        failure(DeviceAuthIssue.RESPONSE){DeviceAuthProtocol.session(json(raw.replace(":600",":0")),bootstrap,1000)}
        failure(DeviceAuthIssue.RESPONSE){DeviceAuthProtocol.session(json(raw.replace(":600",":\"600\"")),bootstrap,1000)}
        for(bad in listOf("http://vk.com/activate","https://vk.com.evil.test/activate","https://vk.com@evil.test/",
            "https://secret@vk.com/activate","https://vk.com:8443/activate","https://127.0.0.1/","https://vk.com\\@evil.test/",
            "https://vk.com/activate\nsecret"))failure(DeviceAuthIssue.RESPONSE){DeviceAuthProtocol.browserUrl(bad)}
    }
    @Test fun serverApprovalDoesNotAcceptPartialPasswordOrUnsupportedTokenResults() {
        for(status in listOf(0,1,200))assertEquals(status==1,(DeviceAuthProtocol.poll(json("{\"status\":"+status+"}")) as DeviceCheck.Pending).confirmPhone)
        assertEquals(DeviceCheck.Expired,DeviceAuthProtocol.poll(json("""{"status":4}""")))
        val raw="{\"status\":2,\"access_token\":\""+token+"\",\"need_password\":0,\"is_partial\":false}"
        val approved=DeviceAuthProtocol.poll(json(raw)) as DeviceCheck.Approved
        assertEquals(token,approved.access.token);assertFalse(approved.toString().contains(token))
        failure(DeviceAuthIssue.PARTIAL){DeviceAuthProtocol.poll(json(raw.replace("\"need_password\":0","\"need_password\":1")))}
        failure(DeviceAuthIssue.PARTIAL){DeviceAuthProtocol.poll(json(raw.replace("\"is_partial\":false","\"is_partial\":true")))}
        failure(DeviceAuthIssue.UNSUPPORTED_TOKEN){DeviceAuthProtocol.poll(json("""{"status":2,"super_app_token":"SECRET_SUPER_TOKEN"}"""))}
        for(bad in listOf("""{"status":"2"}""","""{"status":3}""","""{"status":2}""",
            raw.replace("\"is_partial\":false","\"is_partial\":\"false\""),
            raw.replace(token,"github_pat_"+"X".repeat(60))))
            failure(DeviceAuthIssue.RESPONSE){DeviceAuthProtocol.poll(json(bad))}
    }
    @Test fun serverErrorsAreFixedCodesAndDoNotRetainResponseMessages() {
        val e=assertThrows(DeviceAuthFailure::class.java){
            DeviceAuthProtocol.response("""{"error":{"error_code":15,"error_msg":"PHONE +79991234567 TOKEN_SECRET_FIXTURE"}}""")
        }
        assertEquals(DeviceAuthIssue.SERVER,e.issue);assertEquals(15,e.remoteCode)
        assertFalse(e.toString().contains("TOKEN_SECRET"));assertFalse(e.toString().contains("7999"))
    }
    @Test fun actualRequestBuildersKeepSecretsInPostBodiesAndDiagnosticsContainOnlyMetadata()=runBlocking {
        val requests=mutableListOf<Request>();val events=mutableListOf<String>()
        val client=NetworkClients.api().newBuilder().addInterceptor {chain->
            val r=chain.request();requests.add(r)
            val body=when(r.url.encodedPath) {
                "/qr_auth"->"{\"anonymous_token\":\""+anon+"\",\"code_auth_verification_hash\":\""+verify+"\"}"
                "/method/auth.getAuthCode"->"{\"response\":{\"auth_hash\":\""+hash+"\",\"auth_url\":\""+url+"\",\"expires_in\":120}}"
                "/method/auth.checkAuthCode"->"{\"response\":{\"status\":2,\"access_token\":\""+token+"\"}}"
                else->error("Unexpected endpoint")
            }
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api=VkDeviceAuthClient(client,{1000L}){t,f->events.add(t+f.toString())}
        val s=api.begin("VK Music Export / synthetic device")
        assertEquals(token,(api.poll(s) as DeviceCheck.Approved).access.token)
        assertEquals(3,requests.size)
        for(r in requests) {
            assertNull(r.header("Cookie"));assertNull(r.header("Authorization"))
            assertFalse(r.url.toString().contains(anon));assertFalse(r.url.toString().contains(hash))
            if(r.url.host=="api.vk.com") {
                assertNull(r.url.query);assertEquals("POST",r.method)
                val b=r.body as FormBody;val fields=(0 until b.size).associate{b.name(it) to b.value(it)}
                assertEquals(DeviceAuthProtocol.WEB_CLIENT,fields["client_id"]);assertEquals(anon,fields["anonymous_token"])
                assertFalse(fields.containsKey("client_secret"));assertFalse(fields.containsKey("password"))
            }
        }
        val logged=events.joinToString()
        for(secret in listOf(anon,verify,hash,token,url,"synthetic device"))assertFalse(logged.contains(secret))
        assertFalse(client.followRedirects);assertFalse(client.followSslRedirects)
    }
    @Test fun bootstrapNeverFollowsRedirectToAnUnapprovedHost()=runBlocking {
        val calls=AtomicInteger()
        val client=NetworkClients.api().newBuilder().addInterceptor {chain->
            calls.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                .header("Location","https://evil.test/collect").body("".toResponseBody()).build()
        }.build()
        val e=try{VkDeviceAuthClient(client,{0L}){_,_->}.begin("Fixture");null}catch(e: DeviceAuthFailure){e}
        assertEquals(DeviceAuthIssue.RESPONSE,e!!.issue);assertEquals(1,calls.get())
    }
    @Test fun pairingWaitsForUserGestureAndForegroundThenResumesAfterPhoneConfirmation()=runBlocking {
        val foreground=MutableStateFlow(false);val opened=MutableStateFlow(false);val calls=AtomicInteger()
        val paused=CompletableDeferred<Unit>()
        val session=DeviceSession(anon,hash,url,5000)
        val api=object: DeviceAuthApi {
            override suspend fun begin(deviceName: String)=session
            override suspend fun poll(session: DeviceSession): DeviceCheck =
                if(calls.incrementAndGet()==1)DeviceCheck.Pending(true) else DeviceCheck.Approved(MusicAccess(token,"Client"))
        }
        val result=async {
            awaitDeviceAccess(api,session,opened,foreground,{0L},1){phase->
                if(phase==DeviceAuthPhase.CONFIRM_PHONE){foreground.value=false;paused.complete(Unit)}
            }
        }
        yield();assertEquals(0,calls.get())
        opened.value=true;yield();assertEquals(0,calls.get())
        foreground.value=true;withTimeout(2000){paused.await()};delay(20)
        assertEquals(1,calls.get());assertFalse(result.isCompleted)
        foreground.value=true;assertEquals(token,withTimeout(2000){result.await()}.token);assertEquals(2,calls.get())
    }
    @Test fun expirationAndCancellationCannotBecomeAuthenticationSuccess()=runBlocking {
        val api=object: DeviceAuthApi {
            override suspend fun begin(deviceName: String)=error("Not called")
            override suspend fun poll(session: DeviceSession)=error("Must not poll")
        }
        val session=DeviceSession(anon,hash,url,20)
        val expired=try{awaitDeviceAccess(api,session,MutableStateFlow(false),MutableStateFlow(false),{0L},1){};null}
            catch(e: DeviceAuthFailure){e}
        assertEquals(DeviceAuthIssue.EXPIRED,expired!!.issue)
        val work=launch{awaitDeviceAccess(api,DeviceSession(anon,hash,url,5000),MutableStateFlow(true),MutableStateFlow(false),{0L},1){}}
        yield();work.cancelAndJoin();assertTrue(work.isCancelled)
    }
}
