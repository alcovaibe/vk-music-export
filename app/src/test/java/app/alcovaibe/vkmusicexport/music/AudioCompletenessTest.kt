package app.alcovaibe.vkmusicexport.music

import org.junit.Assert.assertThrows
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
}
