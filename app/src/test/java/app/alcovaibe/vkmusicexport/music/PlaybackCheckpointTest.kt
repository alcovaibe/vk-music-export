package app.alcovaibe.vkmusicexport.music

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PlaybackCheckpointTest {
    private val track=NativeTrack("12_1","Track","Artist",180)
    private fun online(session: String="session",owner: Long=12)=PlaybackEntry(track,"Friend / album",owner,readSessionId=session,
        export=PlaybackExport(listOf("Friends"),3,ExportSource(owner,"friend","34"),2))
    private fun access(owner: Long=12,session: String="session",credential: String="credential")=
        MusicAccess("token","agent",accountId=owner,readSessionId=session,credentialId=credential)
    private fun record()=PlaybackCheckpoint("queue",listOf(online(),online(),online()),"session",PlaybackCursor(1,42000,true,2),listOf(2,0,1))
    @Test fun serializationPreservesOccurrenceSourceAndSessionWithoutResolvedLinks() {
        val entry=online();val raw=Json.encodeToString(entry)
        assertEquals(entry,Json.decodeFromString<PlaybackEntry>(raw))
        assertEquals(entry.download,Json.decodeFromString<PlaybackEntry>(raw).download)
        assertFalse(raw.contains("token"));assertFalse(raw.contains("https://"));assertFalse(raw.contains("download"))
    }
    @Test fun renewalIsAcceptedButNewLoginSameOwnerForeignOwnerAndLogoutAreRejected() {
        val record=record();record.validate()
        assertTrue(record.accepts(access(credential="rotated")))
        assertFalse(record.accepts(access(session="new login")))
        assertFalse(record.accepts(access(owner=34)))
        assertFalse(record.accepts(null))
    }
    @Test fun pausedRestoreKeepsDuplicateEntriesPositionAndModesWithoutAutoplay() {
        val state=record().pausedState()
        assertEquals(3,state.entries.size);assertEquals(1,state.index);assertEquals(42000L,state.positionMs)
        assertEquals(listOf(2,0,1),state.order);assertTrue(state.shuffle);assertEquals(2,state.repeat)
        assertFalse(state.playing);assertFalse(state.playWhenReady);assertFalse(state.buffering)
    }
    @Test fun shuffledNeighboursRespectTheStoredPermutationAndRepeatAll() {
        val record=record()
        assertEquals(2,record.adjacent(1));assertEquals(0,record.adjacent(-1))
        assertNull(record.copy(cursor=record.cursor.copy(repeat=0)).adjacent(1))
        assertEquals(2,record.copy(cursor=record.cursor.copy(shuffle=false)).adjacent(1))
    }
    @Test fun invalidIndexOrderNegativePositionMixedAccountsAndSessionFailClosed() {
        val record=record()
        val bad=listOf(record.copy(order=listOf(0,0,1)),record.copy(order=listOf(0,1)),record.copy(cursor=record.cursor.copy(index=3)),
            record.copy(cursor=record.cursor.copy(positionMs=-1)),record.copy(cursor=record.cursor.copy(repeat=3)),
            record.copy(entries=listOf(online(),online(owner=34),online())),record.copy(session="another"))
        bad.forEach{try{it.validate();fail("Invalid checkpoint accepted")}catch(_: IllegalArgumentException){}}
    }
}
