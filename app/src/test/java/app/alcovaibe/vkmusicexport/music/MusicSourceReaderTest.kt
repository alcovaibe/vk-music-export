package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MusicSourceReaderTest {
    private val access=MusicAccess("SYNTHETIC".repeat(12),"CI/1",accountId=12,readSessionId="read")
    private val song=NativeTrack("34_1","Song","CI",1)
    private val list=NativePlaylist(34,7,"List",3,null,null,null,null,PlaylistKind.PLAYLIST)
    private class Api: MusicApi {
        var rows=emptyList<NativeTrack>();var list: NativePlaylist?=null
        var libraryCalls=0;var playlistCalls=0;var catalogs=0;var failAt: Int?=null
        var before: (suspend(Int)->Unit)?=null
        override suspend fun library(access: MusicAccess)=NativeProbe(12,NativePage(emptyList(),0))
        override suspend fun libraryPage(access: MusicAccess,owner: Long,offset: Int): NativePage {
            libraryCalls++;before?.invoke(offset);if(offset==failAt)throw MusicFailure(MusicError.NETWORK)
            return NativePage(rows.drop(offset).take(100),rows.size)
        }
        override suspend fun playlist(access: MusicAccess,list: NativePlaylist)=playlistPage(access,list,0)
        override suspend fun playlistPage(access: MusicAccess,list: NativePlaylist,offset: Int): NativePage {
            playlistCalls++;before?.invoke(offset);return NativePage(rows.drop(offset).take(100),rows.size)
        }
        override suspend fun catalog(access: MusicAccess,owner: Long): NativeCatalog {catalogs++;return NativeCatalog(listOfNotNull(list),if(list==null)0 else 1)}
        override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("Metadata only")
    }
    @Test fun openingAndReopeningASelectedPlaylistNeverEnumeratesTheCatalog()=runBlocking {
        val api=Api().apply{rows=listOf(song,song.copy(id="34_2"),song)}
        val cache=ExportReadCache({0});val reader=MusicSourceReader(api,cache)
        val first=reader.playlist(access,12,list);assertFalse(first.reused)
        assertTrue(reader.playlist(access,12,list).reused)
        assertEquals(1,api.playlistCalls);assertEquals(0,api.catalogs)
    }
    @Test fun ownerScopedLibrariesAndCatalogsCannotBeServedAsAnotherFriendsData()=runBlocking {
        val cache=ExportReadCache({0});val lease=cache.begin(12,"read")
        cache.rememberLibrary(lease,NativePage(listOf(song),1),34)
        cache.rememberLibrary(lease,NativePage(listOf(song.copy(id="35_1")),1),35)
        cache.rememberCatalog(lease,NativeCatalog(listOf(list),1),34)
        assertEquals("34_1",cache.library(lease,34)!!.tracks.single().id)
        assertEquals("35_1",cache.library(lease,35)!!.tracks.single().id)
        assertNull(cache.library(lease));assertNull(cache.catalog(lease,null,35));assertNotNull(cache.catalog(lease,null,34))
    }
    @Test fun expiredSourceIsReadOnceWithoutScanningAnyOtherSource()=runBlocking {
        var now=0L;val cache=ExportReadCache({now});val api=Api().apply{rows=List(101){song.copy(id="34_$it")}}
        val reader=MusicSourceReader(api,cache);val descriptor=list.withCount(101)
        reader.playlist(access,12,descriptor);assertEquals(2,api.playlistCalls)
        now=60_000;api.rows=api.rows.reversed()
        assertEquals(api.rows,reader.playlist(access,12,descriptor).page.tracks)
        assertEquals(4,api.playlistCalls);assertEquals(0,api.catalogs)
    }
    @Test fun completeCountObservationUpdatesOnlyTheMatchingDescriptorAndDoesNotRefreshCatalogAge()=runBlocking {
        var now=0L;val cache=ExportReadCache({now});val lease=cache.begin(12,"read")
        cache.rememberCatalog(lease,NativeCatalog(listOf(list),1),34)
        val api=Api().apply{rows=listOf(song)};val reader=MusicSourceReader(api,cache)
        now=59_999;val read=reader.playlist(access,12,list)
        assertEquals(1,read.list.count);assertEquals(1,cache.catalog(lease,null,34)!!.lists.single().count)
        assertTrue(reader.playlist(access,12,read.list).reused)
        now=60_000;assertNull(cache.catalog(lease,null,34));assertNotNull(cache.playlist(lease,read.list))
    }
    @Test fun explicitCatalogRefreshFencesOldWritersButPreservesTheUnexpiredLibrary() {
        val cache=ExportReadCache({0});val old=cache.begin(12,"read")
        cache.rememberLibrary(old,NativePage(listOf(song),1))
        val next=cache.restartCatalog(old)!!
        cache.rememberPlaylist(old,list,NativePage(listOf(song),1));cache.rememberCatalog(old,NativeCatalog(listOf(list),1))
        assertNull(cache.catalog(next,null));assertNull(cache.playlist(next,list));assertEquals(1,cache.library(next)!!.count)
    }
    @Test fun cancelledSecondPageCannotBecomeACompleteCachedSource()=runBlocking {
        val cache=ExportReadCache({0});val api=Api().apply{rows=List(101){song.copy(id="34_$it")}}
        val entered=CompletableDeferred<Unit>();api.before={if(it==100){entered.complete(Unit);awaitCancellation()}}
        val reader=MusicSourceReader(api,cache);val partial=mutableListOf<Int>()
        val job=launch{reader.library(access,12,34,partial={partial.add(it.tracks.size)})}
        entered.await();job.cancelAndJoin()
        assertEquals(listOf(100),partial);assertNull(cache.library(cache.lease(12,"read"),34))
        api.before=null;assertEquals(101,reader.library(access,12,34).tracks.size);assertEquals(4,api.libraryCalls)
    }
    @Test fun oldEpochCannotPopulateAFreshCatalogAfterItsRequestReturns()=runBlocking {
        val cache=ExportReadCache({0});val old=cache.begin(12,"read");val api=Api().apply{rows=listOf(song,song,song)}
        api.before={cache.begin(12,"read")}
        MusicSourceReader(api,cache).playlist(access,12,list)
        assertNull(cache.playlist(cache.lease(12,"read"),list));assertNull(cache.playlist(old,list))
    }
    @Test fun independentLoginCannotRebindTheReaderCacheOrIssueANetworkRequest()=runBlocking {
        val cache=ExportReadCache({0});val original=cache.begin(12,"read");val api=Api()
        try{MusicSourceReader(api,cache).library(MusicAccess("other","CI",accountId=12,readSessionId="new"),12,34);fail("Wrong session")}
        catch(e: MusicFailure){assertEquals(MusicError.ACCOUNT_CHANGED,e.code)}
        assertEquals(original,cache.lease(12,"read"));assertEquals(0,api.libraryCalls)
    }
    @Test fun passiveCatalogReadsReuseTheObservedResultAndForcedReadsBypassIt()=runBlocking {
        val cache=ExportReadCache({0});val api=Api().apply{list=this@MusicSourceReaderTest.list};val reader=MusicSourceReader(api,cache)
        reader.catalog(access,12,34,personal=false);reader.catalog(access,12,34,personal=false)
        assertEquals(1,api.catalogs)
        reader.catalog(access,12,34,force=true,personal=false);assertEquals(2,api.catalogs)
    }
}
