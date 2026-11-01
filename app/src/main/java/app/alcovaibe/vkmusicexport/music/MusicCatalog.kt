package app.alcovaibe.vkmusicexport.music

internal enum class CatalogSection(val title: String,val kind: PlaylistKind?) {
    LIBRARY("Моя музыка",null),PLAYLISTS("Плейлисты",PlaylistKind.PLAYLIST),
    ALBUMS("Альбомы",PlaylistKind.ALBUM),UNKNOWN("Подборки",PlaylistKind.UNKNOWN);
    companion object {
        fun forKind(kind: PlaylistKind)=entries.first{it.kind==kind}
    }
}

internal object MusicCatalog {
    /** Explicit item metadata wins over filters; only consistent album membership refines unknown types. */
    suspend fun load(api: MusicApi,access: MusicAccess,owner: Long,guard: suspend()->Unit={},personal: Boolean=true,partial:(NativeCatalog)->Unit={},progress:(Int,Int)->Unit={_,_->}): NativeCatalog {
        fun eligible(list: NativePlaylist)=!list.deleted&&(!personal||list.owner==owner||list.following!=false)
        val all=MusicPagination.catalogCursor({offset,cursor->guard();api.catalogPage(access,owner,offset,cursor).also{guard()}},progress,{part->partial(part.copy(lists=part.lists.filter(::eligible)))})
        guard()
        val first=try{api.albumCatalogPage(access,owner,0).also{guard()}}catch(e: MusicFailure){
            if(e.code in setOf(MusicError.METHOD,MusicError.DENIED))null else throw e
        }
        val albums=first?.let{MusicPagination.catalogCursor({offset,cursor->guard();if(offset==0&&cursor==null)it else api.albumCatalogPage(access,owner,offset,cursor)?:throw MusicFailure(MusicError.INCOMPLETE)})}
        val merged=linkedMapOf<String,NativePlaylist>()
        (all.lists+albums?.lists.orEmpty()).forEach{list->merged[list.key]=merged[list.key]?.reconciled(list)?:list}
        // A filter response may be ignored by VK. Never let it overrule explicit item metadata.
        val filterConfirmed=all.complete&&albums?.complete==true&&albums.lists.none{it.kind==PlaylistKind.PLAYLIST}&&
            albums.lists.map{it.key}.toSet()!=all.lists.map{it.key}.toSet()
        val albumKeys=if(filterConfirmed)albums!!.lists.flatMap{listOf(it.key)+listOfNotNull(it.originalOwner?.let{o->it.originalId?.let{i->"playlist:$o:$i"}})}.toSet() else emptySet()
        val current=merged.values.filter(::eligible).map{list->
            val original=list.originalOwner?.let{o->list.originalId?.let{i->"playlist:$o:$i"}}
            if(list.kind==PlaylistKind.UNKNOWN&&!list.typeConflict&&(list.key in albumKeys||original in albumKeys))list.classified(PlaylistKind.ALBUM)else list
        }
        return NativeCatalog(current,maxOf(all.count,merged.size),all.complete&&(albums?.complete!=false))
    }

    fun visible(state: NativeMusicState,section: CatalogSection=state.catalogSection)=
        lists(state.catalog,section,emptyMap()).filter{it.key !in state.hiddenCatalog}

    // Legacy manual choices are deliberately ignored; only current server metadata is used.
    fun kind(list: NativePlaylist,choices: Map<String,PlaylistKind>): PlaylistKind =
        list.kind
    fun lists(catalog: NativeCatalog?,section: CatalogSection,choices: Map<String,PlaylistKind>): List<NativePlaylist> =
        catalog?.lists.orEmpty().filter{kind(it,choices)==section.kind}
}
