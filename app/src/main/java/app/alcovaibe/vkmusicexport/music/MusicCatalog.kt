package app.alcovaibe.vkmusicexport.music

internal enum class CatalogSection(val title: String,val kind: PlaylistKind?) {
    LIBRARY("Моя музыка",null),PLAYLISTS("Плейлисты",PlaylistKind.PLAYLIST),
    ALBUMS("Альбомы",PlaylistKind.ALBUM),UNKNOWN("Подборки",PlaylistKind.UNKNOWN);
    companion object {
        fun forKind(kind: PlaylistKind)=entries.first{it.kind==kind}
    }
}

internal object MusicCatalog {
    /** Server album membership resolves numeric types without guessing from names or old user choices. */
    suspend fun load(api: MusicApi,access: MusicAccess,owner: Long,guard: suspend()->Unit={},progress:(Int,Int)->Unit={_,_->}): NativeCatalog {
        val all=MusicPagination.catalog({offset->guard();api.catalogPage(access,owner,offset)},progress)
        guard();val first=api.albumCatalogPage(access,owner,0)
        val albums=first?.let{MusicPagination.catalog({offset->guard();if(offset==0)it else api.albumCatalogPage(access,owner,offset)?:throw MusicFailure(MusicError.INCOMPLETE)})}
        if(albums!=null)musicCheck(albums.complete,MusicError.INCOMPLETE)
        val albumKeys=albums?.lists?.flatMap{listOf(it.key)+listOfNotNull(it.originalOwner?.let{o->it.originalId?.let{i->"playlist:$o:$i"}})}?.toSet()
        val current=all.lists.filter{!it.deleted&&(it.owner==owner||it.following!=false)}.map{list->
            if(albumKeys==null)list else {
                val original=list.originalOwner?.let{o->list.originalId?.let{i->"playlist:$o:$i"}}
                list.classified(if(list.key in albumKeys||(original!=null&&original in albumKeys))PlaylistKind.ALBUM else PlaylistKind.PLAYLIST)
            }
        }
        return NativeCatalog(current,all.count,all.complete)
    }
    fun visible(state: NativeMusicState,section: CatalogSection=state.catalogSection)=
        lists(state.catalog,section,emptyMap()).filter{it.key !in state.hiddenCatalog}

    // Legacy manual choices are deliberately ignored; only current server metadata is used.
    fun kind(list: NativePlaylist,choices: Map<String,PlaylistKind>): PlaylistKind =
        list.kind
    fun lists(catalog: NativeCatalog?,section: CatalogSection,choices: Map<String,PlaylistKind>): List<NativePlaylist> =
        catalog?.lists.orEmpty().filter{kind(it,choices)==section.kind}
}
