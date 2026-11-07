package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MusicSearchControllerTest {
    private val access=MusicAccess("a".repeat(64),"CI",accountId=12)
    private val song=NativeTrack("99_7","Title","Artist",180)
    private class Api: MusicApi {
        override val capabilities=ApiCapabilities(search=CapabilityStatus.EXPERIMENTAL,
            directAudioStream=CapabilityStatus.EXPERIMENTAL)
        val calls=mutableListOf<Pair<String,Int>>()
        var search: suspend(String,Int)->MetadataMatches={_,offset->MetadataMatches(emptyList(),0,offset)}
        override suspend fun searchMusic(access: MusicAccess,query: String,offset: Int): MetadataMatches {
            calls.add(query to offset);return search(query,offset)
        }
        override suspend fun library(access: MusicAccess): NativeProbe=error("Not a library read")
        override suspend fun catalog(access: MusicAccess,owner: Long): NativeCatalog=error("Not a catalog read")
        override suspend fun playlist(access: MusicAccess,list: NativePlaylist): NativePage=error("Not a playlist read")
        override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("No audio transfer in search")
    }
    private fun TestScope.controller(api: MusicApi)=MusicSearchController(backgroundScope,api,{access},{it})
    @Test fun debounceAndScreenPausePreventNewRequests()=runTest {
        val api=Api();val reader=controller(api)
        reader.setActive(true);reader.query("first");advanceTimeBy(200);reader.query("second")
        advanceTimeBy(349);runCurrent();assertTrue(api.calls.isEmpty())
        advanceTimeBy(1);runCurrent();assertEquals(listOf("second" to 0),api.calls)
        reader.query("third");reader.pause();advanceTimeBy(400);runCurrent()
        assertEquals(1,api.calls.size)
    }
    @Test fun nonCooperativeOldResponseCannotReplaceNewResults()=runTest {
        val api=Api();val stale=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>()
        api.search={query,offset->
            if(query=="old"){entered.complete(Unit);withContext(NonCancellable){stale.await()}}
            MetadataMatches(listOf(song.copy(title=query)),1,offset)
        }
        val reader=controller(api);reader.setActive(true);reader.query("old");advanceTimeBy(350);runCurrent()
        assertTrue(entered.isCompleted)
        reader.query("new");advanceTimeBy(350);runCurrent()
        stale.complete(Unit);runCurrent()
        assertEquals("new",reader.state.value.tracks.single().title)
        assertEquals(MusicSearchPhase.RESULTS,reader.state.value.phase)
    }
    @Test fun independentLoginRejectsLateResultAndVerifiedRenewalRetainsReadIdentity()=runTest {
        for(independent in listOf(false,true)) {
            var current=access;val result=CompletableDeferred<Unit>();val api=Api()
            api.search={_,offset->result.await();MetadataMatches(listOf(song),1,offset)}
            val reader=MusicSearchController(backgroundScope,api,{current},
                {initial->MusicReadIdentity(initial).require(current);current})
            reader.setActive(true);reader.query("test");advanceTimeBy(350);runCurrent()
            current=MusicAccess("b".repeat(64),"CI",accountId=12,
                readSessionId=if(independent)"new-login" else access.readSessionId)
            result.complete(Unit);runCurrent()
            assertEquals(if(independent)MusicSearchPhase.DENIED else MusicSearchPhase.RESULTS,reader.state.value.phase)
            assertEquals(if(independent)0 else 1,reader.state.value.tracks.size)
        }
    }
    @Test fun pagingKeepsOccurrencesAndOriginalOwnersForTrackActions()=runTest {
        val api=Api();api.search={_,offset->MetadataMatches(listOf(song),2,offset)}
        val reader=controller(api);reader.setActive(true);reader.query("test");advanceTimeBy(350);runCurrent()
        assertTrue(reader.state.value.canLoadMore)
        reader.more();runCurrent()
        assertEquals(listOf("test" to 0,"test" to 1),api.calls)
        val entries=reader.entries(reader.state.value)
        assertEquals(listOf("99_7","99_7"),entries.map{it.track.id})
        assertEquals(listOf(1,2),entries.map{it.export!!.occurrence})
        assertTrue(entries.all{it.account==12L&&it.export!!.source.account==12L&&it.readSessionId==access.readSessionId})
        assertNotEquals(entries[0].download!!.key,entries[1].download!!.key)
        reader.pause();assertTrue(reader.entries(reader.state.value).isEmpty())
    }
    @Test fun errorsChallengesEmptyAndUnavailableRemainDistinct()=runTest {
        for((error,phase) in listOf(MusicError.DENIED to MusicSearchPhase.DENIED,
            MusicError.CAPTCHA to MusicSearchPhase.CHALLENGE,MusicError.VALIDATION to MusicSearchPhase.CHALLENGE,
            MusicError.NETWORK to MusicSearchPhase.ERROR,MusicError.METHOD to MusicSearchPhase.UNAVAILABLE)) {
            val api=Api();api.search={_,_->throw MusicFailure(error)}
            val reader=controller(api);reader.setActive(true);reader.query("test");advanceTimeBy(350);runCurrent()
            assertEquals(phase,reader.state.value.phase)
        }
        val empty=controller(Api());empty.setActive(true);empty.query("none");advanceTimeBy(350);runCurrent()
        assertEquals(MusicSearchPhase.EMPTY,empty.state.value.phase)
        val missing=controller(object: MusicApi by Api(){override val capabilities=ApiCapabilities()})
        missing.setActive(true);missing.query("none");runCurrent()
        assertEquals(MusicSearchPhase.UNAVAILABLE,missing.state.value.phase)
    }
    @Test fun musicSearchHasIndependentLimitsAndDoesNotStopAtArtworkLookupLimit()=runTest {
        val api=Api()
        api.search={_,offset->MetadataMatches(List(MusicSearchQuery.PAGE_SIZE){index->song.copy(id="99_${offset+index+1}")},
            MusicSearchQuery.PAGE_SIZE*2,offset)}
        val reader=controller(api);reader.setActive(true);reader.query("test");advanceTimeBy(350);runCurrent()
        assertEquals(100,reader.state.value.tracks.size);assertTrue(reader.state.value.canLoadMore)
        reader.more();runCurrent()
        assertEquals(200,reader.state.value.tracks.size)
        assertEquals(listOf("test" to 0,"test" to 100),api.calls)
    }
}
