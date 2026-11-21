package app.alcovaibe.vkmusicexport.music

import org.junit.Assert.*
import org.junit.Test

class SearchSavedTracksTest {
    private val song = NativeTrack("-20_8", "Песня", "Исполнитель", 180)
    private fun file(track: NativeTrack, owner: Long, key: String = "one") = PublishedAudio(
        key, track, "content://media/file/$key", "track.mp3", ExportLayout.ROOT,
        "mp3", "audio/mpeg", 180_000, 5_000, "sha", source = ExportSource(owner, "library", "my"))

    @Test fun detectsExactIdAndVerifiedCopyWithAnotherOwnerId() {
        val exact = file(song, 12)
        val copy = file(song.copy(id = "12_54"), 12)
        assertEquals(exact, SearchSavedTracks.match(listOf(song), listOf(SavedLibraryEntry(exact, true)), emptyMap(), 12)[song])
        assertEquals(copy, SearchSavedTracks.match(listOf(song), listOf(SavedLibraryEntry(copy, true)), emptyMap(), 12)[song])
    }

    @Test fun skipsAbsentFilesOtherAccountsAndDifferentVersions() {
        val original = file(song, 12)
        val foreign = file(song, 13)
        val live = file(song.copy(id = "12_55", subtitle = "live"), 12, "live")
        val files = listOf(SavedLibraryEntry(original, false), SavedLibraryEntry(foreign, true), SavedLibraryEntry(live, true))
        assertFalse(SearchSavedTracks.match(listOf(song), files, emptyMap(), 12).containsKey(song))
    }
}
