package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MusicCatalogControllerTest {
    private val access = MusicAccess("a".repeat(64), "CI", accountId = 12)
    private val playlist = NativePlaylist(12, 100, "Test List", 1, null, null, null, null, PlaylistKind.PLAYLIST)
    private val track = NativeTrack("12_100_1", "Title", "Artist", 180)

    private class Api : MusicApi {
        override suspend fun library(access: MusicAccess) = NativeProbe(12, NativePage(listOf(), 0))
        override suspend fun catalog(access: MusicAccess, owner: Long) = NativeCatalog(listOf(), 0)
        override suspend fun playlist(access: MusicAccess, list: NativePlaylist) = NativePage(listOf(), 1)
        override suspend fun media(access: MusicAccess, track: NativeTrack) = error("No media")
    }

    private fun TestScope.controller(api: Api, read: suspend () -> MusicAccess = { access }) =
        MusicCatalogController(
            backgroundScope, api, ExportReadCache({ 1000L }), read,
            checked = { it }, blocked = { false },
            io = UnconfinedTestDispatcher(testScheduler)
        )

    @Test
    fun selectSectionUpdatesSectionAndResetsPlaylist() = runTest {
        val c = controller(Api())
        val probe = NativeProbe(12, NativePage(listOf(track), 1))
        c.selectSection(CatalogSection.PLAYLISTS, probe)
        assertEquals(CatalogSection.PLAYLISTS, c.state.value.section)
        assertNull(c.state.value.selected)

        c.selectSection(CatalogSection.LIBRARY, probe)
        assertEquals(CatalogSection.LIBRARY, c.state.value.section)
        assertEquals(1, c.state.value.page?.tracks?.size)
    }

    @Test
    fun selectPlaylistDeselectsWhenNullPassed() = runTest {
        val c = controller(Api())
        val probe = NativeProbe(12, NativePage(listOf(track), 1))
        c.selectPlaylist(null, probe, access.readSessionId)
        assertEquals(CatalogSection.LIBRARY, c.state.value.section)
        assertNull(c.state.value.selected)
        assertEquals(1, c.state.value.page?.tracks?.size)
    }
}
