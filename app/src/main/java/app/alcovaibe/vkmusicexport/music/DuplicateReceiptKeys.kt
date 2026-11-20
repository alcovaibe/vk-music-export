package app.alcovaibe.vkmusicexport.music

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

internal object DuplicateReceiptKeys {
    val hex=Regex("[0-9a-f]{64}")
    const val TTL=24L*60*60*1000
    const val MAX_CHECKS=20000
    fun fresh(created: Long,now: Long)=created>=0&&now>=created&&now-created<TTL
    private fun formatDigest(bytes: ByteArray): String {
        val digits="0123456789abcdef"
        return buildString(64){for(byte in bytes) {
            val n=byte.toInt() and 255;append(digits[n ushr 4]);append(digits[n and 15])
        }}
    }
    private fun hash(raw: String) = formatDigest(MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()))

    fun key(track: NativeTrack)=hash(Json.encodeToString(track.copy(album=null)))
    fun legacy(track: NativeTrack)=hash(Json.encodeToString(track))
    fun snapshot(tracks: List<NativeTrack>): String {
        // Hash the same JSON array as before while keeping only one encoded track in memory.
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update('['.code.toByte())
        for ((index, track) in tracks.withIndex()) {
            if (index > 0) digest.update(','.code.toByte())
            digest.update(Json.encodeToString(track).toByteArray())
        }
        digest.update(']'.code.toByte())
        return formatDigest(digest.digest())
    }
}
