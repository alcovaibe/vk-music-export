package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class SearchAndLinkedMusicTest {
    private val access = MusicAccess("a".repeat(64), "CI", accountId = 12)
    private val cdn = "https://cs1.vkuseraudio.net/file.mp3"
    private val record = """{"owner_id":-20,"id":8,"title":"Track","artist":"Artist","duration":180,"access_key":"synthetic","url":"$cdn"}"""
    private data class Call(val method: String, val fields: Map<String, String>)

    private fun api(respond: (Call) -> String): VkAudioClient {
        val client = NetworkClients.api().newBuilder().addInterceptor { chain ->
            val request = chain.request()
            val form = request.body as FormBody
            val fields = (0 until form.size).associate { form.name(it) to form.value(it) }
            val call = Call(request.url.pathSegments.last(), fields)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
                .body(respond(call).toResponseBody()).build()
        }.build()
        return VkAudioClient(client) { _, _ -> }
    }

    @Test fun searchStreamIsAvailableToAnotherClientInSameReadSessionOnly() = runBlocking {
        val search = api { call ->
            assertEquals("audio.search", call.method)
            """{"response":{"count":1,"items":[$record]}}"""
        }
        val track = search.searchMusic(access, "Artist Track", 0).tracks.single()
        val requests = mutableListOf<Call>()
        val player = api { call -> requests.add(call); """{"response":[$record]}""" }
        assertEquals("synthetic", track.accessKey)
        assertEquals(cdn, player.resolve(access, track).url)
        assertTrue(requests.isEmpty())

        val other = MusicAccess("b".repeat(64), "CI", accountId = 13)
        assertEquals(cdn, player.resolve(other, track).url)
        assertEquals("-20_8_synthetic", requests.single().fields["audios"])
    }

    @Test fun addUsesSearchRecordOwnerAndTheVerifiedPersonalAccount() = runBlocking {
        val requests = mutableListOf<Call>()
        val client = api { call ->
            requests.add(call)
            if (call.method == "users.get") """{"response":[{"id":12}]}""" else """{"response":55}"""
        }
        val track = NativeTrack("-20_8", "Track", "Artist", 180, accessKey = "synthetic")
        assertEquals(55L, client.addLibraryTrack(access, 12, track))
        assertEquals(listOf("users.get", "audio.add"), requests.map { it.method })
        val fields = requests.last().fields
        assertEquals("-20", fields["owner_id"])
        assertEquals("8", fields["audio_id"])
        assertEquals("synthetic", fields["access_key"])
        assertNull(fields["group_id"])

        var writes = 0
        val wrong = api { call ->
            if (call.method == "audio.add") writes++
            """{"response":[{"id":13}]}"""
        }
        try {
            wrong.addLibraryTrack(access, 12, track)
            fail("A different account was accepted")
        } catch (error: MusicFailure) {
            assertEquals(MusicError.ACCOUNT_CHANGED, error.code)
        }
        assertEquals(0, writes)
    }

    @Test fun openingTrackSupportsPublicIdFallbackAndRetainsLinkForSharing() = runBlocking {
        val requests = mutableListOf<Call>()
        val client = api { call ->
            requests.add(call)
            if (call.fields["audios"] == "-20_8_synthetic") """{"response":[]}""" else """{"response":[$record]}"""
        }
        val link = VkMusicLinks.parse("https://vk.ru/audio-20_8_synthetic") as VkMusicLink.Track
        val entry = readLinkedMusic(client, access, link).entries().single()
        assertEquals(listOf("-20_8_synthetic", "-20_8"), requests.map { it.fields["audios"] })
        assertEquals("synthetic", entry.download?.track?.accessKey)
        assertEquals(cdn, client.resolve(access, entry.track).url)
        assertEquals(2, requests.size)
    }

    @Test fun collectionLoadsAllPagesWithTheLinkKeyAndPreservesDownloadOrder() = runBlocking {
        val requests = mutableListOf<Call>()
        val client = api { call ->
            requests.add(call)
            when (call.method) {
                "audio.getPlaylistById" -> """{"response":{"owner_id":-20,"id":3,"title":"Album","count":2,"type":1}}"""
                else -> {
                    val row = if (call.fields["offset"] == "0") record else record.replace("\"id\":8", "\"id\":9")
                    """{"response":{"count":2,"items":[$row]}}"""
                }
            }
        }
        val link = VkMusicLinks.parse("https://vk.ru/music?z=audio_playlist-20_3&access_key=collectionkey")!!
        val result = readLinkedMusic(client, access, link)
        assertEquals(listOf("audio.getPlaylistById", "audio.get", "audio.get"), requests.map { it.method })
        assertTrue(requests.all { it.fields["access_key"] == "collectionkey" })
        assertEquals(PlaylistKind.ALBUM, result.list?.kind)
        assertEquals(listOf("-20_8", "-20_9"), result.collectionTasks().map { it.track.id })
        assertEquals(listOf(1, 2), result.collectionTasks().map { it.position })
    }
}
