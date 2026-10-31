package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TrackMetadataLookupTest {
    private val access=MusicAccess("a".repeat(64),"CI/1",accountId=12)
    private val saved=NativeTrack("12_1","Song","Original artist",120)
    private val row=PublishedAudio("task",saved,"content://media/external_primary/audio/media/1","Song.mp3",ExportLayout.ROOT+"Моя музыка/","mp3","audio/mpeg",120000,100,"a".repeat(64))
    private val selected=NativeTrack("56_2","Song","Other artist",121,subtitle="Cover sped up")
    private class Api(var track: NativeTrack?): MusicApi {
        val ids=mutableListOf<String>();var searches=0
        override suspend fun library(access: MusicAccess): NativeProbe=error("No library scan")
        override suspend fun catalog(access: MusicAccess,owner: Long): NativeCatalog=error("No catalog")
        override suspend fun playlist(access: MusicAccess,list: NativePlaylist): NativePage=error("No playlist")
        override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("No audio request")
        override suspend fun metadata(access: MusicAccess,id: String): NativeTrack? {ids.add(id);return track}
        override suspend fun searchMetadata(access: MusicAccess,query: String,offset: Int): MetadataMatches {searches++;return MetadataMatches(listOfNotNull(track),1,offset)}
    }
    private suspend fun fails(code: MusicError,block:suspend()->Unit){try{block();fail("Expected $code")}catch(e: MusicFailure){assertEquals(code,e.code)}}
    @Test fun searchAndLinkNeedNeitherCoverNorMediaAndNeverAutomaticallyWrite()=runTest {
        val api=Api(selected);val lookup=TrackMetadataLookup(api)
        assertEquals(listOf(selected),lookup.find(access,"Artist Song").tracks)
        val link=lookup.find(access,"https://vk.com/audio56_2")
        assertTrue(link.byLink);assertEquals(selected,link.tracks.single());assertEquals(1,api.searches)
        assertEquals(listOf("56_2"),api.ids)
        fails(MusicError.MEDIA_URL){lookup.find(access,"https://evil.test/audio56_2")}
    }
    @Test fun explicitChoiceChangesOnlyTextAndCanBeAppliedOnce()=runTest {
        val api=Api(selected);val lookup=TrackMetadataLookup(api);val choice=lookup.preview(access,row,selected);var writes=0
        assertTrue(lookup.apply(access,choice){target,text,reference->
            writes++;assertEquals(row,target);assertEquals(selected.id,reference)
            assertEquals(saved.copy(title=selected.title,subtitle=selected.subtitle),text);true
        })
        fails(MusicError.BUSY){lookup.apply(access,choice){_,_,_->error("Second write")}}
        assertEquals(1,writes)
    }
    @Test fun verifiedTokenRotationPreservesTheChoiceButAnotherLoginDoesNot()=runTest {
        val api=Api(selected);val lookup=TrackMetadataLookup(api)
        val choice=lookup.preview(access,row,selected)
        val renewed=MusicAccess("b".repeat(64),"CI/1",credentialId="renewed",accountId=12,readSessionId=access.readSessionId)
        assertTrue(lookup.apply(renewed,choice){_,_,_->true})
        fails(MusicError.ACCESS){lookup.apply(MusicAccess("c".repeat(64),"CI/1",accountId=12),lookup.preview(access,row,selected)){_,_,_->error("New login")}}
    }
    @Test fun expiredAndChangedRecordsNeverReachWriter()=runTest {
        var now=100L;val api=Api(selected);val lookup=TrackMetadataLookup(api,{now})
        val expired=lookup.preview(access,row,selected);now+=300_000
        fails(MusicError.INCOMPLETE){lookup.apply(access,expired){_,_,_->error("Expired")}}
        val changed=lookup.preview(access,row,selected);api.track=selected.copy(subtitle="slowed")
        fails(MusicError.INCOMPLETE){lookup.apply(access,changed){_,_,_->error("Changed")}}
    }
    @Test fun textSearchParserDoesNotRequireSafeCoverOrPlayableUrlAndLegacyReportsStillDecode() {
        val result=NativeProtocol.metadataSearch(Json.parseToJsonElement("""{"count":1,"items":[{"owner_id":56,"id":2,"title":"Song","subtitle":"sped up","artist":"Artist","duration":120,"thumb":{"photo_600":"https://invalid.test/p"}}]}"""),0)
        assertEquals("sped up",result.tracks.single().subtitle)
        val old=Json.decodeFromString<TrackMetadataIssue>("""{"name":"Song.mp3","reason":"Unavailable"}""")
        assertNull(old.target);assertFalse(old.resolved)
    }
}
