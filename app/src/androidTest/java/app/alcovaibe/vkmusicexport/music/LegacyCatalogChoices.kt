package app.alcovaibe.vkmusicexport.music

/** Historical meta-format fixtures only. App runtime never applies a manual classification. */
internal fun AudioExportStore.catalogKinds(account: Long): Map<String,PlaylistKind> {
    musicCheck(account>0,MusicError.STORAGE)
    val prefix="catalog_kind:$account:"
    return checkpoints(prefix).mapNotNull{(name,value)->
        val kind=runCatching{PlaylistKind.valueOf(value)}.getOrNull()
        if(kind==null||kind==PlaylistKind.UNKNOWN)null else name.removePrefix(prefix) to kind
    }.toMap()
}
internal fun AudioExportStore.catalogKind(account: Long,list: NativePlaylist,kind: PlaylistKind) {
    musicCheck(account>0,MusicError.STORAGE)
    val name="catalog_kind:$account:${list.key}"
    if(kind==PlaylistKind.UNKNOWN)removeCheckpoint(name)else checkpoint(name,kind.name)
}
