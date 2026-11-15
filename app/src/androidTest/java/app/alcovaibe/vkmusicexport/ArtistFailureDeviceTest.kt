package app.alcovaibe.vkmusicexport

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.alcovaibe.vkmusicexport.music.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArtistFailureDeviceTest {
    @Test fun actualClientDistinguishesVkDenialHttpFailureAndMissingSectionWithoutDisplayingServerText()=runBlocking {
        val access=MusicAccess("a".repeat(64),"CI/1",accountId=12)
        val cases=listOf(
            200 to """{"error":{"error_code":15,"error_msg":"PRIVATE SERVER TEXT"}}""",
            200 to """{"error":{"error_code":3,"error_msg":"PRIVATE SERVER TEXT"}}""",
            403 to "<html>PRIVATE SERVER TEXT</html>",
            200 to """{"response":{"artists":[{"id":"secret-id","name":"PRIVATE ARTIST"}],"catalog":{"sections":[]}}}"""
        )
        for((index,sample)in cases.withIndex()) {
            val fields=mutableListOf<Set<String>>()
            val client=NetworkClients.api().newBuilder().addInterceptor{chain->
                val request=chain.request()
                val body=request.body as FormBody
                fields.add((0 until body.size).map{body.name(it)}.toSet())
                val (status,reply)=if(request.url.pathSegments.last()==NativeProtocol.FOLLOWED_ARTISTS&&index==3)
                    200 to """{"error":{"error_code":3,"error_msg":"PRIVATE SERVER TEXT"}}""" else sample
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Synthetic")
                    .body(reply.toResponseBody()).build()
            }.build()
            try {VkAudioClient(client,observe={_,_->}).followedArtists(access,12);fail("A precise failure is required")}
            catch(e: ArtistLoadingFailure) {
                val text=ArtistFailureText.describe(e)
                assertFalse(text.contains("PRIVATE"));assertFalse(text.contains("secret-id"));assertFalse(text.contains(access.token))
                when(index) {
                    0->{assertEquals(15,e.remoteCode);assertEquals(NativeProtocol.FOLLOWED_ARTISTS,e.method);assertTrue(text.contains("API 15"))}
                    1->{assertEquals(3,e.remoteCode);assertTrue(text.contains("API 3"))}
                    2->{assertNull(e.remoteCode);assertEquals(403,e.httpStatus);assertTrue(text.contains("HTTP 403"))}
                    else->{assertEquals(ArtistFailureReason.REQUEST,e.reason);assertEquals(3,e.remoteCode);assertEquals(NativeProtocol.FOLLOWED_ARTISTS,e.method);assertEquals(3,fields.size)}
                }
                assertTrue(fields.all{"current_user" !in it})
            }
        }
    }
}
