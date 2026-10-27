package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ArtworkLookupTest {
    private val access=MusicAccess("a".repeat(64),"Synthetic")
    private val old=NativeTrack("12_34","Saved song","Artist",120)
    private val row=PublishedAudio("synthetic",old,"content://media/external_primary/audio/media/1","Saved.mp3",ExportLayout.ROOT+"Моя музыка/","mp3","audio/mpeg",120000,100,"b".repeat(64))
    private val image=CoverImage(byteArrayOf(1,2,3),"image/jpeg")
    private fun match(n: Int=1)=ArtworkMatch(old.copy(id="56_$n"),listOf(ArtworkCandidate("https://sun9.userapi.com/$n",ArtworkOrigin.TRACK)))
    private class Api(val list: List<ArtworkMatch>): MusicApi {
        var searches=0;var records=0;var lastQuery="";var lastOffset=0;var reference=""
        override suspend fun library(access: MusicAccess): NativeProbe=error("Not used")
        override suspend fun catalog(access: MusicAccess,owner: Long): NativeCatalog=error("Not used")
        override suspend fun playlist(access: MusicAccess,list: NativePlaylist): NativePage=error("Not used")
        override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("No audio download")
        override suspend fun removeLibraryTrack(access: MusicAccess,owner: Long,track: NativeTrack)=error("No remote mutation")
        override suspend fun searchArtwork(access: MusicAccess,query: String,offset: Int): ArtworkMatches {
            searches++;lastQuery=query;lastOffset=offset;return ArtworkMatches(list,41,offset)
        }
        override suspend fun artworkRecord(access: MusicAccess,id: String): ArtworkMatch?{records++;reference=id;return list.firstOrNull()}
    }
    private suspend fun fails(code: MusicError,block:suspend()->Unit){try{block();fail("Expected $code")}catch(e: MusicFailure){assertEquals(code,e.code)}}
    @Test fun lookupReturnsCandidatesWithoutSelectingLoadingOrWritingAndSupportsBoundedPages()=runBlocking {
        val candidates=listOf(match(),match(2));val api=Api(candidates);var images=0
        val service=ArtworkLookup(api,{ArtworkSource{_,_->images++;image}})
        assertEquals(candidates,service.find(access," Artist song ",20).matches)
        assertEquals("Artist song",api.lastQuery);assertEquals(20,api.lastOffset);assertEquals(0,images)
        fails(MusicError.RESPONSE){service.find(access,"song",100)};assertEquals(1,api.searches)
    }
    @Test fun directLinkResolvesOnlyItsExactRecordWithoutSearchOrOpeningTheUrl()=runBlocking {
        val candidate=match();val api=Api(listOf(ArtworkMatch(candidate.track.copy(id="-56_1"),candidate.candidates)));val service=ArtworkLookup(api,{error("No image yet")})
        val page=service.find(access,"https://vk.com/music?z=audio-56_1")
        assertTrue(page.byLink);assertEquals("-56_1",api.reference);assertEquals(1,api.records);assertEquals(0,api.searches)
        fails(MusicError.RESPONSE){service.find(access,"https://vk.com/audio56_1",20)}
    }
    @Test fun linksRejectOtherHostsCredentialsRedirectShortenersPlaylistsAndMalformedIdsBeforeApi()=runBlocking {
        val api=Api(emptyList());val service=ArtworkLookup(api,{error("No image")})
        for(url in listOf("http://vk.com/audio1_2","https://vk.com.evil.test/audio1_2","https://vk.com@evil.test/audio1_2",
            "https://secret@vk.com/audio1_2","https://vk.com:444/audio1_2","https://vk.cc/secret","https://vk.com/music?z=audio_playlist1_2",
            "https://vk.com/audio0_2","https://vk.com/audio1_0","https://vk.com/audio1_2#secret","https://vk.com/audio9223372036854775808_2"))
            fails(MusicError.MEDIA_URL){service.find(access,url)}
        assertEquals(0,api.searches);assertEquals(0,api.records)
        assertEquals("-12_34",ArtworkQuery.reference("m.vk.ru/audio-12_34"))
    }
    @Test fun previewAndExplicitSingleUseChoiceKeepOriginalIdentityAndOnlyPassImageToWriter()=runBlocking {
        val candidate=match();val api=Api(listOf(candidate));var writes=0;var images=0
        val service=ArtworkLookup(api,{selected->ArtworkSource{_,t->assertSame(candidate,selected);assertEquals(candidate.track,t);images++;image}})
        val choice=service.preview(access,row,candidate);assertEquals(1,images);assertEquals(0,writes)
        assertTrue(service.apply(access,choice){target,cover->writes++;assertEquals(row,target);assertEquals(old,target.track);assertSame(image,cover);true})
        fails(MusicError.BUSY){service.apply(access,choice){_,_->writes++;true}};assertEquals(1,writes)
    }
    @Test fun expiredChoiceAndChangedCredentialsCannotReachWriter()=runBlocking {
        var now=100L;val api=Api(listOf(match()));val service=ArtworkLookup(api,{ArtworkSource{_,_->image}},clock={now})
        val expired=service.preview(access,row,match());now+=300000
        fails(MusicError.INCOMPLETE){service.apply(access,expired){_,_->error("Expired write")}}
        val changed=service.preview(access,row,match())
        fails(MusicError.ACCESS){service.apply(MusicAccess("c".repeat(64),"Synthetic"),changed){_,_->error("Wrong credentials")}}
    }
    @Test fun cancellationAfterImageAndBeforeWriteLeavesNoApplicableChoice()=runBlocking {
        var allowed=true;val api=Api(listOf(match()));var writes=0
        val service=ArtworkLookup(api,{ArtworkSource{_,_->allowed=false;image}},guard={if(!allowed)throw CancellationException("Synthetic")})
        try{service.preview(access,row,match());fail("cancel")}catch(_: CancellationException){}
        allowed=true;val choice=ArtworkLookup(api,{ArtworkSource{_,_->image}}).preview(access,row,match());allowed=false
        try{service.apply(access,choice){_,_->writes++;true};fail("cancel")}catch(_: CancellationException){}
        assertEquals(0,writes)
    }
    @Test fun noCoverRestrictedAndAlreadyCoveredTargetsCannotCreateChoice()=runBlocking {
        val api=Api(emptyList());val service=ArtworkLookup(api,{error("No image request")})
        fails(MusicError.UNAVAILABLE){service.preview(access,row,ArtworkMatch(old,emptyList()))}
        fails(MusicError.UNAVAILABLE){service.preview(access,row,ArtworkMatch(old.copy(restricted=true),match().candidates))}
        fails(MusicError.INCOMPLETE){service.preview(access,row.copy(coverEmbedded=true),match())}
    }
    private fun json(raw: String)=Json.parseToJsonElement(raw)
    private fun track(n: Int,thumb: String="")="""{"owner_id":56,"id":$n,"title":"Synthetic","artist":"Artist","duration":120$thumb}"""
    @Test fun searchParserRetainsCoverVariantsAndMissingCoverPerRowWithoutGuessing() {
        val with=track(1,",\"thumb\":{\"photo_600\":\"https://sun9.userapi.com/a\",\"photo_300\":\"https://sun9.userapi.com/b\"}")
        val unsafe=track(3,",\"thumb\":{\"photo_600\":\"https://evil.test/secret\"}")
        val page=NativeProtocol.artworkSearch(json("""{"count":3,"items":[$with,${track(2)},$unsafe]}"""),0)
        assertEquals(listOf(600,300),page.matches[0].candidates.map{it.size});assertTrue(page.matches[0].selectable)
        assertFalse(page.matches[1].selectable);assertNull(page.matches[1].coverError)
        assertFalse(page.matches[2].selectable);assertEquals(MusicError.MEDIA_URL,page.matches[2].coverError)
        assertFalse(page.matches.toString().contains("evil.test"))
    }
    @Test fun malformedOversizedAndDuplicateSearchResultsAreNotTrusted() {
        for(raw in listOf("[]","{}","""{"count":0,"items":[${track(1)}]}""","""{"count":2,"items":[${track(1)},${track(1)}]}""",
            """{"count":21,"items":[${(1..21).joinToString(","){track(it)}}]}"""))
            assertEquals(MusicError.RESPONSE,assertThrows(MusicFailure::class.java){NativeProtocol.artworkSearch(json(raw),0)}.code)
    }
    @Test fun linkResponseMustContainExactlyTheRequestedRecordAndEmptyIsNotFound() {
        assertNull(NativeProtocol.artworkRecord(json("[]"),"56_1"))
        assertEquals("56_1",NativeProtocol.artworkRecord(json("[${track(1)}]"),"56_1")!!.track.id)
        for(raw in listOf("{}","[${track(2)}]","[${track(1)},${track(2)}]"))
            assertEquals(MusicError.RESPONSE,assertThrows(MusicFailure::class.java){NativeProtocol.artworkRecord(json(raw),"56_1")}.code)
    }
    @Test fun actualClientUsesSearchReadOnlyParametersAndNeverLogsQueryUrlsOrNames()=runBlocking {
        val requests=mutableListOf<Request>();val events=mutableListOf<Pair<String,Map<String,Any?>>>()
        val client=NetworkClients.api().newBuilder().addInterceptor{chain->
            requests.add(chain.request());val body=if(chain.request().url.encodedPath.endsWith("audio.search"))"""{"response":{"count":1,"items":[${track(1)}]}}""" else """{"response":[${track(1)}]}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic").body(body.toResponseBody()).build()
        }.build()
        val api=VkAudioClient(client){type,data->events.add(type to data)}
        api.searchArtwork(access,"PRIVATE_ARTIST & PRIVATE_TITLE",0);api.artworkRecord(access,"56_1")
        val form=requests[0].body as FormBody;val fields=(0 until form.size).associate{form.name(it) to form.value(it)}
        assertEquals("PRIVATE_ARTIST & PRIVATE_TITLE",fields["q"]);assertEquals("20",fields["count"]);assertEquals("0",fields["search_own"])
        assertEquals(listOf("/method/audio.search","/method/audio.getById"),requests.map{it.url.encodedPath})
        assertFalse(events.toString().contains("PRIVATE_"));assertFalse(events.toString().contains(access.token));assertFalse(events.toString().contains("56_1"))
    }
    @Test fun deniedSearchIsReportedWithoutFallbackMutation()=runBlocking {
        var calls=0
        val client=NetworkClients.api().newBuilder().addInterceptor{chain->calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
                .body("""{"error":{"error_code":15,"error_msg":"PRIVATE"}}""".toResponseBody()).build()
        }.build()
        fails(MusicError.DENIED){VkAudioClient(client){_,_->}.searchArtwork(access,"Artist song",0)};assertEquals(1,calls)
    }
    @Test fun searchHttp403DoesNotRenewRejectOrReplaceTheWorkingMusicCredential()=runBlocking {
        val now=1_800_000_000_000L
        val stable=MusicAccess("a".repeat(64),"Synthetic",now+3_600_000,WebRenewal("b".repeat(64),12,emptyList()),
            lastVerifiedAtMs=now,credentialId="stable",persisted=true)
        var replacements=0;var renewals=0;var searchCalls=0
        val store=object: MusicCredentialStore {
            var value=stable
            override fun read()=value
            override fun replace(expected: MusicAccess,next: MusicAccess): Boolean {replacements++;value=next;return true}
        }
        val sessions=MusicSessionManager(store,object: MusicRenewer {
            override suspend fun renew(access: MusicAccess): MusicAccess {renewals++;throw MusicFailure(MusicError.ACCESS)}
        },verify={error("No renewal verification expected")},wall={now},io=Dispatchers.Unconfined)
        val client=NetworkClients.api().newBuilder().addInterceptor{chain->
            val path=chain.request().url.encodedPath
            val (status,body)=when {
                path.endsWith("audio.search")->{searchCalls++;403 to "Forbidden"}
                path.endsWith("users.get")->200 to """{"response":[{"id":12}]}"""
                else->200 to """{"response":{"count":0,"items":[]}}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Synthetic").body(body.toResponseBody()).build()
        }.build()
        val api=VkAudioClient(client,sessions){_,_->}
        fails(MusicError.DENIED){api.searchArtwork(stable,"Artist song",0)}
        assertEquals(12L,api.library(stable).owner)
        assertEquals(1,searchCalls);assertEquals(0,renewals);assertEquals(0,replacements)
        assertSame(stable,store.read());assertFalse(store.read().rejected);assertEquals(SessionStatus.VALID,sessions.state.value.status)
    }

}
