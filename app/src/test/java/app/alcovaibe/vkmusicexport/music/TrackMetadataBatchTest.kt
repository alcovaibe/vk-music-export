package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TrackMetadataBatchTest {
    private fun row(n: Int,owner: Long=12)=PublishedAudio("task-$n",NativeTrack("12_$n","Song $n","Artist",60),
        "content://media/external_primary/audio/media/$n","Song $n.mp3",ExportLayout.ROOT+"Моя музыка/","mp3","audio/mpeg",
        60000,100,"a".repeat(64),source=ExportSource(owner,"library","my"))
    private val credential=MusicAccess("a".repeat(64),"CI/1",accountId=12)
    private fun api(fetch:suspend(MusicAccess,String)->NativeTrack?)=object: MusicApi {
        override suspend fun library(access: MusicAccess): NativeProbe=error("No library scan")
        override suspend fun catalog(access: MusicAccess,owner: Long): NativeCatalog=error("No catalog")
        override suspend fun playlist(access: MusicAccess,list: NativePlaylist): NativePage=error("No playlist")
        override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("No audio request")
        override suspend fun metadata(access: MusicAccess,id: String)=fetch(access,id)
    }
    @Test fun automaticRenewalDuringMetadataFetchContinuesBeforeWritingAndAcrossTheWholeBatch()=runTest {
        var clock=1_800_000_000_000L;var rotations=0
        val rows=(1..4).map{row(it)};val initial=MusicAccess("a".repeat(64),"CI/1",clock+60_000,
            WebRenewal("p".repeat(64),12,emptyList()),lastVerifiedAtMs=clock,persisted=true,accountId=12)
        var saved=initial
        val store=object: MusicCredentialStore {
            override fun read()=saved
            override fun replace(expected: MusicAccess,next: MusicAccess): Boolean {
                if(saved.credentialId!=expected.credentialId)return false;saved=next;return true
            }
        }
        val sessions=MusicSessionManager(store,object: MusicRenewer {
            override suspend fun renew(access: MusicAccess): MusicAccess {
                rotations++;return MusicAccess(('b'.code+rotations).toChar().toString().repeat(64),"CI/1",clock+60_000,
                    access.renewal,persisted=true,accountId=12)
            }
        },{NativeProbe(12,NativePage(emptyList(),0))},{clock},io=Dispatchers.Unconfined)
        val requested=mutableListOf<String>();val written=mutableListOf<String>();var checkpoint: TrackMetadataRun?=null
        val batch=TrackMetadataBatch(api {a,id->
            clock+=49_000
            sessions.request(a){requested.add(id);rows.single{it.track.id==id}.track.copy(subtitle="sped up")}
        },{sessions.continueRead(initial)},find={t->rows.single{it.taskKey==t.taskKey}},verify={},
            update={row,_->sessions.continueRead(initial);written.add(row.taskKey);true},recover={},save={checkpoint=it})
        val result=batch.run(TrackMetadataRun.start(12,rows))
        assertEquals(4,result.updated);assertEquals(4,result.next);assertFalse(result.unfinished)
        assertEquals(4,rotations);assertEquals(rows.map{it.track.id},requested);assertEquals(rows.map{it.taskKey},written)
        assertEquals(result,checkpoint);assertNotEquals(initial.credentialId,saved.credentialId)
    }
    @Test fun rejectedAccessKeepsCurrentItemPendingAndANewRunnerResumesWithoutFetchingCompletedIds()=runTest {
        val rows=(1..3).map{row(it)};var checkpoint=TrackMetadataRun.start(12,rows)
        val requested=mutableListOf<String>();var blocked=true;var writes=0
        val endpoint=api{_,id->requested.add(id);if(id=="12_2"&&blocked)throw MusicFailure(MusicError.ACCESS,5)
            rows.single{it.track.id==id}.track.copy(subtitle="slowed")}
        fun runner()=TrackMetadataBatch(endpoint,{credential},{t->rows.single{it.taskKey==t.taskKey}},verify={},
            update={_,_->writes++;true},recover={},save={checkpoint=it})
        try{runner().run(checkpoint);fail("Must pause")}catch(e: MusicFailure){assertEquals(MusicError.ACCESS,e.code)}
        assertEquals(1,checkpoint.next);assertEquals(1,checkpoint.updated);assertEquals(0,checkpoint.failed)
        val onDisk=Json.encodeToString(checkpoint);assertFalse(onDisk.contains(credential.token))
        checkpoint=Json.decodeFromString(onDisk);blocked=false
        val done=runner().run(checkpoint)
        assertEquals(listOf("12_1","12_2","12_2","12_3"),requested)
        assertEquals(3,writes);assertEquals(3,done.updated);assertFalse(done.unfinished)
    }
    @Test fun networkLossAndCancellationPreserveTheCursorAndNeverTurnRemainingFilesIntoFailures()=runTest {
        for(failure in listOf(MusicFailure(MusicError.NETWORK),CancellationException())) {
            val rows=(1..3).map{row(it)};var checkpoint=TrackMetadataRun.start(12,rows)
            val batch=TrackMetadataBatch(api{_,id->if(id=="12_2")throw failure else rows[0].track},{credential},
                find={t->rows.single{it.taskKey==t.taskKey}},verify={},update={_,_->false},recover={},save={checkpoint=it})
            try{batch.run(checkpoint);fail("Must pause")}catch(e: Exception){assertSame(failure,e)}
            assertEquals(1,checkpoint.next);assertEquals(1,checkpoint.current);assertEquals(0,checkpoint.failed)
            assertTrue(checkpoint.unfinished)
        }
    }
    @Test fun aManualLoginBetweenFetchAndWriteIsRejectedEvenForTheSameAccount()=runTest {
        val saved=row(1);var access=credential;var checkpoint=TrackMetadataRun.start(12,listOf(saved));var writes=0
        val batch=TrackMetadataBatch(api{_,_->access=MusicAccess("b".repeat(64),"CI/1",accountId=12);saved.track},
            access={if(access.readSessionId!=credential.readSessionId)throw MusicReadChanged();access},find={saved},verify={},
            update={_,_->writes++;true},recover={},save={checkpoint=it})
        try{batch.run(checkpoint);fail("Manual login must pause")}catch(e: MusicReadChanged){}
        assertEquals(0,writes);assertEquals(0,checkpoint.next);assertEquals(0,checkpoint.failed)
    }
    @Test fun missingIdsChangedFilesAndOtherAccountsAreSeparateResultsAndTheRunContainsNoAccessMaterial()=runTest {
        val rows=listOf(row(1),row(2),row(3,99));var checkpoint=TrackMetadataRun.start(12,rows);val fetched=mutableListOf<String>()
        val batch=TrackMetadataBatch(api{_,id->fetched.add(id);null},{credential},find={t->rows.single{it.taskKey==t.taskKey}},
            verify={if(it.taskKey=="task-2")throw SavedFileFailure(SavedFileReason.HASH_CHANGED)},
            update={_,_->error("No writes")},recover={},save={checkpoint=it})
        val result=batch.run(checkpoint)
        assertEquals(listOf("12_1"),fetched);assertEquals(1,result.unavailable);assertEquals(2,result.skipped)
        assertEquals(0,result.failed);assertEquals(3,result.next)
        assertFalse(Json.encodeToString(result).contains(credential.token))
    }
}
