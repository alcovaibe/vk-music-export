package app.alcovaibe.vkmusicexport.data

import java.time.Instant

/** A confirmed native page replaces only its own source; other lists and duplicate positions survive. */
object CollectionMerge {
    fun replaceSource(current: CollectionSnapshot,source: MusicSource): CollectionSnapshot {
        val sources=linkedMapOf<String,MusicSource>()
        current.sources.forEach{sources[it.id]=it}
        sources[source.id]=source
        return current.copy(updatedAt=Instant.now().toString(),sources=sources.values.toList(),playlistDiscoveryConfirmed=false)
    }
}
