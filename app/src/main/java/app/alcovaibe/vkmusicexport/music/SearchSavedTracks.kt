package app.alcovaibe.vkmusicexport.music

import java.util.Locale

internal object SearchSavedTracks {
    private data class Description(val artist: String, val title: String, val duration: Int)

    private fun description(track: NativeTrack): Description? {
        if (track.metadataError != null || track.duration <= 0) return null
        return Description(track.artist.trim().lowercase(Locale.ROOT),
            track.displayTitle.trim().lowercase(Locale.ROOT), track.duration)
    }

    fun match(tracks: List<NativeTrack>, files: List<SavedLibraryEntry>, added: Map<String, String>,
        account: Long): Map<NativeTrack, PublishedAudio> {
        val available = files.asSequence().filter { it.present }.map { it.file }.filter {
            val owner = it.source?.account ?: 0
            owner == 0L || owner == account
        }.toList()
        val byId = available.groupBy { it.track.id }
        val byDescription = available.filter { it.catalogueVerified }
            .mapNotNull { file -> description(file.track)?.let { it to file } }.groupBy({ it.first }, { it.second })
        return buildMap {
            for (track in tracks) {
                val exact = (byId[track.id].orEmpty() + added[track.id]?.let(byId::get).orEmpty()).firstOrNull {
                    AudioCompleteness.matchesCatalogue(it.durationMs, track.duration, it.track.duration)
                }
                val described = description(track)?.let(byDescription::get).orEmpty().distinctBy { it.uri }
                val match = exact ?: described.singleOrNull()?.takeIf {
                    AudioCompleteness.matchesCatalogue(it.durationMs, track.duration, it.track.duration)
                }
                if (match != null) put(track, match)
            }
        }
    }
}
