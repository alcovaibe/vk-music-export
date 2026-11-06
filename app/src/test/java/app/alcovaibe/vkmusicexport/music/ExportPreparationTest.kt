package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ExportPreparationTest {
    private val access=MusicAccess("SYNTHETIC".repeat(12),"CI/1",accountId=12)
    private fun tracks(size: Int)=List(size){NativeTrack("12_${it+1}","Song $it","CI",1)}
    private fun list(id: Long,count: Int,kind: PlaylistKind=PlaylistKind.PLAYLIST)=
        NativePlaylist(12,id,"List $id",count,null,null,null,null,kind)
    private class Api: MusicApi {
        var library=emptyList<NativeTrack>();var lists=emptyList<NativePlaylist>()
        val contents=mutableMapOf<String,List<NativeTrack>>()
        var libraryCalls=0;var catalogCalls=0;val playlistCalls=mutableMapOf<String,Int>()
        var beforePage: (suspend(String,Int)->Unit)?=null
        override suspend fun library(access: MusicAccess)=NativeProbe(12,libraryPage(access,12,0))
        override suspend fun libraryPage(access: MusicAccess,owner: Long,offset: Int): NativePage {
            libraryCalls++;beforePage?.invoke("library",offset)
            return NativePage(library.drop(offset).take(100),library.size)
        }
        override suspend fun catalog(access: MusicAccess,owner: Long): NativeCatalog {catalogCalls++;return NativeCatalog(lists,lists.size)}
        override suspend fun playlist(access: MusicAccess,list: NativePlaylist)=playlistPage(access,list,0)
        override suspend fun playlistPage(access: MusicAccess,list: NativePlaylist,offset: Int): NativePage {
            playlistCalls[list.key]=(playlistCalls[list.key]?:0)+1;beforePage?.invoke(list.key,offset)
            val tracks=contents.getValue(list.key)
            return NativePage(tracks.drop(offset).take(100),tracks.size)
        }
        override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("Metadata planning cannot download audio")
    }
    @Test fun analyzedLibrariesOf1004500And20000TracksNeedNoNewPages()=runBlocking {
        for(size in listOf(100,4500,20000)) {
            val api=Api();val cache=ExportReadCache({0});val lease=cache.begin(12,access.readSessionId)
            val source=tracks(size);cache.rememberLibrary(lease,NativePage(source,size))
            val result=ExportPreparation(api,cache).plan(access,12)
            assertEquals(size,result.tasks.size);assertEquals(1,result.reusedSources)
            assertEquals(source.map{it.id},result.tasks.map{it.track.id})
            assertEquals(size,result.tasks.last().position)
            assertEquals(0,api.libraryCalls);assertEquals(0,api.catalogCalls);assertTrue(api.playlistCalls.isEmpty())
        }
    }
    @Test fun coldCategoryReadsEachChosenSourceOnceAndNeverReadsOtherCategories()=runBlocking {
        val api=Api();val cache=ExportReadCache({0});val first=list(1,101);val second=list(2,1);val album=list(3,1,PlaylistKind.ALBUM)
        api.lists=listOf(first,second,album)
        api.contents[first.key]=tracks(101);api.contents[second.key]=tracks(1)
        val result=ExportPreparation(api,cache).plan(access,12,category=PlaylistKind.PLAYLIST)
        assertEquals(102,result.tasks.size);assertEquals(1,api.catalogCalls)
        assertEquals(mapOf(first.key to 2,second.key to 1),api.playlistCalls)
        assertEquals(0,api.libraryCalls);assertTrue(result.failures.isEmpty())
        val again=ExportPreparation(api,cache).plan(access,12,category=PlaylistKind.PLAYLIST)
        assertEquals(result.tasks,again.tasks);assertEquals(2,again.reusedSources)
        assertEquals(1,api.catalogCalls);assertEquals(mapOf(first.key to 2,second.key to 1),api.playlistCalls)
    }
    @Test fun openedCollectionReusesItsAnalyzedPageAndRetainsRepeatedOccurrences()=runBlocking {
        val api=Api();val cache=ExportReadCache({0});val lease=cache.begin(12,access.readSessionId)
        val list=list(1,3,PlaylistKind.ALBUM);val song=tracks(1).single()
        cache.rememberCatalog(lease,NativeCatalog(listOf(list),1))
        cache.rememberPlaylist(lease,list,NativePage(listOf(song,song.copy(id="12_2"),song),3))
        val result=ExportPreparation(api,cache).plan(access,12,selected=list)
        assertEquals(listOf(1,1,2),result.tasks.map{it.occurrence});assertEquals(listOf(1,2,3),result.tasks.map{it.position})
        assertEquals(0,api.catalogCalls);assertTrue(api.playlistCalls.isEmpty())
    }
    @Test fun expiredLibraryAndChangedLoginFallBackToFreshPages()=runBlocking {
        var now=0L;val api=Api();api.library=tracks(101)
        val cache=ExportReadCache({now});val lease=cache.begin(12,access.readSessionId)
        cache.rememberLibrary(lease,NativePage(tracks(1),1));now=60_000
        assertEquals(101,ExportPreparation(api,cache).plan(access,12).tasks.size);assertEquals(2,api.libraryCalls)
        val next=MusicAccess("ANOTHER".repeat(12),"CI/1",accountId=12)
        assertEquals(101,ExportPreparation(api,cache).plan(next,12).tasks.size);assertEquals(4,api.libraryCalls)
    }
    @Test fun incompleteCatalogAndDeniedSourceRemainExplicitFailures()=runBlocking {
        val api=Api();val cache=ExportReadCache({0});val first=list(1,1);val denied=list(2,1)
        api.contents[first.key]=tracks(1);api.beforePage={key,_->if(key==denied.key)throw MusicFailure(MusicError.DENIED)}
        val catalog=NativeCatalog(listOf(first,denied),3,complete=false)
        val result=ExportPreparation(api,cache).plan(access,12,category=PlaylistKind.PLAYLIST,loadCatalog={catalog})
        assertEquals(1,result.tasks.size);assertEquals(2,result.failures.size)
        assertEquals(setOf(MusicError.INCOMPLETE,MusicError.DENIED),result.failures.map{it.error}.toSet())
        assertNull(cache.playlist(cache.lease(12,access.readSessionId),denied))
    }
    @Test fun cancellationDiscardsPartialPagesAndRetryStartsAtTheBeginning()=runBlocking {
        val api=Api();api.library=tracks(101);val cache=ExportReadCache({0});val entered=CompletableDeferred<Unit>()
        api.beforePage={_,offset->if(offset==100){entered.complete(Unit);awaitCancellation()}}
        val job=launch{ExportPreparation(api,cache).plan(access,12)}
        entered.await();job.cancelAndJoin()
        assertNull(cache.library(cache.lease(12,access.readSessionId)))
        api.beforePage=null
        assertEquals(101,ExportPreparation(api,cache).plan(access,12).tasks.size);assertEquals(4,api.libraryCalls)
    }
    @Test fun libraryAlbumGroupingStillUsesOneLibraryReadAndKeepsSeparateMemberships()=runBlocking {
        val api=Api();val cache=ExportReadCache({0});api.library=tracks(1).map{it.copy(album=MusicAlbum("album:1","Album"))}
        val result=ExportPreparation(api,cache).plan(access,12,libraryAlbums=true)
        assertEquals(2,result.tasks.size);assertEquals(1,api.libraryCalls)
        assertEquals(setOf("library","library_album"),result.tasks.map{it.source!!.kind}.toSet())
        assertEquals(1,result.tasks.map{it.track.id}.toSet().size)
    }
    @Test fun foreignAccountIsRejectedBeforeReadingOrRebindingCache()=runBlocking {
        val api=Api();val cache=ExportReadCache({0});val lease=cache.begin(12,access.readSessionId)
        try{ExportPreparation(api,cache).plan(access,99);fail("Expected account mismatch")}
        catch(e: MusicFailure){assertEquals(MusicError.ACCOUNT_CHANGED,e.code)}
        assertEquals(lease,cache.lease(12,access.readSessionId));assertEquals(0,api.libraryCalls)
    }
    @Test fun selectedSourceCountChangesBecomeTheObservedPageWithoutAFullCatalogRead()=runBlocking {
        val api=Api();val cache=ExportReadCache({0});val source=list(9,3)
        api.contents[source.key]=tracks(4)
        val prepared=ExportPreparation(api,cache).plan(access,12,selected=source)
        val observed=checkNotNull(prepared.selected)
        assertEquals(4,prepared.tasks.size);assertEquals(4,observed.list.count)
        assertEquals(api.contents[source.key],observed.page.tracks)
        assertEquals(0,api.catalogCalls);assertEquals(mapOf(source.key to 1),api.playlistCalls)
        val again=ExportPreparation(api,cache).plan(access,12,selected=observed.list)
        assertEquals(prepared.tasks,again.tasks);assertEquals(1,again.reusedSources)
    }
}
