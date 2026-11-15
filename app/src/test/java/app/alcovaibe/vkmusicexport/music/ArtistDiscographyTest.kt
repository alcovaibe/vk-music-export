package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.test.runTest
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ArtistDiscographyTest {
    private val artist = MusicArtist("artist-1", "Artist")
    private val access = MusicAccess("a".repeat(64), "CI", accountId = 12)

    private fun track(id: Int, title: String, performer: String = "Artist", credit: String = "") =
        """{"owner_id":12,"id":$id,"artist":"$performer","title":"$title","duration":180$credit}"""

    @Test fun artistQueuesReadCataloguePointersAndAllReleasePages() = runTest {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        val hit = track(1, "Hit")
        val later = track(2, "Later")
        val guest = track(3, "Together", "Band", ""","featured_artists":[{"id":"artist-1","name":"Artist"}]""")
        val unrelated = track(4, "Other track", "Band")
        val duplicate = track(5, "Hit")
        val albumOnly = track(6, "Album cut")
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val method = request.url.pathSegments.last()
            val form = request.body as FormBody
            val params = (0 until form.size).associate { form.name(it) to form.value(it) }
            requests += method to params
            val json = when (method) {
                "catalog.getAudioArtist" -> """{"catalog":{"default_section":"artist-main","sections":[]}}"""
                "catalog.getSection" -> """{
                    "section":{"id":"artist-main","blocks":[
                        {"layout":{"header":{"title":"Популярные треки"}},"audios_ids":["12_2","12_1"]},
                        {"title":"Альбомы и синглы","id":"own","next_from":"next","playlists_ids":["12_10"]},
                        {"title":"Участие в релизах","playlists_ids":["12_20"]},
                        {"title":"Вам понравится","playlists_ids":["12_30"],"audios_ids":["12_4"]}
                    ]},
                    "audios":[$hit,$later,$unrelated],
                    "artists":[{"id":"artist-1","name":"Artist","photo":[{"url":"https://sun1.userapi.com/artist.jpg"}]}],
                    "playlists":[
                        {"owner_id":12,"id":10,"title":"Album","count":2,"album_type":"album","year":2020},
                        {"owner_id":12,"id":20,"title":"Guest","count":2,"main_artists":[{"name":"Band"}]},
                        {"owner_id":12,"id":30,"title":"Recommendation","count":1}
                    ]
                }"""
                "catalog.getBlockItems" -> """{
                    "block":{"id":"own","playlists_ids":["12_11"]},
                    "playlists":[{"owner_id":12,"id":11,"title":"Single","count":1,"album_type":"single","release_date":"2026-11-06"}]
                }"""
                "audio.getAudiosByArtist" -> {
                    if (params["offset"] == "0") """{"count":2,"items":[$hit]}""" else """{"count":2,"items":[$later]}"""
                }
                "audio.get" -> when (params["playlist_id"] to params["offset"]) {
                    "10" to "0" -> """{"count":2,"items":[$duplicate]}"""
                    "10" to "1" -> """{"count":2,"items":[$albumOnly]}"""
                    "11" to "0" -> """{"count":1,"items":[$later]}"""
                    "20" to "0" -> """{"count":2,"items":[$guest,$unrelated]}"""
                    else -> error("Unexpected release page")
                }
                else -> error("Unexpected VK method")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"response":$json}""".toResponseBody()).build()
        }.build()

        val result = VkAudioClient(http, observe = { _, _ -> }).artistDetails(access, artist)
        assertTrue(result.complete)
        assertEquals("https://sun1.userapi.com/artist.jpg", result.artist.photo)
        assertEquals(listOf("12_2", "12_1"), result.tracks(ArtistTracks.POPULAR)?.map(NativeTrack::id))
        assertEquals(listOf("Album cut", "Hit", "Later", "Together"), result.tracks.tracks.map(NativeTrack::title))
        assertEquals("Album", result.tracks.tracks.single { it.title == "Hit" }.album?.title)
        assertEquals(listOf("Single", "Album"), result.releases.map { it.playlist.title })
        assertEquals("Сингл", result.latest?.type)
        assertEquals("2026-11-06", result.latest?.date.toString())
        assertEquals(listOf("Together"), result.appearances.single().featuredTracks)
        assertEquals("Band", result.appearances.single().performer)
        assertTrue(requests.any { (method, fields) -> method == "catalog.getSection" && fields["section_id"] == "artist-main" })
        assertTrue(requests.any { (method, fields) -> method == "catalog.getBlockItems" && fields["start_from"] == "next" })
    }
}
