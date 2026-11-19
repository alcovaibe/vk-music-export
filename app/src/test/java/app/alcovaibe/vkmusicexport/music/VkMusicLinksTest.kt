package app.alcovaibe.vkmusicexport.music

import org.junit.Assert.*
import org.junit.Test

class VkMusicLinksTest {
    @Test fun extractsTrackFromSharedTextAndPreservesItsAccessKey() {
        val url = "https://vk.ru/audio607277757_456258731_synthetic123"
        val link = VkMusicLinks.fromText("Исполнитель — Трек\nПослушай ($url).") as VkMusicLink.Track

        assertEquals("607277757_456258731", link.trackId)
        assertEquals("synthetic123", link.accessKey)
        assertEquals(url, link.url)
        assertFalse(link.toString().contains("synthetic123"))
    }

    @Test fun roundTripsPlaylistAndAlbumLinks() {
        val links = listOf(
            "https://vk.com/audio_playlist-123_456_synthetic",
            "https://vk.com/music?z=audio_playlist-123_456%2Fsynthetic",
            "https://m.vk.ru/music?z=audio_playlist-123_456&access_key=synthetic",
            "https://vk.ru/music/album/-123_456/synthetic"
        )
        for (url in links) {
            val link = VkMusicLinks.parse(url) as VkMusicLink.Collection
            assertEquals(-123L, link.owner)
            assertEquals(456L, link.id)
            assertEquals("synthetic", link.accessKey)
            assertEquals(link.url, VkMusicLinks.parse(link.url)?.url)
        }
    }

    @Test fun rejectsForeignHostsMalformedIdsAndConflictingKeys() {
        val unsupported = listOf(
            "https://vk.ru.evil.example/audio1_2",
            "https://vk.ru@evil.example/audio1_2",
            "https://evil.example@vk.ru/audio1_2",
            "https://vk.ru:80/audio1_2",
            "https://vk.ru/audio0_2",
            "https://vk.ru/audio1_9999999999999999999",
            "https://vk.ru/audio1_2_first?access_key=second",
            "https://vk.ru/music?z=audio1_2&z=audio3_4",
            "https://vk.ru/audios123"
        )
        unsupported.forEach { assertNull(it, VkMusicLinks.fromText(it)) }
    }
}
