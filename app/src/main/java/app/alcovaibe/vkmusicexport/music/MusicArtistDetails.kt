package app.alcovaibe.vkmusicexport.music

import java.time.LocalDate
import java.util.Locale

internal enum class ArtistTracks(val title: String) {
    POPULAR("Популярные треки"),
    ALL("Все треки")
}

internal data class MusicArtistRelease(
    val playlist: NativePlaylist,
    val type: String? = null,
    val date: LocalDate? = null,
    val performer: String? = null,
    val featuredTracks: List<String> = emptyList(),
    val year: Int? = null,
) {
    val id get() = "${playlist.owner}_${playlist.id}"
    val trackAlbum get() = MusicAlbum(id, playlist.title)
}

internal data class MusicArtistDetails(
    val artist: MusicArtist,
    val tracks: NativePage,
    val releases: List<MusicArtistRelease>,
    val popular: List<NativeTrack>? = null,
    val appearances: List<MusicArtistRelease> = emptyList(),
    val latest: MusicArtistRelease? = null,
    val complete: Boolean = true,
) {
    fun tracks(group: ArtistTracks): List<NativeTrack>? =
        if (group == ArtistTracks.POPULAR) popular else tracks.tracks
}

internal fun MusicFailure.blocksArtistRead() = when (code) {
    MusicError.ACCESS, MusicError.ACCOUNT_CHANGED, MusicError.INVALID_ACCESS,
    MusicError.RATE_LIMIT, MusicError.CAPTCHA, MusicError.VALIDATION -> true
    else -> false
}

private data class ArtistRecording(val performer: String, val title: String, val duration: Int)

/** Merge the same recording across releases while retaining named versions and durations. */
internal fun artistDiscography(tracks: Collection<NativeTrack>): NativePage {
    fun normalized(value: String) = value.trim().lowercase(Locale.ROOT)
    fun playable(track: NativeTrack) = track.duration > 0 && !track.restricted && track.metadataError == null
    fun merge(first: NativeTrack?, second: NativeTrack): NativeTrack {
        if (first == null) return second
        if (!playable(first) && playable(second)) return second.copy(album = second.album ?: first.album)
        return if (first.album == null && second.album != null) first.copy(album = second.album) else first
    }
    val byId = linkedMapOf<String, NativeTrack>()
    for (track in tracks) byId[track.id] = merge(byId[track.id], track)
    val unique = linkedMapOf<ArtistRecording, NativeTrack>()
    val unresolved = mutableListOf<NativeTrack>()
    for (track in byId.values) {
        if (track.duration == 0 || track.metadataError != null) {
            unresolved.add(track)
            continue
        }
        val key = ArtistRecording(normalized(track.artist), normalized(track.displayTitle), track.duration)
        unique[key] = merge(unique[key], track)
    }
    val ordered = MusicLibrarySorting.tracks(unique.values + unresolved, MusicLibrarySort.TITLE).map { it.value }
    return NativePage(ordered, ordered.size)
}
