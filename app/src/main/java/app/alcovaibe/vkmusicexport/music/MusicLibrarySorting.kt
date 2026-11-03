package app.alcovaibe.vkmusicexport.music

import java.util.Locale

/** VK returns the library in reverse addition order; keep that order as the default. */
internal enum class MusicLibrarySort(val title: String) {
    DEFAULT("По умолчанию"), NEWEST("Сначала новые"), OLDEST("Сначала старые"),
    ARTIST("По исполнителю"), TITLE("По названию"), YEAR("По году")
}
internal object MusicLibrarySorting {
    private fun russianOrder(value: String): Pair<Int,String> {
        val normalized=value.trimStart().lowercase(Locale.ROOT).replace('ё','е')
        val first=normalized.firstOrNull()
        val group=when {
            first==null->4
            first in '0'..'9'->0
            first in 'a'..'z'->1
            first in 'а'..'я'->2
            else->3
        }
        return group to normalized
    }
    fun tracks(tracks: List<NativeTrack>,sort: MusicLibrarySort): List<IndexedValue<NativeTrack>> {
        val rows=tracks.withIndex().toList()
        return when(sort) {
            MusicLibrarySort.DEFAULT,MusicLibrarySort.NEWEST->rows
            MusicLibrarySort.OLDEST->rows.reversed()
            MusicLibrarySort.TITLE->rows.sortedWith(compareBy<IndexedValue<NativeTrack>>({russianOrder(it.value.displayTitle).first},{russianOrder(it.value.displayTitle).second}))
            MusicLibrarySort.ARTIST->rows.sortedWith(compareBy<IndexedValue<NativeTrack>>({russianOrder(it.value.artist).first},{russianOrder(it.value.artist).second},{russianOrder(it.value.displayTitle).first},{russianOrder(it.value.displayTitle).second}))
            MusicLibrarySort.YEAR->rows.sortedWith(compareBy<IndexedValue<NativeTrack>>({it.value.releaseYear?:Int.MAX_VALUE},{it.index}))
        }
    }
    fun collections(items: List<NativePlaylist>,sort: MusicLibrarySort): List<NativePlaylist> =
        when(sort) {
            MusicLibrarySort.OLDEST->items.reversed()
            MusicLibrarySort.TITLE->items.sortedWith(compareBy({russianOrder(it.title).first},{russianOrder(it.title).second}))
            else->items
        }
}
