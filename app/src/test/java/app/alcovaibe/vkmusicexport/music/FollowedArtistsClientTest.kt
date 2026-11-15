package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class FollowedArtistsClientTest {
    private data class Request(val method: String, val params: Map<String, String>)
    private val access = MusicAccess("a".repeat(64), "CI/1", accountId = 12)
    private val calls = mutableListOf<Request>()
    private val events = mutableListOf<Pair<String, Map<String, Any?>>>()

    private fun client(status: (Request) -> Int = { 200 }, response: (Request) -> String): VkAudioClient {
        val http = NetworkClients.api().newBuilder().addInterceptor { chain ->
            val original = chain.request()
            val form = original.body as FormBody
            val request = Request(original.url.pathSegments.last(),
                (0 until form.size).associate { form.name(it) to form.value(it) }.filterKeys { it != "access_token" })
            calls.add(request)
            Response.Builder().request(original).protocol(Protocol.HTTP_1_1).code(status(request)).message("Synthetic")
                .body(response(request).toResponseBody()).build()
        }.build()
        return VkAudioClient(http, observe = { type, fields -> events.add(type to fields) })
    }

    @Test fun readsSubscriptionCardsDirectlyWithoutAnOwnerOrCatalogueRequest() = runBlocking {
        val api = client {
            """{"response":{"has_next":false,"artists":[
                {"id":"PRIVATE-ARTIST-ID","name":"PRIVATE-NAME","photo":[{"url":"https://sun1.userapi.com/PRIVATE-PHOTO.jpg"}]},
                {"id":"no-longer-followed","name":"Removed","is_followed":false},
                {"id":"PRIVATE-ARTIST-ID","name":"Duplicate"},
                {"id":42,"name":"Numeric ID","is_followed":true}
            ]}}"""
        }
        val actual = api.followedArtists(access, 12)
        assertEquals(listOf("PRIVATE-ARTIST-ID", "42"), actual.map { it.id })
        assertEquals("https://sun1.userapi.com/PRIVATE-PHOTO.jpg", actual.first().photo)
        assertEquals(listOf(Request("audio.getFollowedArtists", mapOf("limit" to "150", "v" to "5.199"))), calls)
        val shape = events.single { it.first == "artist_catalog_shape" }.second.getValue("shape").toString()
        assertTrue(shape.contains("has_next"))
        assertFalse(shape.contains("PRIVATE"))
        assertFalse(shape.contains(access.token))
    }

    @Test fun readsTheLargerSnapshotUntilTheServerConfirmsItIsComplete() = runBlocking {
        val api = client { request ->
            if (request.params["limit"] == "150") """{"response":{"has_next":1,"artists":[{"id":"one","name":"First"}]}}"""
            else """{"response":{"has_next":0,"artists":[{"id":"one","name":"First"},{"id":"two","name":"Second"}]}}"""
        }
        val snapshots = mutableListOf<Pair<Int, List<String>>>()
        val actual = api.followedArtists(access, 12) { artists, requests -> snapshots += requests to artists.map { it.id } }
        assertEquals(listOf("150", "300"), calls.map { it.params["limit"] })
        assertEquals(listOf(1 to listOf("one"), 2 to listOf("one", "two")), snapshots)
        assertEquals(listOf("one", "two"), actual.map { it.id })
    }

    @Test fun aContinuingResponseAtTheLimitReportsAPartialList() = runBlocking {
        val api = client { """{"response":{"has_next":true,"artists":[{"id":"one","name":"First"}]}}""" }
        val snapshots = mutableListOf<List<String>>()
        try {
            api.followedArtists(access, 12) { rows, _ -> snapshots += rows.map { it.id } }
            fail("The last response still advertises more artists.")
        } catch (failure: ArtistLoadingFailure) {
            assertEquals(ArtistFailureReason.RESULT_LIMIT, failure.reason)
            assertEquals(MusicError.INCOMPLETE, failure.code)
        }
        assertEquals(listOf("150", "300", "600", "1000"), calls.map { it.params["limit"] })
        assertEquals(listOf("one"), snapshots.last())
    }

    @Test fun anExplicitEmptyResponseDoesNotTriggerTheCatalogueFallback() = runBlocking {
        val api = client { """{"response":{"has_next":false,"artists":null}}""" }
        assertTrue(api.followedArtists(access, 12).isEmpty())
        assertEquals(listOf("audio.getFollowedArtists"), calls.map { it.method })
    }

    @Test fun aMalformedDirectResponseIsReportedWithoutPublishingAnEmptyList() = runBlocking {
        val api = client { """{"response":{"artists":[]}}""" }
        try {
            api.followedArtists(access, 12) { _, _ -> fail("An invalid response must not publish data.") }
            fail("The response must have a has_next flag.")
        } catch (failure: ArtistLoadingFailure) {
            assertEquals(ArtistFailureReason.UNEXPECTED_FORMAT, failure.reason)
            assertEquals("audio.getFollowedArtists", failure.method)
        }
        assertEquals(1, calls.size)
    }

    @Test fun anUnsupportedMethodOrVersionFallsBackToAnExplicitCatalogueSection() = runBlocking {
        for (code in listOf(3, 8)) {
            calls.clear()
            val api = client { request ->
                when (request.method) {
                    "audio.getFollowedArtists" -> """{"error":{"error_code":${code}}}"""
                    "catalog.getAudio" -> """{"response":{"catalog":{"sections":[{"id":"subscribed","title":"Мои исполнители"}]}}}"""
                    "catalog.getSection" -> """{"response":{"artists":[{"id":"one","name":"First"},{"id":"recommendation","name":"Suggestion"}],
                        "section":{"id":"subscribed","blocks":[{"artists_ids":["one"]}]}}}"""
                    else -> error("Unexpected method")
                }
            }
            assertEquals(listOf("one"), api.followedArtists(access, 12).map { it.id })
            assertEquals(listOf("audio.getFollowedArtists", "catalog.getAudio", "catalog.getSection"), calls.map { it.method })
            assertEquals("subscribed", calls.last().params["section_id"])
        }
    }

    @Test fun aDeniedMethodKeepsItsApiErrorWhenMyMusicHasNoSubscriptionSection() = runBlocking {
        val api = client { request ->
            when (request.method) {
                "audio.getFollowedArtists" -> """{"error":{"error_code":15}}"""
                "catalog.getAudio" -> """{"response":{"catalog":{"default_section":"home","sections":[
                    {"id":"home","title":"Главная","blocks":[{"audios_ids":["12_1"]}]},
                    {"id":"mine","title":"Моя музыка"}]}}}"""
                "catalog.getSection" -> """{"response":{"links":[{"title":"Подборка"}],
                    "section":{"id":"mine","title":"Моя музыка","blocks":[{"audios_ids":["12_1"]}]}}}"""
                else -> error("Unexpected method")
            }
        }
        val progress = mutableListOf<Int>()
        try {
            api.followedArtists(access, 12) { _, requests -> progress += requests }
            fail("The catalogue only contains My Music.")
        } catch (failure: ArtistLoadingFailure) {
            assertEquals(ArtistFailureReason.REQUEST, failure.reason)
            assertEquals("audio.getFollowedArtists", failure.method)
            assertEquals(15, failure.remoteCode)
        }
        assertEquals(listOf("audio.getFollowedArtists", "catalog.getAudio", "catalog.getSection", "catalog.getAudio", "catalog.getSection"), calls.map { it.method })
        assertEquals(listOf(2, 3, 4, 5), progress)
    }
}
