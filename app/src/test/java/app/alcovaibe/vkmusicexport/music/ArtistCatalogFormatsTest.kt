package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ArtistCatalogFormatsTest {
    private fun json(s: String)=Json.parseToJsonElement(s)
    @Test fun sharedRootBlocksAndNestedCatalogPoolAreJoinedUsingSectionReferences()=runTest {
        val root=json("""{"catalog":{"artists":[{"id":"a","name":"Подписка"},{"id":"b","name":"Рекомендация"}],"sections":[{"title":"Моя музыка","blocks_ids":["mine"]},{"title":"Для вас","blocks_ids":["recommended"]}]},"blocks":[{"id":"mine","title":"Исполнители","artist_ids":["a"]},{"id":"recommended","title":"Исполнители","artist_ids":["b"]}]}""")
        assertEquals(listOf("a"),MusicArtists.subscriptions{_,_->root}.map{it.id})
    }
    @Test fun inlineArtistsAndHeaderLinksLoadTheRealSubscriptionSection()=runTest {
        val result=MusicArtists.subscriptions{method,fields->
            if(method=="catalog.getAudio")json("""{"catalog":{"sections":[{"title":"Моя музыка","blocks":[{"layout":{"title":"Мои исполнители · 2","header":{"links":[{"text":"Показать всех","link":{"section_id":"following"}}]}}}]}]}}""")
            else {
                assertEquals("following",fields["section_id"])
                json("""{"section":{"artists":[{"id":"a","name":"Подписка"},{"id":"b","name":"Не подписан","is_followed":false}],"blocks":[]}}""")
            }
        }
        assertEquals(listOf("a"),result.map{it.id})
    }
    @Test fun semanticSubscriptionBlocksAndDictionaryPoolsDoNotNeedRussianHeadings()=runTest {
        val root=json("""{"catalog":{"artists":{"a":{"name":"Подписка"},"b":{"name":"Рекомендация"}},"sections":[{"blocks":[{"type":"followed_artists","artists_ids":["a"]},{"title":"Вам понравится","artists_ids":["b"]}]}]}}""")
        assertEquals(listOf("a"),MusicArtists.subscriptions{_,_->root}.map{it.id})
    }
    @Test fun trustedSectionDoesNotTreatItsWholeSharedPoolAsSubscriptions()=runTest {
        val result=MusicArtists.subscriptions{method,_->
            if(method=="catalog.getAudio")json("""{"catalog":{"sections":[{"id":"all","title":"Мои исполнители"}]}}""")
            else json("""{"artists":[{"id":"a","name":"Подписка"},{"id":"b","name":"Рекомендация"}],"section":{"blocks":[{"artists_ids":["a"]}]}}""")
        }
        assertEquals(listOf("a"),result.map{it.id})
    }
    @Test fun structuralReportContainsOnlyWhitelistedNamesTypesAndCounts() {
        val value=json("""{"catalog":{"sections":[{"id":"PRIVATE_ID","title":"PRIVATE_TITLE","blocks":[{"artists_ids":["PRIVATE_ARTIST"],"buttons":[{"url":"https://private.test/?access_token=SECRET"}]}]}]},"SECRET_AS_KEY":"PRIVATE_TOKEN"}""")
        val shape=ArtistCatalogShape.describe(value)
        for(secret in listOf("PRIVATE","SECRET","https:","access_token"))assertFalse(shape.contains(secret))
        assertTrue(shape.contains("blocks"));assertTrue(shape.contains("artists_ids"));assertTrue(shape.contains("array(1)"))
        assertTrue(shape.length<=3600)
    }
}
