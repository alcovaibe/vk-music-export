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
    @Test fun jsonBootstrapRequiresTypedBoundedFieldsAndNeverPrintsCredentials() {
        val a=DeviceAuthProtocol.anonymous(json("{\"token\":\""+anon+"\",\"expired_at\":1792518600}"))
        val b=DeviceAuthProtocol.bootstrap(a,json("{\"code_auth_verification_hash\":\""+verify+"\"}"))
        assertEquals(anon,b.anonymous);assertEquals(verify,b.verification)
        assertFalse(b.toString().contains(anon));assertFalse(b.toString().contains(verify))
        for(raw in listOf("{}","{\"token\":false}","{\"token\":\"short\"}","[]","{\"token\":\""+"A".repeat(8193)+"\"}"))
            failure(DeviceAuthIssue.RESPONSE){DeviceAuthProtocol.anonymous(json(raw))}
        for(raw in listOf("{}","{\"code_auth_verification_hash\":23}","{\"code_auth_verification_hash\":\"short\"}"))
            failure(DeviceAuthIssue.RESPONSE){DeviceAuthProtocol.bootstrap(anon,json(raw))}
        val events=mutableListOf<String>()
        failure(DeviceAuthIssue.RESPONSE){DeviceAuthProtocol.response("<!doctype html><script>PRIVATE_PAGE_SECRET</script>"){events.add(it.toString())}}
        assertTrue(events.single().contains("html_like"));assertFalse(events.single().contains("PRIVATE_PAGE_SECRET"))
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
    @Test fun unixExpiryBecomesMonotonicDeadlineAndInvalidClockRangesAreRejected() {
        val wall=1792518420000L
        fun reply(expiry:String)=json("{\"auth_hash\":\""+hash+"\",\"auth_url\":\"https://qr.vk.ru/s?q=ONE_TIME_URL_SECRET\",\"expires_in\":"+expiry+"}")
        assertEquals(181000L,DeviceAuthProtocol.session(reply("1792518600"),bootstrap,1000L,wall).expiresAt)
        assertEquals(301000L,DeviceAuthProtocol.session(reply("1792519020"),bootstrap,1000L,wall).expiresAt)
        failure(DeviceAuthIssue.EXPIRED){DeviceAuthProtocol.session(reply("1792518419"),bootstrap,1000L,wall)}
        failure(DeviceAuthIssue.RESPONSE){DeviceAuthProtocol.session(reply("1792691220"),bootstrap,1000L,wall)}
        failure(DeviceAuthIssue.RESPONSE){DeviceAuthProtocol.session(reply(Long.MAX_VALUE.toString()),bootstrap,1000L,wall)}
        failure(DeviceAuthIssue.RESPONSE){DeviceAuthProtocol.session(reply("\"1792518600\""),bootstrap,1000L,wall)}
        for(bad in listOf("https://qr.vk.ru.evil.test/s?q=SECRET","https://qr.vk.ru@evil.test/s?q=SECRET",
            "http://qr.vk.ru/s?q=SECRET","https://qr.vk.ru:8443/s?q=SECRET","https://secret@qr.vk.ru/s?q=SECRET"))
            failure(DeviceAuthIssue.RESPONSE){DeviceAuthProtocol.browserUrl(bad)}
    }
    @Test fun serverApprovalRejectsPartialSessionsAndKeepsSuperAppSeparateFromApiAccess() {
        for(status in listOf(0,1,200))assertEquals(status==1,(DeviceAuthProtocol.poll(json("{\"status\":"+status+"}")) as DeviceCheck.Pending).opened)
        assertEquals(DeviceCheck.Expired,DeviceAuthProtocol.poll(json("""{"status":4}""")))
        assertEquals(DeviceCheck.Declined,DeviceAuthProtocol.poll(json("""{"status":3}""")))
        assertEquals(DeviceCheck.VerificationRequired,DeviceAuthProtocol.poll(json("""{"status":5}""")))
        val raw="{\"status\":2,\"access_token\":\""+token+"\",\"need_password\":0,\"is_partial\":false}"
        val approved=DeviceAuthProtocol.poll(json(raw)) as DeviceCheck.Approved
        assertEquals(token,approved.access.token);assertFalse(approved.toString().contains(token))
        failure(DeviceAuthIssue.PARTIAL){DeviceAuthProtocol.poll(json(raw.replace("\"need_password\":0","\"need_password\":1")))}
        failure(DeviceAuthIssue.PARTIAL){DeviceAuthProtocol.poll(json(raw.replace("\"is_partial\":false","\"is_partial\":true")))}
        val superToken="SUPER_APP_SECRET_FIXTURE_".repeat(4)
        val exchange=DeviceAuthProtocol.poll(buildJsonObject{put("status",2);put("super_app_token",superToken)}) as DeviceCheck.ExchangeRequired
        assertEquals(superToken,exchange.token);assertFalse(exchange.toString().contains(superToken))
        for(bad in listOf("true","42","\"short\"","{}"))failure(DeviceAuthIssue.RESPONSE){
            DeviceAuthProtocol.poll(json("{\"status\":2,\"super_app_token\":"+bad+"}"))
        }
        for(bad in listOf("""{"status":"2"}""","""{"status":6}""","""{"status":2}""",
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
    @Test fun actualRequestBuildersUseCurrentJsonBootstrapAndKeepSecretsOutOfDiagnostics()=runBlocking {
        val requests=mutableListOf<Request>();val events=mutableListOf<String>()
        val client=NetworkClients.api().newBuilder().addInterceptor {chain->
            val r=chain.request();requests.add(r)
            val body=when(r.url.encodedPath) {
                "/method/auth.getAnonymToken"->"{\"response\":{\"token\":\""+anon+"\",\"expired_at\":1792518600}}"
                "/method/auth.getQrAuthData"->"{\"response\":{\"code_auth_verification_hash\":\""+verify+"\",\"app\":{}}}"
                "/method/auth.getAuthCode"->"{\"response\":{\"auth_hash\":\""+hash+"\",\"auth_url\":\"https://qr.vk.ru/s?q=ONE_TIME_URL_SECRET\",\"expires_in\":1792518600}}"
                "/method/auth.checkAuthCode"->"{\"response\":{\"status\":2,\"access_token\":\""+token+"\"}}"
                "/method/auth.validateAuthCode"->"{\"response\":{\"status\":0}}"
                else->error("Unexpected endpoint")
            }
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api=VkDeviceAuthClient(client,{1000L},{1792518420000L}){type,f->events.add(type+f.toString())}
        val s=api.begin("VK Music Export / synthetic device")
        assertEquals(181000L,s.expiresAt);assertEquals("qr.vk.ru",DeviceAuthProtocol.browserUrl(s.url).host)
        assertEquals(token,(api.poll(s) as DeviceCheck.Approved).access.token)
        val code="184937"
        assertEquals(DeviceValidation.ACCEPTED,api.validate(s,DeviceConfirmationCode.from(code)!!))
        assertEquals(5,requests.size)
        for((i,r) in requests.withIndex()) {
            assertNull(r.header("Cookie"));assertNull(r.header("Authorization"))
            assertEquals("https://id.vk.com",r.header("Origin"));assertEquals(NativeProtocol.DEFAULT_AGENT,r.header("User-Agent"))
            assertFalse(r.url.toString().contains(anon));assertFalse(r.url.toString().contains(hash))
            assertEquals("api.vk.com",r.url.host);assertNull(r.url.query);assertEquals("POST",r.method)
            val b=r.body as FormBody;val fields=(0 until b.size).associate{b.name(it) to b.value(it)}
            assertEquals(DeviceAuthProtocol.WEB_CLIENT,fields["client_id"])
            assertEquals(if(i<2)DeviceAuthProtocol.INIT_VERSION else DeviceAuthProtocol.PAIR_VERSION,fields["v"])
            when {
                i<2 -> assertEquals("",fields["access_token"])
                i==4 -> {
                    assertEquals(anon,fields["access_token"]);assertNull(fields["anonymous_token"])
                    assertEquals(hash,fields["auth_hash"]);assertEquals(code,fields["validation_code"])
                }
                else -> assertEquals(anon,fields["anonymous_token"])
            }
            assertFalse(fields.containsKey("client_secret"));assertFalse(fields.containsKey("password"))
            if(i==1){assertEquals(DeviceAuthProtocol.WEB_CLIENT,fields["app_id"]);assertEquals("",fields["uuid"])}
            if(i==2)assertEquals(verify,fields["verification_hash"])
            if(i==3)assertEquals("1",fields["web_auth"])
        }
        val logged=events.joinToString()
        for(secret in listOf(anon,verify,hash,token,url,code,"ONE_TIME_URL_SECRET","synthetic device","auth_url","code_auth_verification_hash"))
            assertFalse(logged.contains(secret))
        for(stage in listOf("anonymous","qr_bootstrap","create","poll","validate"))assertTrue(logged.contains("stage="+stage))
        assertTrue(logged.contains("expiry_format=unix_seconds"));assertTrue(logged.contains("media_type=json"))
        assertFalse(client.followRedirects);assertFalse(client.followSslRedirects)
    }
    @Test fun failureOfFirstJsonStageStopsBeforeCreatingOrOpeningAnything()=runBlocking {
        val requests=mutableListOf<Request>();val events=mutableListOf<String>()
        val client=NetworkClients.api().newBuilder().addInterceptor {chain->
            requests.add(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("<html>PRIVATE_BODY_SECRET +79991234567</html>".toResponseBody("text/html".toMediaType())).build()
        }.build()
        val e=try{VkDeviceAuthClient(client,{0L}){type,f->events.add(type+f.toString())}.begin("Fixture");null}
            catch(e:DeviceAuthFailure){e}
        assertEquals(DeviceAuthIssue.RESPONSE,e!!.issue);assertEquals(1,requests.size)
        assertEquals("/method/auth.getAnonymToken",requests.single().url.encodedPath)
        val logged=events.joinToString()
        assertTrue(logged.contains("html_like"));assertTrue(logged.contains("media_type=html"))
        assertTrue(logged.contains("success_envelope=false"))
        assertFalse(logged.contains("PRIVATE_BODY_SECRET"));assertFalse(logged.contains("79991234567"))
    }
    @Test fun jsonApiNeverFollowsRedirectToAnUnapprovedHost()=runBlocking {
        val calls=AtomicInteger()
        val client=NetworkClients.api().newBuilder().addInterceptor {chain->
            calls.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                .header("Location","https://evil.test/collect").body("".toResponseBody()).build()
        }.build()
        val e=try{VkDeviceAuthClient(client,{0L}){_,_->}.begin("Fixture");null}catch(e: DeviceAuthFailure){e}
        assertEquals(DeviceAuthIssue.HTTP,e!!.issue);assertEquals(1,calls.get())
    }
    @Test fun pairingWaitsForUserGestureAndForegroundThenResumesAfterRequestIsOpened()=runBlocking {
        val foreground=MutableStateFlow(false);val opened=MutableStateFlow(false);val calls=AtomicInteger()
        val paused=CompletableDeferred<Unit>()
        val session=DeviceSession(anon,hash,url,5000)
        val api=object: DeviceAuthApi {
            override suspend fun validate(session: DeviceSession,code: DeviceConfirmationCode): DeviceValidation=error("Unexpected confirmation")
            override suspend fun begin(deviceName: String)=session
            override suspend fun poll(session: DeviceSession): DeviceCheck =
                if(calls.incrementAndGet()==1)DeviceCheck.Pending(true) else DeviceCheck.Approved(MusicAccess(token,"Client"))
        }
        val result=async {
            awaitDeviceAccess(api,session,opened,foreground,{0L},1){phase->
                if(phase==DeviceAuthPhase.REQUEST_OPENED){foreground.value=false;paused.complete(Unit)}
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
            override suspend fun validate(session: DeviceSession,code: DeviceConfirmationCode): DeviceValidation=error("Unexpected confirmation")
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
