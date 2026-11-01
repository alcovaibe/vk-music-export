package app.alcovaibe.vkmusicexport.music

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class PlaybackNetworkTest {
    @Test fun redirectsKeepRangeRequestsAndNeverSendApiCredentials() {
        val requests=mutableListOf<Request>()
        val client=PlaybackNetwork.client().newBuilder().addInterceptor {chain->
            val req=chain.request();requests.add(req)
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).message("Fixture")
                .code(if(requests.size==1)302 else 206).apply {if(requests.size==1)header("Location","https://audio.vkuseraudio.net/part.mp3")}
                .body("audio".toResponseBody()).build()
        }.build()
        client.newCall(Request.Builder().url("https://audio.userapi.com/song.mp3").header("Range","bytes=1200-").build()).execute().use {
            assertEquals(206,it.code)
        }
        assertEquals(2,requests.size)
        assertTrue(requests.all{it.header("Range")=="bytes=1200-"&&it.header("Authorization")==null&&it.header("Cookie")==null})
    }
    @Test fun redirectOutsideTheMediaAllowlistIsRejectedBeforeASecondRequest() {
        var requests=0
        val client=PlaybackNetwork.client().newBuilder().addInterceptor {chain->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Fixture")
                .header("Location","https://private.example/audio.mp3").body("".toResponseBody()).build()
        }.build()
        try {client.newCall(Request.Builder().url("https://audio.userapi.com/song.mp3").build()).execute().close();fail("Unsafe redirect was accepted")}
        catch(e: MusicFailure){assertEquals(MusicError.MEDIA_URL,e.code)}
        assertEquals(1,requests)
    }
}
