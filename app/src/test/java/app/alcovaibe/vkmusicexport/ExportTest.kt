package app.alcovaibe.vkmusicexport

import app.alcovaibe.vkmusicexport.data.*
import app.alcovaibe.vkmusicexport.export.*
import app.alcovaibe.vkmusicexport.vk.PagePolicy
import app.alcovaibe.vkmusicexport.vk.BrowserProfile
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class ExportTest {
    private val song = Track("12_34", "Песня, \"Версия\" 🎵", "Артист")
    private fun source(id: String, track: Track = song) = MusicSource(id, "Плейлист / 1", "https://vk.ru/audio", listOf(Entry("a", track, 1), Entry("b", track, 2)), status = ScanStatus.PARTIAL)
    private val snapshot get() = CollectionSnapshot(sources = listOf(source("my-music"), source("playlist:12:1")))

    @Test fun repeatsArePreservedInPlaylistsAndMergedOnlyByVkId() {
        assertEquals(4, snapshot.entriesCount)
        assertEquals(1, snapshot.uniqueTracks().size)
        val unknown = Track(title = "Одинаково", artist = "Артист")
        assertEquals(4, CollectionSnapshot(sources = listOf(source("one", unknown), source("two", unknown))).uniqueTracks().size)
    }
    @Test fun csvQuotesNamesAndPreservesPositions() {
        val csv = Exporter.csv(snapshot)
        assertTrue(csv.contains("\"Песня, \"\"Версия\"\" 🎵\""))
        assertTrue(csv.contains("\"2\""))
        assertEquals(5, csv.split("\r\n").filter { it.isNotEmpty() }.size)
    }
    @Test fun zipContainsFullStructureAndSeparatePlaylists() {
        val out = ByteArrayOutputStream()
        Exporter.write(snapshot, ExportFormat.ZIP, out)
        val files = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; files[entry.name] = zip.readBytes().toString(Charsets.UTF_8) }
        }
        assertEquals(6, files.size)
        assertEquals(4, Json.decodeFromString<CollectionSnapshot>(files.getValue("collection.json")).entriesCount)
        assertTrue(files.getValue("report.txt").contains("Частично"))
        assertEquals(2, files.getValue("playlists/1-Плейлист _ 1.txt").lines().size)
    }
    @Test fun urlsRejectImpersonationAndExportsStripSecrets() {
        assertFalse(PagePolicy.allowed("https://vk.ru.evil.example/audio"))
        assertFalse(PagePolicy.allowed("https://vk.ru@evil.example/audio"))
        assertFalse(PagePolicy.allowed("javascript:alert(1)"))
        assertFalse(PagePolicy.allowed("http://vk.ru/audio"))
        assertFalse(PagePolicy.allowed("https://vk.ru:8443/audio"))
        assertFalse(PagePolicy.musicPage("https://id.vk.com/authorize"))
        assertFalse(PagePolicy.vkWebsite("https://id.vk.com/authorize"))
        assertFalse(PagePolicy.vkWebsite("https://m.vk.ru.evil.example/"))
        assertTrue(PagePolicy.vkWebsite("https://m.vk.ru/"))
        assertTrue(PagePolicy.musicPage("https://vk.ru/audios123"))
        assertEquals("https://vk.ru/music/playlist/12_34", PagePolicy.publicUrl("https://vk.ru/music/playlist/12_34_secret?access_token=secret#token"))
    }
    @Test fun chromeCompatibilityKeepsTheRealEngineAndMobilePlatform() {
        val defaultAgent = "Mozilla/5.0 (Linux; Android 10; K; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/155.0.0.0 Mobile Safari/537.36"
        val expected = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Mobile Safari/537.36"
        assertEquals(expected, BrowserProfile.mobileUserAgent(defaultAgent))
        assertEquals(expected, BrowserProfile.mobileUserAgent(expected))
        assertEquals("Unknown engine", BrowserProfile.mobileUserAgent("Unknown engine"))
    }
    @Test fun fileNamesCannotEscapeArchiveDirectory() {
        assertFalse(Exporter.safeName("../../secret\u0000 / name").contains('/'))
        assertFalse(Exporter.safeName("..").isBlank())
    }
}
