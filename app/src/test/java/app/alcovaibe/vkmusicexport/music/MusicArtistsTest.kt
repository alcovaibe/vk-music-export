package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MusicArtistsTest {
    private fun json(raw: String)=Json.parseToJsonElement(raw)
    @Test fun recommendationsAndNamesInSongsAreNeverInferredAsSubscriptions()=runTest {
        val value=json("""{"artists":[{"id":"1","name":"Подписка"},{"id":"2","name":"Рекомендация"}],"catalog":{"sections":[{"id":"own","blocks":[{"title":"Мои исполнители","artists_ids":["1"]},{"title":"Похожие исполнители","artists_ids":["2"]}]}]}}""")
        val rows=MusicArtists.subscriptions{method,_->assertEquals("catalog.getAudio",method);value}
        assertEquals(listOf("1"),rows.map{it.id})
    }
    @Test fun subscriptionSectionAndBlockCursorsAreLoadedToTheEndAndDuplicatesUseIds()=runTest {
        val calls=mutableListOf<String>()
        val result=MusicArtists.subscriptions{method,p->
            calls.add(method+":"+p.values.joinToString())
            when(method) {
                "catalog.getAudio"->json("""{"catalog":{"sections":[{"id":"own","blocks":[{"title":"Исполнители","actions":[{"title":"Исполнители","section_id":"all"}]}]}]}}""")
                "catalog.getSection"->json("""{"artists":[{"id":"1","name":"Одно имя"}],"section":{"id":"all","blocks":[{"id":"b","artists_ids":["1"],"next_from":"cursor"}]}}""")
                else->{assertEquals("cursor",p["start_from"]);json("""{"artists":[{"id":"1","name":"Одно имя"},{"id":"2","name":"Одно имя"}],"block":{"id":"b","artists_ids":["1","2"]}}""")}
            }
        }
        assertEquals(listOf("1","2"),result.map{it.id});assertEquals(3,calls.size)
    }
    @Test fun explicitUnfollowedArtistIsExcludedAndUnsupportedIsNotAnEmptySuccess()=runTest {
        val response=json("""{"artists":[{"id":"1","name":"Первый","is_following":1},{"id":"2","name":"Второй","is_following":0}]}""")
        assertEquals(listOf("1"),MusicArtists.subscriptions{_,_->response}.map{it.id})
        try{MusicArtists.subscriptions{_,_->json("""{"artists":[{"id":"2","name":"Рекомендация"}]}""")};fail("Must report unavailable subscriptions")}
        catch(e: MusicFailure){assertEquals(MusicError.METHOD,e.code)}
    }
    @Test fun realEmptySubscriptionBlockRemainsEmpty()=runTest {
        val rows=MusicArtists.subscriptions{_,_->json("""{"catalog":{"sections":[{"blocks":[{"title":"Мои исполнители","artists_ids":[]}]}]}}""")}
        assertTrue(rows.isEmpty())
    }
    @Test fun missingArtistPoolAndRepeatingCursorFailWithoutPublishingAPartialLibrary()=runTest {
        try{MusicArtists.subscriptions{_,_->json("""{"catalog":{"sections":[{"blocks":[{"title":"Исполнители","artists_ids":["missing"]}]}]}}""")};fail("Missing data must fail")}
        catch(e: MusicFailure){assertEquals(MusicError.INCOMPLETE,e.code)}
        try{MusicArtists.subscriptions{_,_->json("""{"block":{"id":"loop","title":"Исполнители","next_from":"same","artists_ids":[]}}""")};fail("Cursor loop must fail")}
        catch(e: MusicFailure){assertEquals(MusicError.INCOMPLETE,e.code)}
    }
    @Test fun artistAlbumsUseOnlyReleaseBlocksNotRecommendations()=runTest {
        val response=json("""{"playlists":[{"owner_id":12,"id":1,"title":"Альбом","count":0},{"owner_id":12,"id":2,"title":"Рекомендация","count":0}],"catalog":{"sections":[{"blocks":[{"title":"Альбомы","playlists_ids":["12_1"]},{"title":"Вам понравится","playlists_ids":["12_2"]}]}]}}""")
        val rows=MusicArtists.albums(response){_,_->error("No extra request")}
        assertEquals(listOf("Альбом"),rows.map{it.title});assertEquals(PlaylistKind.ALBUM,rows.single().kind)
    }
    @Test fun thumbnailsAcceptOnlyTrustedHttpsImageHosts() {
        assertNull(MusicArtists.photo(json("""{"url":"https://example.com/image.jpg"}""")))
        assertNull(MusicArtists.photo(json("""{"url":"http://sun1.userapi.com/image.jpg"}""")))
        assertEquals("https://sun1.userapi.com/photo.jpg",MusicArtists.photo(json("""{"photo_135":"https://sun1.userapi.com/photo.jpg"}""")))
    }
}
