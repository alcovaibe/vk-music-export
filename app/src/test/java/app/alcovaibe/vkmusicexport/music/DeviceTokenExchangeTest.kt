package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceTokenExchangeTest {
    private val superToken="SUPER_APP_CREDENTIAL_SECRET_".repeat(4)
    private val intermediate="vk1.a."+"INTERMEDIATE_SECRET_".repeat(5)
    private val finalToken="vk1.a."+"FINAL_API_CREDENTIAL_SECRET_".repeat(4)
    private val callback="https://login.vk.com/?act=finish&ticket=CALLBACK_SECRET"
    private fun session()=DeviceSession("ANONYMOUS_SECRET","PAIR_SECRET","https://qr.vk.ru/s?q=URL_SECRET",10000)
    private fun response(r: Request,body: String,code: Int=200,location: String?=null)=
        Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .apply{location?.let{header("Location",it)}}.body(body.toResponseBody("application/json".toMediaType())).build()
    private fun loginData(token: String,next: String?=null)=buildJsonObject {
        put("type","okay");putJsonObject("data") {
            put("response_type","auth_token");put("access_token",token);next?.let{put("next_step_url",it)}
        }
    }.toString()
    private fun webData(token: String)=buildJsonObject {
        put("type","okay");putJsonObject("data"){put("access_token",token);put("user_id",17);put("expires",3600)}
    }.toString()
    private fun noSecrets(log: String) {
        for(secret in listOf(superToken,intermediate,finalToken,"ANONYMOUS_SECRET","PAIR_SECRET","URL_SECRET","CALLBACK_SECRET",
            "COOKIE_SECRET","PRIVATE_HTML","+79991234567","SERVER_SECRET"))assertFalse("Secret leaked",log.contains(secret))
    }

    @Test fun actualClientCompletesSuperAppExchangeAndNeverUsesIntermediateTokenAsApiAccess()=runBlocking {
        val calls=mutableListOf<Request>();val events=mutableListOf<String>();val phases=mutableListOf<DeviceAuthPhase>()
        val client=NetworkClients.api().newBuilder().addInterceptor {chain->
            val r=chain.request();calls.add(r)
            when {
                r.url.encodedPath=="/method/auth.checkAuthCode" -> response(r,buildJsonObject {
                    putJsonObject("response"){put("status",2);put("super_app_token",superToken);put("is_partial",false)}
                }.toString())
                r.url.queryParameter("act")=="connect_code_auth" -> response(r,loginData(intermediate,callback))
                r.url.queryParameter("act")=="finish" -> response(r,"PRIVATE_HTML",302,"/?act=complete&ticket=CALLBACK_SECRET")
                r.url.queryParameter("act")=="complete" -> response(r,"PRIVATE_HTML")
                r.url.queryParameter("act")=="web_token" -> response(r,webData(finalToken))
                else -> error("Unexpected endpoint")
            }
        }.build()
        val api=VkDeviceAuthClient(client,{0L},{0L}){type,data->events.add(type+data)}
        val result=awaitDeviceAccess(api,session(),MutableStateFlow(true),MutableStateFlow(true),{0L}){phases.add(it)}
        assertEquals(finalToken,result.token);assertEquals(listOf(DeviceAuthPhase.EXCHANGING),phases)
        assertEquals(5,calls.size)
        for(r in calls) {
            assertEquals("https",r.url.scheme);assertEquals(443,r.url.port)
            assertTrue(r.url.host in setOf("api.vk.com","login.vk.com"))
            assertFalse(r.url.toString().contains(superToken));assertFalse(r.url.toString().contains(intermediate))
            assertFalse(r.url.toString().contains(finalToken));assertNull(r.header("Authorization"))
            assertEquals(NativeProtocol.DEFAULT_AGENT,r.header("User-Agent"))
        }
        fun fields(r: Request): Map<String,String> {val b=r.body as FormBody;return (0 until b.size).associate{b.name(it) to b.value(it)}}
        val connect=fields(calls[1]);val web=fields(calls.last())
        assertEquals("POST",calls[1].method);assertEquals(DeviceAuthProtocol.WEB_CLIENT,connect["app_id"])
        assertEquals(superToken,connect["token"]);assertEquals("silent_token",connect["oauth_response_type"])
        assertEquals(32,connect["uuid"]!!.length);assertEquals("0",connect["is_oauth_migrated_flow"])
        assertEquals(listOf("GET","GET"),calls.subList(2,4).map{it.method})
        assertEquals(intermediate,web["access_token"]);assertEquals(WebExchangeProtocol.WEB_TOKEN_CLIENT,web["app_id"])
        assertFalse(connect.containsKey("client_secret"));assertFalse(web.containsKey("client_secret"))
        val log=events.joinToString();noSecrets(log)
        assertTrue(log.contains("body_observation=headers_only"));assertTrue(log.contains("music_access=not_proven"))
        assertTrue(log.contains("native_cookies_cleared=true"))
    }

    @Test fun malformedPartialAndErrorLoginResponsesCannotBecomeApiAccess() {
        for(raw in listOf("[]","<html>PRIVATE_HTML</html>","{}","{\"type\":\"okay\",\"data\":false}",
            "{\"type\":\"okay\",\"data\":{\"is_partial\":\"false\"}}","[".repeat(35)+"]".repeat(35))) {
            val events=mutableListOf<String>()
            val failure=assertThrows(DeviceAuthFailure::class.java){WebExchangeProtocol.response(raw){events.add(it.toString())}}
            assertEquals(DeviceAuthIssue.RESPONSE,failure.issue);noSecrets(events.joinToString()+failure.toString())
        }
        for(key in listOf("is_partial","need_password","is_service","additional_signup_required")) {
            val raw="{\"type\":\"okay\",\"data\":{\"$key\":true}}"
            assertEquals(DeviceAuthIssue.PARTIAL,assertThrows(DeviceAuthFailure::class.java){WebExchangeProtocol.response(raw)}.issue)
        }
        val events=mutableListOf<String>()
        val failure=assertThrows(DeviceAuthFailure::class.java){WebExchangeProtocol.response(
            """{"type":"error","error_code":"SERVER_SECRET","error_info":"+79991234567 PRIVATE_HTML"}"""){events.add(it.toString())}}
        assertEquals(DeviceAuthIssue.TOKEN_EXCHANGE,failure.issue);assertNull(failure.remoteCode)
        noSecrets(events.joinToString()+failure.toString())
        assertThrows(DeviceAuthFailure::class.java){WebExchangeProtocol.access(buildJsonObject{put("access_token",superToken)})}
        assertThrows(DeviceAuthFailure::class.java){WebExchangeProtocol.connected(buildJsonObject{put("access_token",intermediate)})}
    }

    @Test fun callbacksRejectLookalikeHostsCredentialsPortsAndFragmentsBeforeAnyRequest()=runBlocking {
        for(bad in listOf("https://evil.test/","https://vk.com.evil.test/","https://vk.com@evil.test/",
            "http://login.vk.com/","https://secret@login.vk.com/","https://login.vk.com:8443/",
            "https://login.vk.com/#CALLBACK_SECRET","https://api.vk.com/")) {
            var calls=0;val events=mutableListOf<String>()
            val client=NetworkClients.api().newBuilder().addInterceptor{chain->calls++;response(chain.request(),loginData(intermediate,bad))}.build()
            val error=runCatching{VkWebTokenExchange(client,{0L}){t,d->events.add(t+d)}.exchange(session(),DeviceCheck.ExchangeRequired(superToken))}.exceptionOrNull()
            assertEquals(DeviceAuthIssue.RESPONSE,(error as DeviceAuthFailure).issue);assertEquals(1,calls)
            assertTrue(events.last().contains("native_cookies_cleared=true"));noSecrets(events.joinToString())
        }
    }

    @Test fun redirectsCannotReplayPostCredentialsOrEscapeTheCallbackAllowlist()=runBlocking {
        for(fromPost in listOf(true,false)) {
            val calls=mutableListOf<Request>();val events=mutableListOf<String>()
            val client=NetworkClients.api().newBuilder().addInterceptor {chain->
                val r=chain.request();calls.add(r)
                if(!fromPost&&calls.size==1)response(r,loginData(intermediate,callback))
                else response(r,"",307,"https://evil.test/collect")
            }.build()
            val error=runCatching{VkWebTokenExchange(client,{0L}){t,d->events.add(t+d)}.exchange(session(),DeviceCheck.ExchangeRequired(superToken))}.exceptionOrNull()
            assertEquals(if(fromPost)DeviceAuthIssue.HTTP else DeviceAuthIssue.RESPONSE,(error as DeviceAuthFailure).issue)
            assertEquals(if(fromPost)1 else 2,calls.size);assertTrue(calls.all{it.url.host=="login.vk.com"})
            if(!fromPost){assertEquals("GET",calls.last().method);assertNull(calls.last().body)}
            noSecrets(events.joinToString())
        }
    }

    @Test fun deadlineAndTransportFailureAlwaysCleanUpNativeCookies()=runBlocking {
        for(expired in listOf(true,false)) {
            var clock=0L;var calls=0;val events=mutableListOf<String>()
            val client=NetworkClients.api().newBuilder().addInterceptor {chain->
                calls++
                if(expired){clock=10001;response(chain.request(),loginData(intermediate))}
                else throw IOException("SERVER_SECRET +79991234567")
            }.build()
            val error=runCatching{VkWebTokenExchange(client,{clock}){t,d->events.add(t+d)}.exchange(session(),DeviceCheck.ExchangeRequired(superToken))}.exceptionOrNull()
            assertEquals(if(expired)DeviceAuthIssue.EXPIRED else DeviceAuthIssue.NETWORK,(error as DeviceAuthFailure).issue)
            assertEquals(1,calls);assertTrue(events.last().contains("native_cookies_cleared=true"));noSecrets(events.joinToString()+error)
        }
    }

    @Test fun nativeCookieJarIsBoundedScopedAndCanBeCompletelyCleared() {
        val jar=PairingCookieJar();val url="https://login.vk.com/".toHttpUrl()
        val cookie=Cookie.Builder().name("native_fixture").value("COOKIE_SECRET").domain("vk.com").path("/").build()
        jar.saveFromResponse(url,listOf(cookie))
        assertEquals(1,jar.loadForRequest(url).size)
        for(bad in listOf("http://login.vk.com/","https://api.vk.com/","https://vk.com.evil.test/","https://login.vk.com:8443/"))
            assertTrue(jar.loadForRequest(bad.toHttpUrl()).isEmpty())
        for(n in 0..32)jar.saveFromResponse(url,listOf(Cookie.Builder().name("fixture$n").value("COOKIE_SECRET").domain("vk.com").build()))
        assertTrue(jar.limited);assertEquals(32,jar.loadForRequest(url).size);noSecrets(jar.toString())
        jar.clear();assertFalse(jar.limited);assertTrue(jar.loadForRequest(url).isEmpty())
    }

    @Test fun exchangeWaitsForForegroundAndLateResultCannotCompleteExpiredPairing()=runTest {
        val foreground=MutableStateFlow(true);val gate=CompletableDeferred<Unit>();var exchanges=0;var phase=DeviceAuthPhase.READY
        val api=object: DeviceAuthApi {
            override suspend fun begin(deviceName: String)=session()
            override suspend fun validate(session: DeviceSession,code: DeviceConfirmationCode)=error("Not used")
            override suspend fun poll(session: DeviceSession): DeviceCheck {foreground.value=false;return DeviceCheck.ExchangeRequired(superToken)}
            override suspend fun exchange(session: DeviceSession,result: DeviceCheck.ExchangeRequired): MusicAccess {
                exchanges++;gate.await();return MusicAccess(finalToken,NativeProtocol.DEFAULT_AGENT)
            }
        }
        val work=async{runCatching{awaitDeviceAccess(api,session(),MutableStateFlow(true),foreground,
            {testScheduler.currentTime}){phase=it}}}
        runCurrent();assertEquals(0,exchanges)
        foreground.value=true;runCurrent();assertEquals(1,exchanges);assertEquals(DeviceAuthPhase.EXCHANGING,phase)
        advanceTimeBy(10000);runCurrent();gate.complete(Unit);runCurrent()
        assertEquals(DeviceAuthIssue.EXPIRED,(work.await().exceptionOrNull() as DeviceAuthFailure).issue)
    }

    @Test fun cancellingExchangeRemainsCancellationAndClosesItsWork()=runTest {
        var finished=false;var started=false
        val api=object: DeviceAuthApi {
            override suspend fun begin(deviceName: String)=session()
            override suspend fun validate(session: DeviceSession,code: DeviceConfirmationCode)=error("Not used")
            override suspend fun poll(session: DeviceSession)=DeviceCheck.ExchangeRequired(superToken)
            override suspend fun exchange(session: DeviceSession,result: DeviceCheck.ExchangeRequired): MusicAccess {
                started=true;try{awaitCancellation()}finally{finished=true}
            }
        }
        val work=async{awaitDeviceAccess(api,session(),MutableStateFlow(true),MutableStateFlow(true),{testScheduler.currentTime}){}}
        runCurrent();assertTrue(started);work.cancelAndJoin();assertTrue(finished);assertTrue(work.isCancelled)
    }
}
