package app.alcovaibe.vkmusicexport.music

import org.junit.Assert.*
import org.junit.Test

class ExportReadCacheTest {
    private val song=NativeTrack("12_1","Song","CI",1)
    private fun list(id: Long=1,count: Int=1,title: String="List",kind: PlaylistKind=PlaylistKind.PLAYLIST,
        access: String?=null)=NativePlaylist(12,id,title,count,access,null,null,null,kind)
    @Test fun onlyCompletePagesAreStoredAndMutableInputCannotChangeTheSnapshot() {
        val cache=ExportReadCache({0});val lease=cache.begin(12,"session")
        val tracks=mutableListOf(song)
        cache.rememberLibrary(lease,NativePage(tracks,1));tracks.clear()
        assertEquals(listOf(song),cache.library(lease)!!.tracks)
        cache.rememberLibrary(lease,NativePage(listOf(song),2));assertNull(cache.library(lease))
        cache.rememberPlaylist(lease,list(count=2),NativePage(listOf(song),2))
        assertNull(cache.playlist(lease,list(count=2)))
        cache.rememberLibrary(lease,NativePage(emptyList(),0));assertEquals(0,cache.library(lease)!!.count)
    }
    @Test fun expiryAndClockRollbackRejectOldPagesAndCatalogs() {
        var now=10L;val cache=ExportReadCache({now},ttlMs=100);val lease=cache.begin(12,"session")
        cache.rememberLibrary(lease,NativePage(listOf(song),1));cache.rememberCatalog(lease,NativeCatalog(listOf(list()),1))
        now=109;assertNotNull(cache.library(lease));assertNotNull(cache.catalog(lease,null))
        now=110;assertNull(cache.library(lease));assertNull(cache.catalog(lease,null))
        cache.rememberLibrary(lease,NativePage(listOf(song),1));now=109;assertNull(cache.library(lease))
    }
    @Test fun accountLoginAndAnalysisEpochFenceDelayedWriters() {
        val cache=ExportReadCache({0});val old=cache.begin(12,"first")
        val fresh=cache.begin(12,"first") // Explicit new analysis, even of the same session.
        cache.rememberLibrary(fresh,NativePage(listOf(song),1))
        cache.rememberLibrary(old,NativePage(emptyList(),0));cache.rememberCatalog(old,NativeCatalog(emptyList(),0))
        assertEquals(1,cache.library(fresh)!!.count);assertNull(cache.library(old));assertNull(cache.catalog(fresh,null))
        assertNull(cache.lease(99,"first"));assertNull(cache.lease(12,"new-login"))
        val next=cache.begin(12,"new-login");assertNull(cache.library(next))
        cache.rememberPlaylist(fresh,list(),NativePage(listOf(song),1));assertNull(cache.playlist(next,list()))
        cache.clear();assertNull(cache.lease(12,"new-login"))
    }
    @Test fun changedSourceDescriptorCannotReuseOldTracks() {
        val cache=ExportReadCache({0});val lease=cache.begin(12,"session");val original=list()
        for(changed in listOf(list(title="Renamed"),list(count=2),list(kind=PlaylistKind.ALBUM),list(access="new-key"))) {
            cache.rememberPlaylist(lease,original,NativePage(listOf(song),1))
            assertNull(cache.playlist(lease,changed))
        }
    }
    @Test fun catalogUpdatesPruneDeletedAndChangedSourcesButPartialCatalogDoesNotInventDeletion() {
        val cache=ExportReadCache({0});val lease=cache.begin(12,"session");val first=list();val second=list(2)
        cache.rememberPlaylist(lease,first,NativePage(listOf(song),1));cache.rememberPlaylist(lease,second,NativePage(listOf(song),1))
        cache.rememberCatalog(lease,NativeCatalog(listOf(first),2,complete=false))
        assertNotNull(cache.playlist(lease,second));assertNull(cache.catalog(lease,null))
        cache.rememberCatalog(lease,NativeCatalog(listOf(list(title="New")),1))
        assertNull(cache.playlist(lease,first));assertNull(cache.playlist(lease,second))
    }
    @Test fun catalogRefreshInvalidatesCollectionPagesWithoutDiscardingTheLibrary() {
        val cache=ExportReadCache({0});val lease=cache.begin(12,"session")
        cache.rememberLibrary(lease,NativePage(listOf(song),1));cache.rememberPlaylist(lease,list(),NativePage(listOf(song),1))
        cache.rememberCatalog(lease,NativeCatalog(listOf(list()),1));cache.invalidateCatalog(lease)
        assertNull(cache.catalog(lease,null));assertNull(cache.playlist(lease,list()));assertNotNull(cache.library(lease))
    }
    @Test fun positionAndPageLimitsEvictLeastRecentlyUsedMetadataAndOversizeEntriesAreNotStored() {
        val cache=ExportReadCache({0},maxTracks=4,maxPages=2);val lease=cache.begin(12,"session")
        val page=NativePage(listOf(song,song),2)
        cache.rememberPlaylist(lease,list(1,2),page);cache.rememberPlaylist(lease,list(2,2),page)
        assertNotNull(cache.playlist(lease,list(1,2)))
        cache.rememberPlaylist(lease,list(3,2),page)
        assertNull(cache.playlist(lease,list(2,2)));assertNotNull(cache.playlist(lease,list(1,2)))
        cache.rememberLibrary(lease,NativePage(List(5){song},5));assertNull(cache.library(lease))
        assertNotNull(cache.playlist(lease,list(3,2)))
    }
    @Test fun incompleteOptionalAlbumFilterNeverBecomesACompleteAlbumCatalog() {
        val cache=ExportReadCache({0});val lease=cache.begin(12,"session")
        cache.rememberCatalog(lease,NativeCatalog(listOf(list()),2,complete=false,baseComplete=true,optionalAlbumsIncomplete=true))
        assertNotNull(cache.catalog(lease,PlaylistKind.PLAYLIST));assertNull(cache.catalog(lease,PlaylistKind.ALBUM))
    }
    @Test fun observedCountMismatchDropsTheOldDescriptorAndPage() {
        val cache=ExportReadCache({0});val lease=cache.begin(12,"session")
        cache.rememberCatalog(lease,NativeCatalog(listOf(list()),1));cache.rememberPlaylist(lease,list(),NativePage(listOf(song),1))
        cache.rememberPlaylist(lease,list(),NativePage(listOf(song,song),2))
        assertNull(cache.catalog(lease,null));assertNull(cache.playlist(lease,list()))
    }
}
