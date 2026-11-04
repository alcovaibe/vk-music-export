package app.alcovaibe.vkmusicexport.music

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class DuplicateReceiptKeysTest {
    private val track=NativeTrack("12_1","Песня","Артист",60,album=MusicAlbum("old","Альбом"),subtitle="версия",releaseYear=2026)
    private fun oldHash(raw: String)=MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString(""){"%02x".format(it)}
    @Test fun keysRemainByteCompatibleWithBothPreviouslyShippedFormats() {
        assertEquals(oldHash(Json.encodeToString(track)),DuplicateReceiptKeys.legacy(track))
        assertEquals(oldHash(Json.encodeToString(track.copy(album=null))),DuplicateReceiptKeys.key(track))
        assertEquals(oldHash(Json.encodeToString(listOf(track))),DuplicateReceiptKeys.snapshot(listOf(track)))
        assertNotEquals(DuplicateReceiptKeys.legacy(track),DuplicateReceiptKeys.key(track))
    }
    @Test fun albumChangesKeepEvidenceButAudioMetadataChangesAndOrderAffectTheRightKeys() {
        val key=DuplicateReceiptKeys.key(track)
        assertEquals(key,DuplicateReceiptKeys.key(track.copy(album=MusicAlbum("new","New"))))
        for(changed in listOf(track.copy(id="12_2"),track.copy(duration=61),track.copy(subtitle="other"),track.copy(title="other")))
            assertNotEquals(key,DuplicateReceiptKeys.key(changed))
        assertNotEquals(DuplicateReceiptKeys.snapshot(listOf(track,track.copy(id="12_2"))),DuplicateReceiptKeys.snapshot(listOf(track.copy(id="12_2"),track)))
    }
    @Test fun expiryRejectsTheExactBoundaryRollbackAndInvalidDatesWithoutOverflow() {
        val created=1_800_000_000_000L
        assertTrue(DuplicateReceiptKeys.fresh(created,created))
        assertTrue(DuplicateReceiptKeys.fresh(created,created+DuplicateReceiptKeys.TTL-1))
        assertFalse(DuplicateReceiptKeys.fresh(created,created+DuplicateReceiptKeys.TTL))
        assertFalse(DuplicateReceiptKeys.fresh(created,created-1))
        assertFalse(DuplicateReceiptKeys.fresh(-1,Long.MAX_VALUE))
        assertFalse(DuplicateReceiptKeys.fresh(0,Long.MAX_VALUE))
    }
}
