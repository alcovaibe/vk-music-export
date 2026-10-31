package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MusicArtistsTest {
    private fun json(raw: String)=Json.parseToJsonElement(raw)
    @Test fun recommendationsAndNamesInSongsAreNeverInferredAsSubscriptions()=runTest {
        val value=json("""{"artists":[{"id":"1","name":"Подписка"},{"id":"2","name":"Рекомендация"}],"catalog":{"sections":[{"id":"own","title":"Моя музыка","blocks":[{"title":"Мои исполнители","artists_ids":["1"]},{"title":"Похожие исполнители","artists_ids":["2"]}]}]}}""")
        val rows=MusicArtists.subscriptions{method,_->assertEquals("catalog.getAudio",method);value}
        assertEquals(listOf("1"),rows.map{it.id})
    }
    @Test fun subscriptionSectionAndBlockCursorsAreLoadedToTheEndAndDuplicatesUseIds()=runTest {
        val calls=mutableListOf<String>()
        val result=MusicArtists.subscriptions{method,p->
            calls.add(method+":"+p.values.joinToString())
            when(method) {
                "catalog.getAudio"->json("""{"catalog":{"sections":[{"id":"own","title":"Моя музыка","blocks":[{"title":"Исполнители","actions":[{"title":"Исполнители","section_id":"all"}]}]}]}}""")
                "catalog.getSection"->json("""{"artists":[{"id":"1","name":"Одно имя"}],"section":{"id":"all","blocks":[{"id":"b","artists_ids":["1"],"next_from":"cursor"}]}}""")
                else->{assertEquals("cursor",p["start_from"]);json("""{"artists":[{"id":"1","name":"Одно имя"},{"id":"2","name":"Одно имя"}],"block":{"id":"b","artists_ids":["1","2"]}}""")}
            }
        }
        assertEquals(listOf("1","2"),result.map{it.id});assertEquals(3,calls.size)
    }
    @Test fun explicitUnfollowedArtistIsExcludedAndUnsupportedIsNotAnEmptySuccess()=runTest {
        val response=json("""{"artists":[{"id":"1","name":"Первый","is_following":1},{"id":"2","name":"Второй","is_following":0}],"catalog":{"sections":[{"blocks":[{"title":"Мои исполнители","artists_ids":["1","2"]}]}]}}""")
        assertEquals(listOf("1"),MusicArtists.subscriptions{_,_->response}.map{it.id})
        try{MusicArtists.subscriptions{_,_->json("""{"artists":[{"id":"2","name":"Рекомендация"}]}""")};fail("Must report unavailable subscriptions")}
        catch(e: MusicFailure){assertEquals(MusicError.METHOD,e.code)}
    }
    @Test fun ownMusicSectionIsHydratedBeforeFindingArtistsAndGenericRecommendationBlocksAreIgnored()=runTest {
        val result=MusicArtists.subscriptions{method,fields->
            if(method=="catalog.getAudio")json("""{"artists":[{"id":"fake","name":"Рекомендация"}],"catalog":{"sections":[{"id":"for-you","title":"Для вас","blocks":[{"title":"Исполнители","artists_ids":["fake"]}]},{"id":"own","title":"Моя музыка"}]}}""")
            else {
                assertEquals("own",fields["section_id"])
                json("""{"artists":[{"id":"real","name":"Подписка"}],"section":{"id":"own","blocks":[{"title":"Исполнители","artists_ids":["real"]}]}}""")
            }
        }
        assertEquals(listOf("real"),result.map{it.id})
    }
    @Test fun textActionsAndRepeatedShowAllLinksDoNotRestartTheSameSection()=runTest {
        var calls=0
        val rows=MusicArtists.subscriptions{method,_->
            calls++
            if(method=="catalog.getAudio")json("""{"catalog":{"sections":[{"title":"Моя музыка","blocks":[{"actions":[{"text":"Исполнители","section_id":"all"}]}]}]}}""")
            else json("""{"artists":[{"id":"1","name":"Подписка"}],"section":{"id":"all","blocks":[{"artists_ids":["1"],"meta":{"show_all_info":{"section_id":"all"}},"actions":[{"text":"Исполнители","section_id":"all"}]}]}}""")
        }
        assertEquals(2,calls);assertEquals(listOf("1"),rows.map{it.id})
    }
    @Test fun realEmptySubscriptionBlockRemainsEmpty()=runTest {
        val rows=MusicArtists.subscriptions{_,_->json("""{"catalog":{"sections":[{"blocks":[{"title":"Мои исполнители","artists_ids":[]}]}]}}""")}
        assertTrue(rows.isEmpty())
    }
    @Test fun missingArtistPoolAndRepeatingCursorFailWithoutPublishingAPartialLibrary()=runTest {
        try{MusicArtists.subscriptions{_,_->json("""{"catalog":{"sections":[{"blocks":[{"title":"Мои исполнители","artists_ids":["missing"]}]}]}}""")};fail("Missing data must fail")}
        catch(e: MusicFailure){assertEquals(MusicError.INCOMPLETE,e.code)}
        try{MusicArtists.subscriptions{_,_->json("""{"block":{"id":"loop","title":"Мои исполнители","next_from":"same","artists_ids":[]}}""")};fail("Cursor loop must fail")}
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
    @Test fun ownMusicFeedCursorIsNotFollowedAndExplicitFollowedFlagsWorkWithoutHeadings()=runTest {
        var calls=0
        val rows=MusicArtists.subscriptions{_,_->calls++;json("""{"artists":[{"id":"1","name":"Подписка","is_followed":true},{"id":"2","name":"Удалённая","is_followed":false}],"section":{"id":"own","title":"Моя музыка","next_from":"general-feed","blocks":[]}}""")}
        assertEquals(1,calls);assertEquals(listOf("1"),rows.map{it.id})
    }
    @Test fun nestedButtonsAndMetaHeadingsNavigateOnlyToTheSubscriptionSection()=runTest {
        val rows=MusicArtists.subscriptions{method,fields->
            if(method=="catalog.getAudio")json("""{"catalog":{"sections":[{"title":"Моя музыка","blocks":[{"buttons":[{"text":"Исполнители","action":{"section_id":"subscriptions"}}]}]}]}}""")
            else {assertEquals("subscriptions",fields["section_id"]);json("""{"artists":[{"id":"1","name":"Подписка"}],"section":{"id":"subscriptions","blocks":[{"artists_ids":["1"]}]}}""")}
        }
        assertEquals(listOf("1"),rows.map{it.id})
        val meta=MusicArtists.subscriptions{method,_->
            if(method=="catalog.getAudio")json("""{"catalog":{"sections":[{"title":"Моя музыка","blocks":[{"meta":{"show_all_info":{"title":"Исполнители","section_id":"all"}}}]}]}}""")
            else json("""{"artists":[{"id":"1","name":"Подписка"}],"section":{"blocks":[{"artists_ids":["1"]}]}}""")
        }
        assertEquals(listOf("1"),meta.map{it.id})
    }
    @Test fun subscriptionOpenUrlStaysOnTheCurrentOwnerAndNeverFetchesAnExternalPage()=runTest {
        val calls=mutableListOf<Map<String,String>>()
        val rows=MusicArtists.subscriptions(12){method,fields->
            assertEquals("catalog.getAudio",method);calls.add(fields)
            if(fields.isEmpty())json("""{"catalog":{"sections":[{"title":"Моя музыка","blocks":[{"buttons":[{"text":"Исполнители","action":{"url":"https://vk.com/audios12?section=artists"}},{"text":"Исполнители","action":{"url":"https://vk.com/audios99?section=artists"}}]}]}]}}""")
            else json("""{"artists":[{"id":"1","name":"Подписка"}],"section":{"blocks":[{"artists_ids":["1"]}]}}""")
        }
        assertEquals(2,calls.size);assertEquals("https://vk.com/audios12?section=artists",calls[1]["url"])
        assertEquals(listOf("1"),rows.map{it.id})
    }
    @Test fun nonRepeatingEmptyCursorsStopAndVerifiedPartialResultsArePublishedBeforeFailure()=runTest {
        val snapshots=mutableListOf<List<MusicArtist>>();var n=0
        try {
            MusicArtists.subscriptions(progress={rows,_->snapshots.add(rows)}){method,_->
                if(method=="catalog.getAudio")json("""{"artists":[{"id":"1","name":"Подписка"}],"catalog":{"sections":[{"blocks":[{"title":"Мои исполнители","artists_ids":["1"],"actions":[{"text":"Мои исполнители","section_id":"all"}]}]}]}}""")
                else {n++;json("""{"section":{"id":"all","next_from":"different-$n","blocks":[]}}""")}
            };fail("Must stop empty cursor chain")
        }catch(e: MusicFailure){assertEquals(MusicError.INCOMPLETE,e.code)}
        assertEquals(2,n);assertEquals(listOf("1"),snapshots.first().map{it.id})
    }

}
