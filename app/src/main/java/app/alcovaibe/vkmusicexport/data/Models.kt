package app.alcovaibe.vkmusicexport.data

import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class Track(val vkId: String? = null, val title: String, val artist: String,
    val durationSeconds: Int? = null, val album: AlbumMetadata? = null, val restricted: Boolean = false)

@Serializable
data class AlbumMetadata(val id: String, val title: String)

@Serializable
data class Entry(val key: String, val track: Track, val position: Int)

@Serializable
enum class ScanStatus(val label: String) {
    SCANNING("Сбор"), UNCONFIRMED("Полнота не подтверждена"),
    COUNT_MATCHED("Количество совпало"), PARTIAL("Частично"),
    INTERRUPTED("Остановлен"), ERROR("Ошибка")
}

@Serializable
data class MusicSource(
    val id: String,
    val title: String,
    val url: String,
    val entries: List<Entry> = emptyList(),
    val expectedCount: Int? = null,
    val status: ScanStatus = ScanStatus.UNCONFIRMED,
    val reason: String = "NOT_SCANNED",
    val updatedAt: String = Instant.now().toString()
)

@Serializable
data class CollectionSnapshot(
    val schemaVersion: Int = 1,
    val updatedAt: String = Instant.now().toString(),
    val sources: List<MusicSource> = emptyList(),
    val playlistDiscoveryConfirmed: Boolean = false
) {
    fun uniqueTracks(): List<Track> {
        val known = HashSet<String>()
        return sources.flatMap { source -> source.entries.mapNotNull { entry ->
            val key = entry.track.vkId?.let { "vk:$it" } ?: "local:${source.id}:${entry.key}"
            entry.track.takeIf { known.add(key) }
        } }
    }
    val entriesCount: Int get() = sources.sumOf { it.entries.size }
    val playlistCount: Int get() = sources.count { it.id.startsWith("playlist:") }
}
