package app.alcovaibe.vkmusicexport.music

import app.alcovaibe.vkmusicexport.data.ScanStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class NativeProtocolTest {
    private val track="""{"owner_id":12,"id":34,"title":"Название","artist":"Исполнитель","duration":2}"""
    private fun json(s: String)=Json.parseToJsonElement(s)
    private fun failure(code: MusicError,block:()->Unit){assertEquals(code,assertThrows(MusicFailure::class.java,block).code)}
    @Test fun samplesPreserveDuplicatePositionsUnavailableAndNeverClaimFullCollection() {
        val page=NativeProtocol.page(json("""{"count":2,"items":[$track,$track]}"""))
        val source=NativeProtocol.sampleSource(page,12,null)
        assertEquals(2,source.entries.size);assertEquals(listOf(1,2),source.entries.map{it.position})
        assertEquals(ScanStatus.PARTIAL,source.status)
        val unavailable=track.dropLast(1)+",\"is_restricted\":true}"
        assertTrue(NativeProtocol.page(json("""{"count":1,"items":[$unavailable]}""")).tracks.single().restricted)
    }
    @Test fun malformedShapesNumbersMetadataAndDeepResponsesGetFixedErrors() {
        for(raw in listOf("[]","{}","{\"error\":{\"error_code\":[]}}","[".repeat(33)+"]".repeat(33)))
            failure(MusicError.RESPONSE){NativeProtocol.envelope(raw)}
        failure(MusicError.RESPONSE){NativeProtocol.page(json("""{"count":"1","items":[$track]}"""))}
        failure(MusicError.RESPONSE){NativeProtocol.page(json("""{"count":1,"items":[{}]}"""))}
        failure(MusicError.RESPONSE){NativeProtocol.page(json("""{"count":0,"items":[$track]}"""))}
        failure(MusicError.RESPONSE){NativeProtocol.owner(json("""[{"id":"12"}]"""))}
    }
    @Test fun remoteErrorMessagesAndParametersNeverEnterExceptionText() {
        val secret="PRIVATE_TOKEN_PHONE_COOKIE"
        for((code,expected) in listOf(5 to MusicError.ACCESS,15 to MusicError.DENIED,14 to MusicError.CAPTCHA,17 to MusicError.VALIDATION,6 to MusicError.RATE_LIMIT,3 to MusicError.METHOD)) {
            val e=assertThrows(MusicFailure::class.java){NativeProtocol.envelope("""{"error":{"error_code":$code,"error_msg":"$secret","request_params":["$secret"]}}""")}
            assertEquals(expected,e.code);assertFalse(e.toString().contains(secret));assertNull(e.cause)
        }
    }
    @Test fun metadataCatalogDoesNotSerializeAccessKeysOrGuessAlbumType() {
        val catalog=NativeProtocol.catalog(json("""{"count":1,"items":[{"owner_id":-12,"id":34,"title":"Сохранённое","count":2,"access_key":"SECRET","type":1,"original":{"owner_id":56,"playlist_id":78}}]}"""))
        val list=catalog.lists.single();assertEquals("playlist:-12:34",list.key);assertEquals(56L,list.originalOwner)
        assertFalse(list.toString().contains("SECRET"))
        val source=NativeProtocol.sampleSource(NativePage(emptyList(),2),12,list)
        assertFalse(source.toString().contains("SECRET"));assertFalse(source.url.contains('?'))
    }
    @Test fun cdnBoundaryRejectsSchemesCredentialsPortsFragmentsAndLookalikes() {
        for(raw in listOf("http://a.userapi.com/f","https://userapi.com.evil.test/f","https://userapi.com@evil.test/f",
            "https://secret@a.userapi.com/f","https://a.userapi.com:8443/f","https://a.userapi.com/f#secret","https://127.0.0.1/f","https://a.userapi.com\\@evil.test/f"))
            failure(MusicError.MEDIA_URL){MediaAddress.require(raw)}
        assertEquals("https://a.userapi.com/path/part?signature=SECRET",MediaAddress.resolve("https://a.userapi.com/path/list?x=1","part?signature=SECRET"))
    }
    @Test fun credentialValidationRejectsWrongServiceAndHeadersButAcceptsVkTokenShape() {
        assertTrue(NativeProtocol.validAccess("vk1.a."+"x".repeat(100),"Original Client/1"))
        assertFalse(NativeProtocol.validAccess("github_pat_"+"x".repeat(90),"Client"))
        assertFalse(NativeProtocol.validAccess("x".repeat(64),"Client\r\nCookie: SECRET"))
        assertFalse(NativeProtocol.validAccess("https://vk.com/#access_token=SECRET","Client"))
        assertFalse(MusicAccess("x".repeat(64),"SECRET_AGENT").toString().contains("SECRET"))
    }
    @Test fun realRequestBuilderUsesOnlyFixedReadOnlyApiAndBodyCredentialsAndSafeDiagnostics()=runBlocking {
        val token="vk1.a."+"SECRET".repeat(15);val events=mutableListOf<String>();val requests=mutableListOf<Request>()
        val client=NetworkClients.api().newBuilder().addInterceptor {chain->
            val r=chain.request();requests.add(r)
            val body=when(r.url.encodedPath.substringAfterLast('/')) {
                "users.get"->"""{"response":[{"id":12}]}"""
                "audio.get"->"""{"response":{"count":1,"items":[$track]}}"""
                else->error("Unexpected method")
            }
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api=VkAudioClient(client){t,f->events.add(t+f.toString())}
        val probe=api.library(MusicAccess(token,"Original Client/1"));assertEquals(12L,probe.owner)
        assertEquals(2,requests.size)
        for(r in requests) {
            assertEquals("https",r.url.scheme);assertEquals("api.vk.com",r.url.host);assertNull(r.url.query)
            assertEquals("POST",r.method);assertNull(r.header("Cookie"));assertNull(r.header("Authorization"))
            val form=r.body as FormBody;assertTrue((0 until form.size).any{form.name(it)=="access_token"&&form.value(it)==token})
        }
        assertFalse(events.joinToString().contains(token));assertFalse(events.joinToString().contains("Название"))
        assertFalse(client.followRedirects);assertFalse(client.followSslRedirects)
    }
}
