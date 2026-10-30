package app.alcovaibe.vkmusicexport.music

import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class AudioCompletenessTest {
    @Test fun completedThirtySecondPlaylistCannotStandInForThreeMinuteSong() {
        assertThrows(MusicFailure::class.java){AudioCompleteness.requireComplete(30_000,180,30.0)}
        assertThrows(MusicFailure::class.java){AudioCompleteness.requireComplete(180_000,180,30.0)}
        assertThrows(MusicFailure::class.java){AudioCompleteness.requireComplete(30_000,180,null)}
    }
    @Test fun catalogueRoundingAndSmallCodecDelayAreAllowed() {
        AudioCompleteness.requireComplete(180_120,180,180.1)
        AudioCompleteness.requireComplete(1_223,1,1.2)
        AudioCompleteness.requireComplete(179_900,180,null)
    }
    @Test fun cachedPreviewAndChangedCatalogueDurationCannotBeReused() {
        assertFalse(AudioCompleteness.matchesCatalogue(30_000,180))
        assertFalse(AudioCompleteness.matchesCatalogue(180_000,300))
        assertFalse(AudioCompleteness.matchesCatalogue(0,180))
        assertTrue(AudioCompleteness.matchesCatalogue(180_120,180))
        assertTrue(AudioCompleteness.matchesCatalogue(1223,0,1))
        assertFalse(AudioCompleteness.matchesCatalogue(30_000,180,30))
        assertFalse(AudioCompleteness.matchesCatalogue(30_000,0,180))
    }
    @Test fun durationMismatchRetainsCatalogueActualAndManifestMeasurementsForReport() {
        val failure=assertThrows(AudioDurationMismatch::class.java){AudioCompleteness.requireComplete(30_000,180,30.0)}
        val details=exportFailureDetails(failure)!!
        assertEquals(180,details.expectedSeconds);assertEquals(30_000L,details.actualMs);assertEquals(30.0,details.hlsSeconds)
        assertEquals(details,Json.decodeFromString<ExportFailureDetails>(Json.encodeToString(details)))
        assertTrue(details.label.contains("180"));assertTrue(details.label.contains("30.0"))
    }
    @Test fun mediaHttpAndTrackAccessReasonsAreSeparateFromAccountFailures() {
        assertEquals(403,exportFailureDetails(MediaHttpFailure(403))!!.httpStatus)
        assertEquals("RESTRICTED",exportFailureDetails(TrackUnavailable(TrackAccessIssue.RESTRICTED))!!.accessReason)
        assertNull(exportFailureDetails(MusicFailure(MusicError.ACCESS)))
    }
}
