package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class TrackAvailabilityTest {
    private val access=MusicAccess("a".repeat(64),"Synthetic")
    private val stale=NativeTrack("12_1","Synthetic song","Synthetic artist",0,restricted=true)
    private fun response(flags: String="",url: String="https://audio.userapi.com/synthetic.mp3")=Json.parseToJsonElement(
        """[{"owner_id":12,"id":1,"title":"Synthetic song","artist":"Synthetic artist","duration":1,"url":"$url"$flags}]""")
    @Test fun freshAllowedResponseRecoversStaleRestrictionAndDurationWithoutChangingId() {
        val result=NativeProtocol.resolveMedia(response(),stale)
        assertEquals(stale.id,result.track.id);assertEquals(1,result.track.duration);assertFalse(result.track.restricted)
        assertEquals("https://audio.userapi.com/synthetic.mp3",result.url)
        assertFalse(result.toString().contains("userapi"))
    }
    @Test fun explicitRestrictionIsRespectedEvenWhenServerIncludesAUrlAndRegionIsNotGuessed() {
        val error=assertThrows(TrackUnavailable::class.java){NativeProtocol.resolveMedia(response(",\"is_restricted\":true"),stale)}
        assertEquals(TrackAccessIssue.RESTRICTED,error.issue);assertEquals(MusicError.UNAVAILABLE,error.code)
    }
    @Test fun emptyMissingStreamAndMalformedIdentityAreDifferentAndUnsafeUrlsStayBlocked() {
        assertEquals(TrackAccessIssue.NOT_RETURNED,assertThrows(TrackUnavailable::class.java){NativeProtocol.resolveMedia(JsonArray(emptyList()),stale)}.issue)
        assertEquals(TrackAccessIssue.NO_STREAM,assertThrows(TrackUnavailable::class.java){NativeProtocol.resolveMedia(response(url=""),stale)}.issue)
        assertEquals(MusicError.MEDIA_URL,assertThrows(MusicFailure::class.java){NativeProtocol.resolveMedia(response(url="https://evil.test/audio"),stale)}.code)
        assertEquals(MusicError.RESPONSE,assertThrows(MusicFailure::class.java){NativeProtocol.resolveMedia(response(),stale.copy(id="12_2"))}.code)
        assertEquals(MusicError.RESPONSE,assertThrows(MusicFailure::class.java){NativeProtocol.resolveMedia(response(),stale.copy(duration=20))}.code)
    }
    @Test fun clientUsesTheCurrentAccountAndNormalFixedEndpointWithoutForgedRegionHeaders()=runBlocking {
        val requests=mutableListOf<Request>()
        val client=NetworkClients.api().newBuilder().addInterceptor{chain->requests.add(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
                .body("{\"response\":${response()}}".toResponseBody()).build()
        }.build()
        val resolved=VkAudioClient(client,observe={_,_->}).resolve(access,stale)
        assertEquals(1,resolved.track.duration)
        val request=requests.single();assertEquals("api.vk.com",request.url.host);assertEquals("/method/audio.getById",request.url.encodedPath)
        val body=request.body as FormBody;val fields=(0 until body.size).associate{body.name(it) to body.value(it)}
        assertEquals(setOf("access_token","v","audios"),fields.keys);assertEquals(access.token,fields["access_token"])
        assertNull(request.header("X-Forwarded-For"));assertNull(request.header("Cookie"))
    }
    @Test fun checkUsesOnlyFlaggedOrExplicitFailedTracksAndReportsLinksWithoutDownloadingAudio()=runBlocking {
        val calls=mutableListOf<String>();val good=stale.copy(id="12_2",restricted=false,duration=1);val failed=good.copy(id="12_3")
        val api=object: MusicApi {
            override suspend fun library(access: MusicAccess)=NativeProbe(12,NativePage(listOf(stale,good),2))
            override suspend fun resolve(access: MusicAccess,track: NativeTrack): ResolvedAudio {
                calls.add(track.id);if(track.id==stale.id)throw TrackUnavailable(TrackAccessIssue.NOT_RETURNED)
                return ResolvedAudio(track,"https://audio.userapi.com/synthetic.mp3")
            }
            override suspend fun media(access: MusicAccess,track: NativeTrack)=error("No audio download")
            override suspend fun catalog(access: MusicAccess,owner: Long)=error("Unused")
            override suspend fun playlist(access: MusicAccess,list: NativePlaylist)=error("Unused")
        }
        val rows=AvailabilityCheck(api,pageDelayMs=0).check(access,listOf(failed,failed)){_,_,_->}
        assertEquals(listOf("12_3","12_1"),calls);assertTrue(rows[0].linkAvailable);assertFalse(rows[1].linkAvailable)
        assertEquals(TrackAccessIssue.NOT_RETURNED.label,rows[1].reason)
    }
    @Test fun accessFailureStopsCheckInsteadOfDeclaringEveryTrackRegionBlocked()=runBlocking {
        val api=object: MusicApi {
            override suspend fun library(access: MusicAccess)=NativeProbe(12,NativePage(listOf(stale),1))
            override suspend fun media(access: MusicAccess,track: NativeTrack): String=throw MusicFailure(MusicError.ACCESS,5)
            override suspend fun resolve(access: MusicAccess,track: NativeTrack): ResolvedAudio=throw MusicFailure(MusicError.ACCESS,5)
            override suspend fun catalog(access: MusicAccess,owner: Long)=error("Unused")
            override suspend fun playlist(access: MusicAccess,list: NativePlaylist)=error("Unused")
        }
        try{AvailabilityCheck(api,pageDelayMs=0).check(access,emptyList()){_,_,_->};fail("Must pause")}
        catch(e: MusicFailure){assertEquals(5,e.remoteCode)}
    }
}
