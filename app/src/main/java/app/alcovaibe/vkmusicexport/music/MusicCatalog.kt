package app.alcovaibe.vkmusicexport.music

internal enum class CatalogSection(val title: String,val kind: PlaylistKind?) {
    LIBRARY("Моя музыка",null),PLAYLISTS("Плейлисты",PlaylistKind.PLAYLIST),
    ALBUMS("Альбомы",PlaylistKind.ALBUM),UNKNOWN("Тип не определён",PlaylistKind.UNKNOWN);
    companion object {
        fun forKind(kind: PlaylistKind)=entries.first{it.kind==kind}
    }
}

internal object MusicCatalog {
    // Explicit server metadata wins if a formerly unknown collection becomes typed.
    fun kind(list: NativePlaylist,choices: Map<String,PlaylistKind>): PlaylistKind =
        if(list.kind!=PlaylistKind.UNKNOWN)list.kind else choices[list.key]?:PlaylistKind.UNKNOWN
    fun lists(catalog: NativeCatalog?,section: CatalogSection,choices: Map<String,PlaylistKind>): List<NativePlaylist> =
        catalog?.lists.orEmpty().filter{kind(it,choices)==section.kind}
}
