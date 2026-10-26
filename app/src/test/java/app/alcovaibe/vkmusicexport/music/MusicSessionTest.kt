package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MusicSessionTest {
    private val now=1_800_000_000_000L
    private val token="vk1.a."+"OLD_SECRET_".repeat(8)
    private val parent="vk1.a."+"PARENT_SECRET_".repeat(8)
    private fun access(expiry: Long?=now+120_000,renew: Boolean=true,id: String="old")=MusicAccess(token,"Client/1",expiry,
        if(renew)WebRenewal(parent,12,emptyList()) else null,credentialId=id,persisted=true)
    private fun fresh()=MusicAccess("vk1.a."+"NEW_SECRET_".repeat(8),"Client/1",now+3_600_000,
        WebRenewal(parent,12,emptyList()),credentialId="fresh",persisted=true)
    private class Store(var value: MusicAccess?): MusicCredentialStore {
        override fun read()=value
        override fun replace(expected: MusicAccess,next: MusicAccess): Boolean {
            if(value?.credentialId!=expected.credentialId)return false
            value=next;return true
        }
    }
    private fun manager(store: Store,renew: suspend(MusicAccess)->MusicAccess,verify: suspend(MusicAccess)->NativeProbe={NativeProbe(12,NativePage(emptyList(),0))},clock: ()->Long={now})=
        MusicSessionManager(store,object: MusicRenewer{override suspend fun renew(access: MusicAccess)=renew(access)},verify,clock,io=Dispatchers.Unconfined)
    @Test fun legacyCodecNeverInventsExpiryAndKeepsAllRenewalSecretsInsideTheEnvelopePayload() {
        val old=MusicCredentialCodec.decode("""{"token":"$token","agent":"Client/1"}""")
        assertNull(old.expiresAtMs);assertNull(old.renewal);assertTrue(old.persisted)
        val value=access().withSession(verifiedAt=now)
        val decoded=MusicCredentialCodec.decode(MusicCredentialCodec.encode(value))
        assertEquals(value.expiresAtMs,decoded.expiresAtMs);assertEquals(parent,decoded.renewal!!.parent)
        assertEquals(now,decoded.lastVerifiedAtMs);assertFalse(decoded.toString().contains("SECRET"))
        assertFalse(decoded.renewal.toString().contains("SECRET"))
    }
    @Test fun expiryDistinguishesUnknownRelativeAndAbsoluteAndRejectsBadTypes() {
        fun parse(raw: String)=MusicCredentialCodec.expiry(Json.parseToJsonElement(raw).jsonObject,now)
        assertNull(parse("{}"));assertNull(parse("{\"expires\":0}"))
        assertEquals(now+3600000,parse("{\"expires_in\":3600}"))
        assertEquals(now+3600000,parse("{\"expires\":3600}"))
        assertEquals(now+3600000,parse("{\"expires\":${now/1000+3600}}"))
        for(value in listOf("-1","\"3600\"","true","null","9223372036854775807"))
            assertThrows(MusicFailure::class.java){parse("{\"expires_in\":$value}")}
    }
    @Test fun concurrentForegroundAndQueueRequestsRenewOnceAndVerifyBeforePersisting()=runTest {
        val old=access();val store=Store(old);val gate=CompletableDeferred<Unit>();var calls=0;var probes=0
        val m=manager(store,{calls++;gate.await();fresh()},{assertEquals("old",store.value!!.credentialId);probes++;NativeProbe(12,NativePage(emptyList(),0))})
        val requests=List(8){async{m.request(old){it.token}}}
        runCurrent();assertEquals(1,calls);assertEquals(SessionStatus.RENEWING,m.state.value.status)
        gate.complete(Unit);requests.awaitAll().forEach{assertEquals(fresh().token,it)}
        assertEquals(1,calls);assertEquals(1,probes);assertEquals("fresh",store.value!!.credentialId)
        assertEquals(now,store.value!!.lastVerifiedAtMs)
    }
    @Test fun expiryAndAccessErrorsWithoutRenewalRequireLoginAndNeverCreateNetworkWork()=runTest {
        var calls=0
        val m=manager(Store(access(now-1,false)),{calls++;fresh()})
        try{m.current();fail()}catch(e: MusicFailure){assertEquals(MusicError.ACCESS,e.code)}
        assertEquals(0,calls);assertEquals(SessionStatus.LOGIN_REQUIRED,m.state.value.status)
    }
    @Test fun remoteRevocationRenewsAndRetriesOnlyOnce()=runTest {
        val old=access(expiry=now+3_600_000);val store=Store(old);var renewals=0;var requests=0
        val m=manager(store,{renewals++;fresh()})
        try{m.request(old){requests++;throw MusicFailure(MusicError.ACCESS,5)};fail()}
        catch(e: MusicFailure){assertEquals(5,e.remoteCode)}
        assertEquals(1,renewals);assertEquals(2,requests)
        assertTrue(store.value!!.rejected)
        try{m.current();fail()}catch(_: MusicFailure){}
        assertEquals(1,renewals);assertEquals(SessionStatus.LOGIN_REQUIRED,m.state.value.status)
    }
    @Test fun transientFailureKeepsValidAccessAndUsesFiveMinuteBackoff()=runTest {
        val old=access();val store=Store(old);var calls=0;var clock=now
        val m=manager(store,{calls++;throw MusicFailure(MusicError.NETWORK)},clock={clock})
        repeat(5){assertSame(old,m.current())};assertEquals(1,calls)
        assertFalse(store.value!!.renewalBlocked);assertEquals(SessionStatus.RETRY,m.state.value.status)
        clock+=300_001
        try{m.current();fail()}catch(e: MusicFailure){assertEquals(MusicError.NETWORK,e.code)}
        assertEquals(2,calls);assertSame(old,store.value)
    }
    @Test fun malformedRenewalOfExpiredAccessPausesInsteadOfSkippingTheDownloadQueue()=runTest {
        val store=Store(access(now-1));var calls=0
        val m=manager(store,{calls++;throw MusicFailure(MusicError.RESPONSE)})
        repeat(2) {
            try{m.current();fail()}catch(e: MusicFailure){assertEquals(MusicError.ACCESS,e.code)}
        }
        assertEquals(1,calls);assertEquals(token,store.value!!.token)
    }
    @Test fun deniedOrDifferentAccountRenewalNeverOverwritesThePreviousToken()=runTest {
        for(denied in listOf(true,false)) {
            val old=access(now-1);val store=Store(old)
            val m=manager(store,{fresh()},{if(denied)throw MusicFailure(MusicError.DENIED);NativeProbe(99,NativePage(emptyList(),0))})
            try{m.current();fail()}catch(_: MusicFailure){}
            assertEquals(token,store.value!!.token);assertTrue(store.value!!.renewalBlocked)
            assertEquals(SessionStatus.LOGIN_REQUIRED,m.state.value.status)
        }
    }
    @Test fun signOutOrNewLoginDuringRefreshCannotBeUndoneByTheLateResponse()=runTest {
        for(replacement in listOf(null,access(expiry=now+900_000,id="another-login"))) {
            val store=Store(access());val gate=CompletableDeferred<Unit>();val started=CompletableDeferred<Unit>()
            val m=manager(store,{started.complete(Unit);gate.await();fresh()})
            val job=async{runCatching{m.current()}}
            started.await();store.value=replacement;gate.complete(Unit)
            assertTrue(job.await().isFailure);assertSame(replacement,store.value)
        }
    }
    @Test fun cancelledRefreshDoesNotSaveAndAnInitialPairingCandidateUsesItsOwnToken()=runTest {
        val old=access();val store=Store(old);var calls=0
        val m=manager(store,{calls++;awaitCancellation()})
        val work=launch{m.current()};runCurrent();work.cancelAndJoin();assertSame(old,store.value)
        val candidate=MusicAccess("vk1.a."+"CANDIDATE_".repeat(8),"Client/1")
        assertEquals(candidate.token,m.request(candidate){it.token});assertEquals(1,calls)
    }
    @Test fun actualReissueClientPostsOnlyToLoginAndPreservesExpiryWithoutLeakingSecrets()=runTest {
        val requests=mutableListOf<Request>()
        val client=NetworkClients.api().newBuilder().addInterceptor {chain->
            val r=chain.request();requests.add(r)
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""{"type":"okay","data":{"access_token":"${fresh().token}","user_id":12,"expires":3600}}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val renewed=VkMusicRenewer(client,{now}).renew(access())
        assertEquals(now+3_600_000,renewed.expiresAtMs);assertEquals(parent,renewed.renewal!!.parent)
        val request=requests.single();assertEquals("login.vk.com",request.url.host);assertEquals("POST",request.method)
        assertEquals("web_token",request.url.queryParameter("act"));assertFalse(request.url.toString().contains(parent))
        val form=request.body as FormBody;val fields=(0 until form.size).associate{form.name(it) to form.value(it)}
        assertEquals(parent,fields["access_token"]);assertEquals(WebExchangeProtocol.WEB_TOKEN_CLIENT,fields["app_id"])
        assertNull(request.header("Authorization"));assertFalse(renewed.toString().contains("SECRET"))
    }
}
