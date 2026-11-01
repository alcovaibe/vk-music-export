package app.alcovaibe.vkmusicexport.music

/** A listening queue owns IDs and optional app-owned local files, never signed media URLs or credentials. */
internal data class PlaybackEntry(val track: NativeTrack,val sourceLabel: String,val account: Long?=null,
    val saved: PublishedAudio?=null,val download: AudioExportTask?=null) {
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
    fun saved(rows: List<SavedLibraryEntry>)=rows.filter{it.present}.distinctBy{it.file.uri}.map {
        PlaybackEntry(it.file.track,"Сохранённая музыка",saved=it.file)
    }
    fun friends(state: FriendMusicState): List<PlaybackEntry> {
        val viewer=state.viewerAccount?:return emptyList();val user=state.user?:return emptyList();val page=state.page?:return emptyList()
        if(!state.complete||viewer<=0)return emptyList()
        val tasks=FriendMusicExport.tasks(page,user,viewer,state.playlist)
        val source=state.playlist?.title?:"Музыка друга"
        val label=source+" · "+(user.handle?:user.name)
        return page.tracks.mapIndexed {i,track->PlaybackEntry(track,label,viewer,download=tasks[i])}
    }
}
internal data class PlaybackState(val entries: List<PlaybackEntry> = emptyList(),val index: Int=0,
    val playing: Boolean=false,val buffering: Boolean=false,val playWhenReady: Boolean=false,val positionMs: Long=0,val durationMs: Long=0,
    val bufferedMs: Long=0,val seekable: Boolean=false,val shuffle: Boolean=false,val repeat: Int=0,
    val previous: Boolean=false,val next: Boolean=false,val order: List<Int> = emptyList(),val error: String?=null,val artwork: ByteArray?=null) {
    val current get()=entries.getOrNull(index)
    override fun toString()="PlaybackState(count=${entries.size}, index=$index, playing=$playing)"
}
