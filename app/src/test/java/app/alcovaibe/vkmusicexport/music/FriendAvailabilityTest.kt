package app.alcovaibe.vkmusicexport.music

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class FriendAvailabilityTest {
    private fun friend(id: Long,name: String="Friend")=MusicFriend(id,name,"User",handle="@user$id")

    @Test fun confirmedResultsExpireAfterFifteenMinutesAndIgnoreClockRollback() {
        var now=1_000_000L
        val cache=FriendAvailabilityCache(nowMs={now})
        cache.activate("session-a",12L)
        cache.put(34,FriendAudioAccess.AVAILABLE)
        cache.put(35,FriendAudioAccess.DENIED)
        cache.put(36,FriendAudioAccess.CHECK_FAILED)
        assertEquals(FriendAudioAccess.AVAILABLE,cache.get(34))
        assertEquals(FriendAudioAccess.DENIED,cache.get(35))
        assertNull(cache.get(36))
        now+=14*60_000L
        assertEquals(FriendAudioAccess.AVAILABLE,cache.get(34))
        now+=60_000L
        assertNull(cache.get(34));assertNull(cache.get(35))
        cache.put(34,FriendAudioAccess.AVAILABLE)
        now-=5_000L
        assertNull(cache.get(34))
    }

    @Test fun cachedPermissionsNeverLeakAcrossAccountsOrSessions() {
        val cache=FriendAvailabilityCache(nowMs={1000L})
        cache.activate("session-a",12)
        cache.put(34,FriendAudioAccess.DENIED)
        cache.activate("session-a",12)
        assertEquals(FriendAudioAccess.DENIED,cache.get(34))
        cache.activate("session-b",12)
        assertNull(cache.get(34))
        cache.put(34,FriendAudioAccess.AVAILABLE)
        cache.activate("session-b",13)
        assertNull(cache.get(34))
        cache.put(35,FriendAudioAccess.AVAILABLE)
        assertEquals(mapOf(35L to FriendAudioAccess.AVAILABLE),
            cache.snapshot(listOf(friend(34),friend(35))))
        cache.clear()
        assertNull(cache.get(35))
    }

    @Test fun actualVkFriendPreviewRequestsJustOneTrackAndAcceptsAnEmptyLibrary()=runBlocking {
        val requests=mutableListOf<Map<String,String>>()
        val client=NetworkClients.api().newBuilder().addInterceptor{chain->
            val form=chain.request().body as FormBody
            requests.add((0 until form.size).associate{form.name(it) to form.value(it)})
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("Synthetic")
                .body("""{"response":{"count":0,"items":[]}}""".toResponseBody()).build()
        }.build()
        VkAudioClient(client).probeFriendAudio(MusicAccess("a".repeat(64),"CI",accountId=12),34)
        assertEquals(1,requests.size)
        assertEquals("34",requests.single()["owner_id"])
        assertEquals("1",requests.single()["count"])
        assertEquals("0",requests.single()["offset"])
    }

    @Test fun visibleFriendsAndSearchMatchesAreCheckedBeforeBackgroundItems() {
        val friends=listOf(friend(1,"Иван"),friend(2,"Алина"),
            friend(3,"Мария"),friend(4,"Александра"))
        val attempted=mutableSetOf<Long>()
        fun next(visible: List<Long>,query: String): Long? =
            FriendAvailabilityPriority.next(friends,visible,query){it in attempted}?.id
        assertEquals(4L,next(listOf(4,3),"ал"))
        attempted.add(4)
        assertEquals(3L,next(listOf(4,3),"ал"))
        attempted.add(3)
        assertEquals(2L,next(listOf(4,3),"ал"))
        attempted.add(2)
        assertEquals(1L,next(listOf(4,3),"ал"))
        attempted.add(1)
        assertNull(next(listOf(4),"ал"))
    }
}
