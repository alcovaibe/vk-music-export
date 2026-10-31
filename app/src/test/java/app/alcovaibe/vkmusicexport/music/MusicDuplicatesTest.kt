package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MusicDuplicatesTest {
    private val access=MusicAccess("a".repeat(64),"Synthetic agent")
    private val same=DuplicateSignature("mp3","a".repeat(64))
    private fun track(n: Int,title: String="Song",artist: String="Artist")=NativeTrack("12_$n",title,artist,1)
    private class Api(val tracks: MutableList<NativeTrack>): MusicApi {
        val deletes=mutableListOf<String>();val offsets=mutableListOf<Int>()
        var owner=12L;var loseReply=false
        override suspend fun library(access: MusicAccess)=NativeProbe(owner,NativePage(tracks.take(100),tracks.size))
        override suspend fun libraryPage(access: MusicAccess,owner: Long,offset: Int): NativePage {
            offsets.add(offset);return NativePage(tracks.drop(offset).take(100),tracks.size)
        }
        override suspend fun catalog(access: MusicAccess,owner: Long)=error("No catalog")
        override suspend fun playlist(access: MusicAccess,list: NativePlaylist)=error("No playlist")
        override suspend fun media(access: MusicAccess,track: NativeTrack)=error("Injected hasher")
        override suspend fun removeLibraryTrack(access: MusicAccess,owner: Long,track: NativeTrack) {
            assertEquals(this.owner,owner);deletes.add(track.id);tracks.removeAll{it.id==track.id}
            if(loseReply)throw MusicFailure(MusicError.NETWORK)
        }
    }
    private fun service(api: Api,hash: suspend(NativeTrack)->DuplicateSignature={same},guard: suspend()->Unit={})=
        MusicDuplicates(api,DuplicateHasher{_,track->hash(track)},guard=guard,pageDelayMs=0)
    private suspend fun fails(code: MusicError,block: suspend()->Unit) {
        try{block();fail("Expected $code")}catch(e: MusicFailure){assertEquals(code,e.code)}
    }
    @Test fun threeOrMoreMatchingRecordsKeepOneAndNeverDeleteOnDiscovery()=runBlocking {
        val a=track(1," SONG "," ARTIST");val b=track(2,"song","artist");val c=track(3,"Song","Artist")
        val api=Api(mutableListOf(a,b,c,track(4),track(5,"Other")))
        val calls=mutableListOf<String>()
        val report=service(api,hash={calls.add(it.id);if(it.id=="12_4")same.copy(sha256="b".repeat(64))else same}).scan(access)
        assertEquals(listOf("12_1","12_2","12_3","12_4"),calls)
        assertEquals(listOf("12_2","12_3"),report.duplicates.map{it.remove.id})
        assertTrue(report.duplicates.all{it.keep==a});assertTrue(api.deletes.isEmpty());assertEquals(1,report.problems.size)
    }
    @Test fun eachSingleUseConsentDeletesOnlyItsTargetAndFinalCopyRemains()=runBlocking {
        val api=Api((1..4).map{track(it)}.toMutableList());val engine=service(api);val report=engine.scan(access)
        for((i,row)in report.duplicates.withIndex()) {
            val ticket=DuplicateConsent(report.owner,access.credentialId,row)
            assertEquals(i,api.deletes.size);engine.remove(access,ticket)
            assertEquals(i+1,api.deletes.size);assertTrue(api.tracks.contains(row.keep))
            fails(MusicError.BUSY){engine.remove(access,ticket)};assertEquals(i+1,api.deletes.size)
        }
        assertEquals(listOf("12_2","12_3","12_4"),api.deletes);assertEquals(listOf(track(1)),api.tracks)
    }
    @Test fun differentAudioAndDifferentCodecAreNotDuplicatesEvenWithEqualNames()=runBlocking {
        val api=Api((1..3).map{track(it)}.toMutableList())
        val report=service(api,hash={when(it.id){"12_2"->same.copy(sha256="b".repeat(64));"12_3"->same.copy(codec="m4a");else->same}}).scan(access)
        assertTrue(report.duplicates.isEmpty());assertEquals(3,report.problems.size);assertTrue(api.deletes.isEmpty())
    }
    @Test fun repeatedSameIdAndForeignOwnersCannotBecomeDeletableDuplicates()=runBlocking {
        val a=track(1);val api=Api(mutableListOf(a,a,track(2).copy(id="13_2")))
        val report=service(api).scan(access)
        assertTrue(report.duplicates.isEmpty());assertEquals(2,report.problems.size)
        fails(MusicError.INCOMPLETE){service(api).remove(access,DuplicateConsent(12,access.credentialId,MusicDuplicate(a,a,same)))}
        assertTrue(api.deletes.isEmpty())
    }
    @Test fun missingKeeperPreventsDeletionBeforeHashingOrMutation()=runBlocking {
        val api=Api(mutableListOf(track(1),track(2)));var hashes=0
        val engine=service(api,hash={hashes++;same});val report=engine.scan(access);api.tracks.removeAt(0)
        fails(MusicError.LIBRARY_CHANGED){engine.remove(access,DuplicateConsent(12,access.credentialId,report.duplicates.single()))}
        assertEquals(2,hashes);assertTrue(api.deletes.isEmpty())
    }
    @Test fun changedAudioAfterDiscoveryPreventsDeletion()=runBlocking {
        val api=Api(mutableListOf(track(1),track(2)));var changed=false
        val engine=service(api,hash={if(changed&&it.id=="12_2")same.copy(sha256="b".repeat(64))else same})
        val report=engine.scan(access);changed=true
        fails(MusicError.INCOMPLETE){engine.remove(access,DuplicateConsent(12,access.credentialId,report.duplicates.single()))}
        assertTrue(api.deletes.isEmpty())
    }
    @Test fun libraryChangeDuringLongHashCheckInvalidatesConsent()=runBlocking {
        val api=Api(mutableListOf(track(1),track(2)));var mutate=false
        val engine=service(api,hash={if(mutate){api.tracks.add(track(99,"Other"));mutate=false};same})
        val report=engine.scan(access);mutate=true
        fails(MusicError.LIBRARY_CHANGED){engine.remove(access,DuplicateConsent(12,access.credentialId,report.duplicates.single()))}
        assertTrue(api.deletes.isEmpty())
    }
    @Test fun accountOrCredentialChangeCannotReplayOldConsent()=runBlocking {
        val api=Api(mutableListOf(track(1),track(2)));var current=true
        val engine=service(api,guard={musicCheck(current,MusicError.ACCESS)});val report=engine.scan(access)
        current=false
        fails(MusicError.ACCESS){engine.remove(access,DuplicateConsent(12,access.credentialId,report.duplicates.single()))}
        current=true;api.owner=13
        fails(MusicError.ACCESS){engine.remove(access,DuplicateConsent(12,access.credentialId,report.duplicates.single()))}
        assertTrue(api.deletes.isEmpty())
    }
    @Test fun completePaginationIncludesDuplicateBeyondFirstHundredPositions()=runBlocking {
        val list=(1..205).map{track(it,if(it in setOf(1,105,205))"Duplicate"else "Unique $it")}.toMutableList()
        val api=Api(list);var hashes=0
        val report=service(api,hash={hashes++;same}).scan(access)
        assertEquals(205,report.tracks);assertEquals(3,hashes);assertEquals(2,report.duplicates.size)
        assertEquals(listOf(100,200,100,200),api.offsets);assertTrue(api.deletes.isEmpty())
    }
    @Test fun ambiguousWriteNeverAutomaticallyRetriesAndNeedsNewConsentAndScan()=runBlocking {
        val api=Api(mutableListOf(track(1),track(2)));val engine=service(api);val report=engine.scan(access)
        val ticket=DuplicateConsent(12,access.credentialId,report.duplicates.single());api.loseReply=true
        fails(MusicError.NETWORK){engine.remove(access,ticket)}
        fails(MusicError.BUSY){engine.remove(access,ticket)}
        assertEquals(listOf("12_2"),api.deletes);assertEquals(listOf(track(1)),api.tracks)
    }
    @Test fun cancellationDuringVerificationDoesNotSendDelete()=runBlocking {
        val api=Api(mutableListOf(track(1),track(2)));var cancel=false
        val engine=service(api,hash={if(cancel)throw CancellationException("synthetic");same})
        val report=engine.scan(access);cancel=true
        try{engine.remove(access,DuplicateConsent(12,access.credentialId,report.duplicates.single()));fail("cancel")}
        catch(_: CancellationException){}
        assertTrue(api.deletes.isEmpty())
    }
    @Test fun invalidHashesCannotProveEquality()=runBlocking {
        val api=Api(mutableListOf(track(1),track(2)))
        val report=service(api,hash={DuplicateSignature("mp3","")}).scan(access)
        assertEquals(0,report.checked);assertTrue(report.duplicates.isEmpty());assertEquals(2,report.problems.size)
    }

    @Test fun reorderAtFinalValidationFinishesOnePassWithoutHashingAgain()=runBlocking<Unit> {
        val api=Api((1..3).map{track(it)}.toMutableList());val hashes=mutableListOf<String>();var reads=0
        val engine=MusicDuplicates(object: MusicApi by api {
            override suspend fun library(access: MusicAccess): NativeProbe {
                reads++;if(reads==2)api.tracks.reverse()
                return api.library(access)
            }
        },DuplicateHasher{_,t->hashes.add(t.id);same},pageDelayMs=0)
        val report=engine.scan(access)
        assertEquals(2,reads);assertEquals(listOf("12_1","12_2","12_3"),hashes)
        assertEquals(2,report.duplicates.size);assertFalse(report.libraryChanged);assertTrue(api.deletes.isEmpty())
    }
    @Test fun libraryEditsFinishWithOnlyUnchangedPairsAndDoNotRestartDiscovery()=runBlocking<Unit> {
        val api=Api((1..4).map{track(it)}.toMutableList());var hashes=0
        val report=service(api,hash={t->
            hashes++
            if(t.id=="12_4"){
                api.tracks.removeAt(0);api.tracks[0]=api.tracks[0].copy(restricted=true);api.tracks.add(track(99))
            }
            same
        }).scan(access)
        assertEquals(4,hashes);assertTrue(report.libraryChanged)
        assertEquals(listOf("12_4"),report.duplicates.map{it.remove.id});assertEquals("12_3",report.duplicates.single().keep.id)
        assertEquals(2,report.problems.count{it.error==MusicError.LIBRARY_CHANGED});assertTrue(api.deletes.isEmpty())
    }
    @Test fun unavailableCandidateDoesNotAbortLaterCandidatesButAccountFailureDoes()=runBlocking<Unit> {
        val api=Api((1..3).map{track(it)}.toMutableList());val calls=mutableListOf<String>()
        val report=service(api,hash={t->calls.add(t.id);if(t.id=="12_2")throw MediaHttpFailure(403);same}).scan(access)
        assertEquals(listOf("12_1","12_2","12_3"),calls);assertEquals(2,report.checked)
        assertEquals("12_3",report.duplicates.single().remove.id);assertEquals(MusicError.UNAVAILABLE,report.problems.single().error)
        calls.clear()
        fails(MusicError.ACCESS){service(api,hash={t->calls.add(t.id);if(t.id=="12_2")throw MusicFailure(MusicError.ACCESS,5);same}).scan(access)}
        assertEquals(listOf("12_1","12_2"),calls)
    }

    @Test fun verifiedRenewalKeepsConsentButAnIndependentLoginCannotUseIt()=runBlocking {
        val api=Api(mutableListOf(track(1),track(2)));val engine=service(api)
        val report=engine.scan(access)
        val renewed=MusicAccess("b".repeat(64),"CI",credentialId="renewed",accountId=12,readSessionId=access.readSessionId)
        val consent=DuplicateConsent(12,access.credentialId,report.duplicates.single(),report.readSessionId)
        engine.remove(renewed,consent,mutationAccess={renewed})
        assertEquals(listOf("12_2"),api.deletes)
        val newLogin=MusicAccess("c".repeat(64),"CI",accountId=12)
        fails(MusicError.ACCESS){engine.remove(newLogin,DuplicateConsent(12,access.credentialId,report.duplicates.single(),report.readSessionId))}
        assertEquals(1,api.deletes.size)
    }
    @Test fun credentialChangeDuringFinalMutationAccessNeverSendsDeletion()=runBlocking {
        val api=Api(mutableListOf(track(1),track(2)));val engine=service(api);val row=engine.scan(access).duplicates.single()
        fails(MusicError.ACCESS) {
            engine.remove(access,DuplicateConsent(12,access.credentialId,row),mutationAccess={MusicAccess("b".repeat(64),"CI",accountId=12)})
        }
        assertTrue(api.deletes.isEmpty())
    }

}
