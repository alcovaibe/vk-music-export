package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CatalogProfileRegressionTest {
    private fun json(value: String)=Json.parseToJsonElement(value)
    private fun item(id: Int,type: Int)=NativePlaylist(12,id.toLong(),"List $id",1,null,null,null,type)
    private fun api(all: NativeCatalog,albums: NativeCatalog)=object: MusicApi {
        override suspend fun library(access: MusicAccess)=error("unused")
        override suspend fun catalog(access: MusicAccess,owner: Long)=all
        override suspend fun albumCatalogPage(access: MusicAccess,owner: Long,offset: Int)=albums
        override suspend fun playlist(access: MusicAccess,list: NativePlaylist)=error("unused")
        override suspend fun media(access: MusicAccess,track: NativeTrack)=error("unused")
    }
    @Test fun ignoredAlbumFilterCannotTurnAllPlaylistsIntoAlbums()=runTest {
        val all=NativeCatalog(listOf(item(1,0),item(2,1)),2)
        val result=MusicCatalog.load(api(all,all),MusicAccess("synthetic","CI"),12)
        assertEquals(listOf(PlaylistKind.PLAYLIST,PlaylistKind.ALBUM),result.lists.map{it.kind})
        assertTrue(result.complete)
    }
    @Test fun albumsReturnedOnlyByTheAlbumRequestAreRetainedAndConflictsStayUnclassified()=runTest {
        val raw=NativeProtocol.catalog(json("""{"count":2,"items":[{"owner_id":12,"id":1,"title":"Playlist","count":1,"type":0},{"owner_id":12,"id":2,"title":"Conflict","count":1,"type":0,"album_type":"album"}]}"""))
        val extra=item(3,1)
        val result=MusicCatalog.load(api(raw,NativeCatalog(listOf(raw.lists[1],extra),2)),MusicAccess("synthetic","CI"),12)
        assertEquals(listOf(1L,2L,3L),result.lists.map{it.id})
        assertEquals(PlaylistKind.UNKNOWN,result.lists[1].kind)
        val state=FriendMusicState(catalog=result,tab=FriendMusicTab.COLLECTIONS)
        assertEquals(listOf(2L),state.collections().map{it.id})
        assertEquals(listOf(1L),state.copy(tab=FriendMusicTab.PLAYLISTS).collections().map{it.id})
    }
    @Test fun cursorPagesPreserveAllListsAndRepeatedCursorsRemainIncomplete()=runTest {
        val calls=mutableListOf<Pair<Int,String?>>()
        val result=MusicPagination.catalogCursor({offset,cursor->
            calls.add(offset to cursor)
            if(cursor==null)NativeCatalog(listOf(item(1,0),item(2,1)),3,nextFrom="next",cursorBased=true)
            else NativeCatalog(listOf(item(3,0)),3,nextFrom=null,cursorBased=true)
        })
        assertEquals(listOf(0 to null,2 to "next"),calls);assertEquals(3,result.lists.size);assertTrue(result.complete)
        var n=0
        val stuck=MusicPagination.catalogCursor({_,_->n++;NativeCatalog(listOf(item(n,0)),3,nextFrom="same",cursorBased=true)})
        assertFalse(stuck.complete);assertEquals(2,stuck.lists.size)
    }
    @Test fun validPartialCatalogIsPublishedBeforeALaterPageFailure()=runTest {
        val partial=mutableListOf<NativeCatalog>()
        try {
            MusicPagination.catalogCursor({offset,_->if(offset==0)NativeCatalog(listOf(item(1,0)),2)else throw MusicFailure(MusicError.NETWORK)},partial=partial::add)
            fail("Later page must remain a failure")
        }catch(e: MusicFailure){assertEquals(MusicError.NETWORK,e.code)}
        assertEquals(1,partial.single().lists.size);assertFalse(partial.single().complete)
    }
    @Test fun incompleteOptionalAlbumDoesNotDiscardAudioAndRequiredFailureReportsExactPosition() {
        val warnings=mutableListOf<Pair<String,Int>>()
        val rows=NativeProtocol.page(json("""{"count":2,"items":[{"owner_id":12,"id":1,"title":"One","artist":"Artist","duration":1,"album":{"id":0}},{"owner_id":12,"id":2,"title":"Two\nLive","artist":"Artist","duration":2,"album":{"thumb":{}}}]}""")){f,i->warnings.add(f to i)}
        assertEquals(listOf("12_1","12_2"),rows.tracks.map{it.id});assertTrue(rows.tracks.all{it.album==null})
        assertEquals("Two Live",rows.tracks[1].title);assertEquals(listOf("album" to 1,"album" to 2),warnings)
        try {NativeProtocol.page(json("""{"count":1,"items":[{"owner_id":12,"id":3,"title":"PRIVATE","artist":"SECRET","duration":{}}]}"""));fail("Required metadata must not be guessed")}
        catch(e: NativeDataFailure){assertEquals("duration",e.field);assertEquals(1,e.position);assertEquals(201,e.atOffset(200).position);assertFalse(e.toString().contains("SECRET"))}
    }
    @Test fun catalogHeaderCanOpenTheSubscriptionsSectionWithoutMixingRecommendations()=runTest {
        val result=MusicArtists.subscriptions{method,fields->
            if(method=="catalog.getAudio")json("""{"catalog":{"sections":[{"title":"Для вас","blocks":[{"title":"Исполнители","artists_ids":["fake"]}]}],"header":{"title":"Моя музыка","buttons":[{"text":"Исполнители","action":{"section_id":"followed"}}]}}}""")
            else {assertEquals("followed",fields["section_id"]);json("""{"artists":[{"id":"real","name":"Subscribed"},{"id":"fake","name":"Recommendation"}],"section":{"blocks":[{"artists_ids":["real"]}]}}""")}
        }
        assertEquals(listOf("real"),result.map{it.id})
    }
    @Test fun realClientUsesCursorWithoutOffsetAndReportsGlobalMalformedTrackPosition()=runBlocking {
        val forms=mutableListOf<Map<String,String>>();val events=mutableListOf<String>()
        val client=NetworkClients.api().newBuilder().addInterceptor{chain->
            val form=chain.request().body as FormBody
            val values=(0 until form.size).associate{form.name(it) to form.value(it)};forms.add(values)
            val body=when {
                chain.request().url.encodedPath.endsWith("audio.get")->"""{"response":{"count":300,"items":[{"owner_id":12,"id":3,"title":"PRIVATE_TITLE","artist":"PRIVATE_ARTIST","duration":{}}]}}"""
                values["start_from"]==null->"""{"response":{"count":2,"next_from":"cursor","items":[{"owner_id":12,"id":1,"title":"Playlist","count":1,"type":0}]}}"""
                else->"""{"response":{"count":2,"next_from":"","items":[{"owner_id":12,"id":2,"title":"Album","count":1,"type":1}]}}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic").body(body.toResponseBody()).build()
        }.build()
        val api=VkAudioClient(client,observe={name,fields->events.add(name+fields.toString())})
        val access=MusicAccess("a".repeat(64),"CI",accountId=12)
        val result=MusicPagination.catalogCursor({offset,cursor->api.catalogPage(access,12,offset,cursor)})
        assertTrue(result.complete);assertEquals(2,result.lists.size)
        assertFalse(forms[0].containsKey("filters"));assertEquals("0",forms[0]["offset"])
        assertEquals("cursor",forms[1]["start_from"]);assertFalse(forms[1].containsKey("offset"))
        try{api.libraryPage(access,34,200);fail("Required duration is malformed")}
        catch(e: NativeDataFailure){assertEquals("duration",e.field);assertEquals(201,e.position)}
        assertTrue(events.any{it.contains("native_music_parse_failure")&&it.contains("position=201")})
        assertFalse(events.joinToString().contains("PRIVATE"));assertFalse(events.joinToString().contains(access.token))
    }

}
