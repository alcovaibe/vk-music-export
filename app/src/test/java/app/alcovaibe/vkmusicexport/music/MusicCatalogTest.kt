package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class MusicCatalogTest {
    private fun list(id: Long,kind: PlaylistKind)=NativePlaylist(12,id,"Collection $id",1,null,null,null,null,kind)
    @Test fun completePaginationFindsAlbumsBeyondTheFirstHundredBeforeFiltering()=runBlocking {
        val input=(1L..100L).map{list(it,PlaylistKind.PLAYLIST)}+list(101,PlaylistKind.ALBUM)+list(102,PlaylistKind.UNKNOWN)
        val offsets=mutableListOf<Int>()
        val catalog=MusicPagination.catalog({offset->offsets.add(offset);NativeCatalog(input.drop(offset).take(100),input.size)})
        assertEquals(listOf(0,100),offsets);assertTrue(catalog.complete)
        assertEquals(100,MusicCatalog.lists(catalog,CatalogSection.PLAYLISTS,emptyMap()).size)
        assertEquals(listOf(input[100]),MusicCatalog.lists(catalog,CatalogSection.ALBUMS,emptyMap()))
        assertEquals(listOf(input[101]),MusicCatalog.lists(catalog,CatalogSection.UNKNOWN,emptyMap()))
    }
    @Test fun numericTypesAreResolvedByServerAlbumMembershipAndOldChoicesCannotMisclassifyPlaylists()=runBlocking {
        val raw=NativeProtocol.catalog(Json.parseToJsonElement("""{"count":4,"items":[
            {"owner_id":12,"id":1,"title":"Плейлист 28.10.2026","count":1,"type":0},
            {"owner_id":-9,"id":2,"title":"I AM","count":1,"type":1,"is_following":1},
            {"owner_id":-9,"id":3,"title":"Removed","count":1,"type":1,"is_following":false},
            {"owner_id":12,"id":4,"title":"Deleted own","count":1,"type":0,"is_deleted":true}]}"""))
        val api=object: MusicApi {
            override suspend fun library(access: MusicAccess)=error("unused")
            override suspend fun catalog(access: MusicAccess,owner: Long)=raw
            override suspend fun albumCatalogPage(access: MusicAccess,owner: Long,offset: Int)=NativeCatalog(listOf(raw.lists[1],raw.lists[2]),2)
            override suspend fun playlist(access: MusicAccess,list: NativePlaylist)=error("unused")
            override suspend fun media(access: MusicAccess,track: NativeTrack)=error("unused")
        }
        val catalog=MusicCatalog.load(api,MusicAccess("synthetic","CI/1"),12)
        assertEquals(2,catalog.lists.size);assertTrue(catalog.complete)
        val choices=mapOf(raw.lists[0].key to PlaylistKind.ALBUM)
        assertEquals(listOf("I AM"),MusicCatalog.lists(catalog,CatalogSection.ALBUMS,choices).map{it.title})
        assertEquals(listOf("Плейлист 28.10.2026"),MusicCatalog.lists(catalog,CatalogSection.PLAYLISTS,choices).map{it.title})
        assertTrue(MusicCatalog.lists(catalog,CatalogSection.UNKNOWN,choices).isEmpty())
    }
    @Test fun untypedCollectionCanBeDownloadedWithoutManualChoiceAndLibraryAlbumGroupsRemainExplicit() {
        val track=NativeTrack("12_1","Song","Artist",1,album=MusicAlbum("album:9","Release"))
        val page=NativePage(listOf(track),1);val unknown=list(7,PlaylistKind.UNKNOWN)
        assertTrue(ExportLayout.relative(ExportLayout.playlist(page,unknown).single()).contains("/Подборки/"))
        assertEquals(1,ExportLayout.library(page,albums=false).size)
        assertEquals(2,ExportLayout.library(page,albums=true).size)
    }
    @Test fun repeatedCollectionPositionsDoNotProduceFalseIncompleteWarningsButStuckPagesDo()=runBlocking {
        val a=list(1,PlaylistKind.PLAYLIST);val b=list(2,PlaylistKind.ALBUM);val c=list(3,PlaylistKind.PLAYLIST)
        val offsets=mutableListOf<Int>()
        val result=MusicPagination.catalog({offset->offsets.add(offset);NativeCatalog(if(offset==0)listOf(a,b)else listOf(b,c),4)})
        assertEquals(listOf(0,2),offsets);assertEquals(listOf(a,b,c),result.lists);assertTrue(result.complete)
        assertFalse(MusicPagination.catalog({NativeCatalog(listOf(a,b),4)}).complete)
        assertFalse(MusicPagination.catalog({offset->NativeCatalog(if(offset==0)listOf(a)else emptyList(),4)}).complete)
    }
}
