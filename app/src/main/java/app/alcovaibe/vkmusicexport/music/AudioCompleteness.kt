package app.alcovaibe.vkmusicexport.music

import kotlin.math.abs
import kotlin.math.max

/** The manifest describes the received representation, not proof of a complete catalogue track. */
internal object AudioCompleteness {
    fun requireComplete(durationMs: Long, catalogueSeconds: Int, hlsSeconds: Double?) {
        musicCheck(durationMs > 0 && catalogueSeconds > 0, MusicError.INCOMPLETE)
        val actual = durationMs / 1000.0
        val catalogueTolerance = max(1.1, catalogueSeconds * 0.01)
        musicCheck(abs(actual - catalogueSeconds) <= catalogueTolerance, MusicError.INCOMPLETE)
        if (hlsSeconds != null) {
            musicCheck(hlsSeconds.isFinite() && hlsSeconds > 0, MusicError.INCOMPLETE)
            musicCheck(abs(hlsSeconds - catalogueSeconds) <= catalogueTolerance, MusicError.INCOMPLETE)
            musicCheck(abs(actual - hlsSeconds) <= max(0.35, hlsSeconds * 0.005), MusicError.INCOMPLETE)
        }
    }
}
