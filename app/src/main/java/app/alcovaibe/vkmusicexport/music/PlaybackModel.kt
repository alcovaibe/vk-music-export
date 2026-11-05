package app.alcovaibe.vkmusicexport.music

/** A listening queue owns IDs and optional app-owned local files, never signed media URLs or credentials. */
internal data class PlaybackEntry(val track: NativeTrack,val sourceLabel: String,val account: Long?=null,
    val saved: PublishedAudio?=null,val download: AudioExportTask?=null,val readSessionId: String?=null) {
    val offline get()=saved!=null
    override fun toString()="PlaybackEntry([metadata omitted], offline=$offline)"
}
internal data class PlaybackSelection(val entries: List<PlaybackEntry>,val start: Int)
internal object PlaybackLists {
    private fun playable(e: PlaybackEntry)=e.offline||e.track.metadataError==null&&e.track.duration>0&&!e.track.restricted
    fun firstPlayable(entries: List<PlaybackEntry>)=entries.indexOfFirst(::playable)
    fun select(entries: List<PlaybackEntry>,start: Int): PlaybackSelection? {
        if(start !in entries.indices)return null
        val selected=entries[start]
        if(!playable(selected))return null
        val positions=entries.indices.filter{playable(entries[it])}
        return PlaybackSelection(positions.map{entries[it]},positions.indexOf(start))
    }
    fun saved(rows: List<SavedLibraryEntry>)=rows.filter{it.playable}.distinctBy{it.file.uri}.map {
        PlaybackEntry(it.file.track,"Сохранённая музыка",saved=it.file)
    }
    fun library(state: NativeMusicState,sort: MusicLibrarySort): List<PlaybackEntry> {
        val owner=state.probe?.owner?:return emptyList()
        val page=state.page?:return emptyList()
        if(owner<=0||state.readSessionId==null||state.libraryStale&&state.selected==null)return emptyList()
        val selected=state.selected
        val kind=selected?.let{MusicCatalog.kind(it,state.catalogKinds)}?:PlaylistKind.UNKNOWN
        val tasks=if(selected==null)ExportLayout.library(page,albums=false,account=owner)
            else if(kind!=PlaylistKind.UNKNOWN)ExportLayout.playlist(page,selected,kind,account=owner)else emptyList()
        return MusicLibrarySorting.tracks(page.tracks,sort).map{row->
            PlaybackEntry(row.value,selected?.title?:"Моя музыка",owner,download=tasks.getOrNull(row.index),readSessionId=state.readSessionId)
        }
    }
    fun friendPositions(state: FriendMusicState)=state.page?.tracks.orEmpty().withIndex().filter{
        state.musicQuery.isBlank()||("${it.value.artist} ${it.value.displayTitle}").contains(state.musicQuery.trim(),true)
    }
    fun friends(state: FriendMusicState): List<PlaybackEntry> {
        val viewer=state.viewerAccount?:return emptyList();val user=state.user?:return emptyList();val page=state.page?:return emptyList()
        if(viewer<=0)return emptyList()
        val tasks=if(state.playlist?.kind!=PlaylistKind.UNKNOWN)FriendMusicExport.tasks(page,user,viewer,state.playlist)else emptyList()
        val source=state.playlist?.title?:"Музыка друга"
        val label=source+" · "+(user.handle?:user.name)
        return friendPositions(state).map {row->PlaybackEntry(row.value,label,viewer,download=tasks.getOrNull(row.index),readSessionId=state.readSessionId)}
    }
}
internal data class PlaybackState(val entries: List<PlaybackEntry> = emptyList(),val index: Int=0,
    val playing: Boolean=false,val buffering: Boolean=false,val playWhenReady: Boolean=false,val positionMs: Long=0,val durationMs: Long=0,
    val bufferedMs: Long=0,val seekable: Boolean=false,val shuffle: Boolean=false,val repeat: Int=0,
    val previous: Boolean=false,val next: Boolean=false,val order: List<Int> = emptyList(),val error: String?=null,val artwork: ByteArray?=null) {
    val current get()=entries.getOrNull(index)
    override fun toString()="PlaybackState(count=${entries.size}, index=$index, playing=$playing)"
}
