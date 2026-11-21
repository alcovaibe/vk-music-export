package app.alcovaibe.vkmusicexport.music

import java.util.concurrent.TimeUnit

/** Search/links, the player service and the download job run in the same process. */
internal object VkStreamHints {
    private data class Key(val session: String, val track: String, val accessKey: String?)
    private data class Hint(val stream: ResolvedAudio, val addedAt: Long)
    private val lifetime = TimeUnit.MINUTES.toNanos(2)
    private val hints = object : LinkedHashMap<Key, Hint>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Hint>?) = size > 1_000
    }

    @Synchronized
    fun remember(access: MusicAccess, track: NativeTrack, stream: ResolvedAudio) {
        hints[Key(access.readSessionId, track.id, track.accessKey)] = Hint(stream, System.nanoTime())
    }

    @Synchronized
    fun read(access: MusicAccess, track: NativeTrack): ResolvedAudio? {
        val key = Key(access.readSessionId, track.id, track.accessKey)
        val hint = hints[key] ?: return null
        if (System.nanoTime() - hint.addedAt >= lifetime) {
            hints.remove(key)
            return null
        }
        return hint.stream.takeIf {
            !track.restricted && track.metadataError == null && it.track.duration == track.duration
        }
    }
}
