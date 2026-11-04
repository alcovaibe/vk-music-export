package app.alcovaibe.vkmusicexport.music

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

internal object DuplicateReceiptKeys {
    val hex=Regex("[0-9a-f]{64}")
    const val TTL=24L*60*60*1000
    const val MAX_CHECKS=20000
    fun fresh(created: Long,now: Long)=created>=0&&now>=created&&now-created<TTL
    private fun hash(raw: String): String {
        val digits="0123456789abcdef"
        return buildString(64){for(byte in MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())) {
            val n=byte.toInt() and 255;append(digits[n ushr 4]);append(digits[n and 15])
        }}
    }
    fun key(track: NativeTrack)=hash(Json.encodeToString(track.copy(album=null)))
    fun legacy(track: NativeTrack)=hash(Json.encodeToString(track))
    fun snapshot(tracks: List<NativeTrack>)=hash(Json.encodeToString(tracks))
}
