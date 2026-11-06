package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class FriendDownloadPreparationTest {
    private val access=MusicAccess("SYNTHETIC".repeat(12),"CI/1",accountId=12,readSessionId="read")
    private val user=MusicFriend(34,"Friend","CI")
    private fun rows(n: Int)=List(n){NativeTrack("34_${if(it==n-1)1 else it+1}","Song","CI",1)}
    private fun state(rows: List<NativeTrack>,list: NativePlaylist?=null)=FriendMusicState(user=user,tracks=NativePage(rows,rows.size),tracksComplete=true,
        playlist=list,playlistTracks=if(list!=null)NativePage(rows,rows.size)else null,playlistComplete=list!=null,viewerAccount=12,readSessionId="read")
    private class Api: MusicApi {
        var rows=emptyList<NativeTrack>();var reads=0
        override suspend fun library(access: MusicAccess)=error("Full library probe is unnecessary")
        override suspend fun libraryPage(access: MusicAccess,owner: Long,offset: Int): NativePage {reads++;return NativePage(rows.drop(offset).take(100),rows.size)}
        override suspend fun catalog(access: MusicAccess,owner: Long): NativeCatalog=error("No catalog scan")
        override suspend fun playlist(access: MusicAccess,list: NativePlaylist)=playlistPage(access,list,0)
        override suspend fun playlistPage(access: MusicAccess,list: NativePlaylist,offset: Int): NativePage {reads++;return NativePage(rows.drop(offset).take(100),rows.size)}
        override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("No media in queue admission")
    }
    @Test fun oneTrackFor1004500And20000PositionsKeepsIdentityWithoutAnyListRead()=runBlocking {
        for(size in listOf(100,4500,20000)) {
            val rows=rows(size);val api=Api();val before=state(rows)
            val actual=FriendDownloadPreparation(api,ExportReadCache({0})).plan(before,access,12,setOf(size),{})
            assertEquals(FriendMusicExport.tasks(before.page!!,user,12).last(),actual.tasks.single())
            assertEquals(2,actual.tasks.single().occurrence);assertEquals(0,api.reads)
        }
    }
    @Test fun selectedPlaylistOccurrencesUseDisplayedIdsEvenAfterRemoteReorder()=runBlocking {
        val rows=rows(3);val list=NativePlaylist(-55,7,"Album",3,null,null,null,1,PlaylistKind.ALBUM)
        val before=state(rows,list);val api=Api().apply{this.rows=rows.drop(1)}
        assertNotEquals(rows,api.rows)
        val actual=FriendDownloadPreparation(api,ExportReadCache({0})).plan(before,access,12,setOf(1,3),{})
        assertEquals(FriendMusicExport.tasks(before.page!!,user,12,list).filter{it.position in setOf(1,3)},actual.tasks)
        assertTrue(actual.tasks.all{it.source?.account==12L&&it.source?.id=="34:${list.key}"});assertEquals(0,api.reads)
    }
    @Test fun anExplicitTrackCanUseAPartialPrefixButDownloadAllCannot()=runBlocking {
        val before=state(rows(3)).copy(tracks=NativePage(rows(3),20000),tracksComplete=false)
        val planner=FriendDownloadPreparation(Api(),ExportReadCache({0}))
        assertEquals(3,planner.plan(before,access,12,setOf(3),{}).tasks.single().position)
        try{planner.plan(before,access,12,null,{});fail("Partial list must not become a complete source")}
        catch(e: MusicFailure){assertEquals(MusicError.INCOMPLETE,e.code)}
    }
    @Test fun fullSourcesReuseFreshPagesAndReadOnlyThatSourceOnceAfterExpiry()=runBlocking {
        var now=0L;val cache=ExportReadCache({now});val lease=cache.begin(12,"read");val rows=rows(101)
        cache.rememberLibrary(lease,NativePage(rows,rows.size),34)
        val api=Api().apply{this.rows=rows.reversed()};val planner=FriendDownloadPreparation(api,cache)
        assertEquals(rows,planner.plan(state(rows),access,12,null,{}).tasks.map{it.track});assertEquals(0,api.reads)
        now=60_000;assertEquals(api.rows,planner.plan(state(rows),access,12,null,{}).tasks.map{it.track});assertEquals(2,api.reads)
    }
    @Test fun foreignViewerNewLoginInvalidPositionsAndCancellationCannotProduceTasks()=runBlocking {
        val before=state(rows(3));val api=Api();val planner=FriendDownloadPreparation(api,ExportReadCache({0}))
        for((state,viewer) in listOf(before to 99L,before.copy(readSessionId="old") to 12L)) {
            try{planner.plan(state,access,viewer,setOf(3),{});fail("Wrong identity")}
            catch(e: MusicFailure){assertTrue(e.code in setOf(MusicError.ACCESS,MusicError.ACCOUNT_CHANGED))}
        }
        try{planner.plan(before,access,12,setOf(4),{});fail("Wrong position")}catch(e: MusicFailure){assertEquals(MusicError.LIBRARY_CHANGED,e.code)}
        try{planner.plan(before,access,12,setOf(3),{throw CancellationException()});fail("Cancelled") }catch(_: CancellationException){}
        assertEquals(0,api.reads)
    }
}
