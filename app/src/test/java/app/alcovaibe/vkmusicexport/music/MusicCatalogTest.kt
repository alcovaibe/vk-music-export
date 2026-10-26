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
        assertEquals(listOf(0,100),offsets)
        assertEquals(100,MusicCatalog.lists(catalog,CatalogSection.PLAYLISTS,emptyMap()).size)
        assertEquals(listOf(input[100]),MusicCatalog.lists(catalog,CatalogSection.ALBUMS,emptyMap()))
        assertEquals(listOf(input[101]),MusicCatalog.lists(catalog,CatalogSection.UNKNOWN,emptyMap()))
    }
    @Test fun explicitTypesAndManualUnknownChoicesStaySeparateWithoutGuessingFromTitlesOrNumbers() {
        val response=Json.parseToJsonElement("""{"count":5,"items":[
            {"owner_id":12,"id":1,"title":"Album title","count":1,"type":1},
            {"owner_id":12,"id":2,"title":"Playlist","count":1,"type":"album"},
            {"owner_id":12,"id":3,"title":"Album","count":1,"type":"playlist"},
            {"owner_id":12,"id":4,"title":"Single","count":1,"album_type":"single"},
            {"owner_id":12,"id":5,"title":"EP","count":1,"album_type":"ep"}]}""")
        val catalog=NativeProtocol.catalog(response)
        val choices=mapOf(catalog.lists[0].key to PlaylistKind.ALBUM,catalog.lists[2].key to PlaylistKind.ALBUM)
        assertEquals(PlaylistKind.UNKNOWN,catalog.lists[0].kind)
        assertEquals(4,MusicCatalog.lists(catalog,CatalogSection.ALBUMS,choices).size)
        assertEquals(listOf(catalog.lists[2]),MusicCatalog.lists(catalog,CatalogSection.PLAYLISTS,choices))
        assertTrue(MusicCatalog.lists(catalog,CatalogSection.UNKNOWN,choices).isEmpty())
    }
    @Test fun unknownCollectionCannotSilentlyCreatePlaylistTasksAndLibraryAlbumGroupsAreExplicit() {
        val track=NativeTrack("12_1","Song","Artist",1,album=MusicAlbum("album:9","Release"))
        val page=NativePage(listOf(track),1);val unknown=list(7,PlaylistKind.UNKNOWN)
        assertEquals(MusicError.COLLECTION_TYPE,assertThrows(MusicFailure::class.java){ExportLayout.playlist(page,unknown)}.code)
        assertTrue(ExportLayout.relative(ExportLayout.playlist(page,unknown,PlaylistKind.ALBUM).single()).contains("/Альбомы/"))
        assertEquals(1,ExportLayout.library(page,albums=false).size)
        assertEquals(2,ExportLayout.library(page,albums=true).size)
    }
}
