package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class CatalogClassificationRegressionTest {
    private val access=MusicAccess("a".repeat(64),"Synthetic",accountId=12)
    private fun row(id: Int,type: Int?=null)=NativePlaylist(12,id.toLong(),"Synthetic $id",1,null,null,null,type)
    private fun api(all: NativeCatalog,albums: NativeCatalog,detail:(NativePlaylist)->NativePlaylist?)=object: MusicApi {
        override suspend fun library(access: MusicAccess)=error("unused")
        override suspend fun catalog(access: MusicAccess,owner: Long)=all
        override suspend fun albumCatalogPage(access: MusicAccess,owner: Long,offset: Int)=albums
        override suspend fun playlistDetails(access: MusicAccess,list: NativePlaylist)=detail(list)
        override suspend fun playlist(access: MusicAccess,list: NativePlaylist)=error("unused")
        override suspend fun media(access: MusicAccess,track: NativeTrack)=error("unused")
    }
    @Test fun emptyAndNullCursorFieldsDoNotTruncateOffsetPagesAndDiagnosticsOmitPrivateValues()=runBlocking {
        val forms=mutableListOf<Map<String,String>>();val events=mutableListOf<String>()
        val client=NetworkClients.api().newBuilder().addInterceptor{chain->
            val form=chain.request().body as FormBody
            val values=(0 until form.size).associate{form.name(it) to form.value(it)};forms.add(values)
            val offset=values.getValue("offset").toInt()
            val response=buildJsonObject {
                put("count",5);put("next_from",if(offset==0)JsonNull else JsonPrimitive(""))
                put("items",JsonArray((offset+1..minOf(offset+2,5)).map{id->buildJsonObject {
                    put("owner_id",12);put("id",id);put("title","PRIVATE_TITLE");put("count",1);put("type",id%2)
                }}))
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
                .body(buildJsonObject{put("response",response)}.toString().toResponseBody()).build()
        }.build()
        val real=VkAudioClient(client,observe={name,fields->events.add(name+fields)})
        val catalog=MusicPagination.catalogCursor({offset,cursor->real.catalogPage(access,12,offset,cursor)})
        assertTrue(catalog.complete);assertEquals(5,catalog.lists.size)
        assertEquals(listOf("0","2","4"),forms.map{it["offset"]});assertTrue(forms.none{"start_from" in it})
        assertEquals(3,events.count{it.startsWith("native_catalog_page")})
        assertFalse(events.joinToString().contains("PRIVATE_TITLE"));assertFalse(events.joinToString().contains(access.token))
    }
    @Test fun explicitVkObjectTypeWinsOverReleaseHintsAndMissingMetadataIsNeverGuessedFromTheTitle() {
        val raw="""{"count":6,"items":[
            {"owner_id":12,"id":1,"title":"Generated year playlist","count":1,"type":0,"album_type":"album"},
            {"owner_id":12,"id":2,"title":"Compilation","count":1,"type":1,"album_type":"collection"},
            {"owner_id":12,"id":3,"title":"Album flag","count":1,"is_album":true},
            {"owner_id":12,"id":4,"title":"Playlist flag","count":1,"is_album":0},
            {"owner_id":12,"id":5,"title":"Release","count":1,"album_type":"compilation"},
            {"owner_id":-12,"id":6,"title":"Album 2025","count":1,"year":2025,"photo":{"photo_1200":"https://sun1.userapi.com/private"}}]}"""
        val parsed=NativeProtocol.catalog(Json.parseToJsonElement(raw))
        assertEquals(listOf(PlaylistKind.PLAYLIST,PlaylistKind.ALBUM,PlaylistKind.ALBUM,PlaylistKind.PLAYLIST,PlaylistKind.ALBUM,PlaylistKind.UNKNOWN),parsed.lists.map{it.kind})
        assertTrue(parsed.lists.none{it.typeConflict})
    }
    @Test fun ignoredAlbumFilterUsesCanonicalCardsForBothPersonalAndFriendProfiles()=runTest {
        val all=NativeCatalog(listOf(row(1),row(2)),2);val calls=mutableListOf<Long>()
        val service=api(all,all){list->calls.add(list.id);row(list.id.toInt(),if(list.id==1L)0 else 1)}
        val personal=MusicCatalog.load(service,access,12)
        val friend=MusicCatalog.load(service,access,34,personal=false)
        assertEquals(listOf(1L,2L,1L,2L),calls)
        for(catalog in listOf(personal,friend)) {
            assertTrue(catalog.complete)
            val state=FriendMusicState(catalog=catalog,tab=FriendMusicTab.PLAYLISTS)
            assertEquals(listOf(1L),state.collections().map{it.id})
            assertEquals(listOf(2L),state.copy(tab=FriendMusicTab.ALBUMS).collections().map{it.id})
            assertEquals(1,state.count(FriendMusicTab.PLAYLISTS));assertEquals(1,state.count(FriendMusicTab.ALBUMS))
        }
    }
    @Test fun contradictoryListResponsesAreResolvedByTheExactCanonicalCard()=runTest {
        var calls=0
        val service=api(NativeCatalog(listOf(row(1,0)),1),NativeCatalog(listOf(row(1,1)),1)){calls++;row(1,1)}
        val result=MusicCatalog.load(service,access,34,personal=false)
        assertEquals(1,calls);assertEquals(PlaylistKind.ALBUM,result.lists.single().kind);assertFalse(result.lists.single().typeConflict)
        val wrong=api(NativeCatalog(listOf(row(1)),1),NativeCatalog(listOf(row(1)),1)){row(2,1)}
        try{MusicCatalog.load(wrong,access,34,personal=false);fail("Another card must not classify this ID")}
        catch(e: MusicFailure){assertEquals(MusicError.RESPONSE,e.code)}
    }
    @Test fun confirmedAlbumMembershipClassifiesTheComplementAsPlaylistsWithoutDetailRequests()=runTest {
        val all=NativeCatalog(listOf(row(1),row(2)),2)
        val service=api(all,NativeCatalog(listOf(row(2)),1)){error("Confirmed membership does not require another request")}
        val result=MusicCatalog.load(service,access,34,personal=false)
        assertEquals(listOf(PlaylistKind.PLAYLIST,PlaylistKind.ALBUM),result.lists.map{it.kind})
    }
    @Test fun unresolvedMetadataNeverAddsAFourthTabOrClaimsZeroAlbumsOrDownloadsAGuessedType()=runTest {
        val all=NativeCatalog(listOf(row(1)),1)
        val result=MusicCatalog.load(api(all,all){throw MusicFailure(MusicError.DENIED,15)},access,34,personal=false)
        val state=FriendMusicState(catalog=result,tab=FriendMusicTab.PLAYLISTS)
        assertEquals(listOf("Треки","Плейлисты","Альбомы"),FriendMusicTab.entries.map{it.title})
        assertNull(state.count(FriendMusicTab.PLAYLISTS));assertNull(state.count(FriendMusicTab.ALBUMS))
        try{FriendMusicExport.tasks(NativePage(emptyList(),0),MusicFriend(34,"Synthetic",""),12,result.lists.single());fail("Do not guess the folder type")}
        catch(e: MusicFailure){assertEquals(MusicError.COLLECTION_TYPE,e.code)}
        val message=FriendMusicProblem(MusicError.INCOMPLETE).message
        assertTrue(message.contains("список"));assertFalse(message.contains("файл"));assertFalse(message.contains("длительност"))
    }
    @Test fun providerNextOffsetAdvancesPastUnavailablePositionsIncludingAnEmptyPage()=runTest {
        val offsets=mutableListOf<Int>()
        val result=MusicPagination.catalogCursor({offset,_->offsets.add(offset);when(offset) {
            0->NativeCatalog(listOf(row(1,0)),5,nextOffset=2)
            2->NativeCatalog(emptyList(),5,nextOffset=4)
            else->NativeCatalog(listOf(row(5,1)),5)
        }})
        assertTrue(result.complete);assertEquals(listOf(0,2,4),offsets);assertEquals(listOf(1L,5L),result.lists.map{it.id})
    }
    @Test fun detailRequestUsesExactIdentityAndAccessKeyAndAProfileDenialDoesNotBecomeAccountExpiry()=runBlocking {
        val requests=mutableListOf<Map<String,String>>();var denied=false
        val client=NetworkClients.api().newBuilder().addInterceptor{chain->
            assertTrue(chain.request().url.encodedPath.endsWith("audio.getPlaylistById"))
            val form=chain.request().body as FormBody
            requests.add((0 until form.size).associate{form.name(it) to form.value(it)})
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if(denied)403 else 200).message("Synthetic")
                .body(if(denied)"{}".toResponseBody() else """{"response":{"owner_id":-12,"id":7,"title":"Canonical album","count":1,"type":1}}""".toResponseBody()).build()
        }.build()
        val real=VkAudioClient(client,observe={_,_->});val requested=NativePlaylist(-12,7,"Short card",1,"PRIVATE_KEY",null,null,null)
        assertEquals(PlaylistKind.ALBUM,real.playlistDetails(access,requested).kind)
        assertEquals("-12",requests.single()["owner_id"]);assertEquals("7",requests.single()["playlist_id"]);assertEquals("PRIVATE_KEY",requests.single()["access_key"])
        denied=true
        try{real.playlistDetails(access,requested);fail("Scoped 403 must be explicit")}
        catch(e: MusicFailure){assertEquals(MusicError.DENIED,e.code);assertEquals(403,e.httpStatus)}
    }
}
