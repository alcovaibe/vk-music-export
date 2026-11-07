package app.alcovaibe.vkmusicexport.music

internal data class PreparedFriendDownload(val tasks: List<AudioExportTask>,val page: NativePage?=null,val list: NativePlaylist?=null)
/** Selected positions mean exact entries of the displayed prefix; a full-source download requires completeness. */
internal class FriendDownloadPreparation(private val api: MusicApi,private val cache: ExportReadCache) {
    suspend fun plan(before: FriendMusicState,access: MusicAccess,viewer: Long,positions: Set<Int>?,guard:suspend()->Unit): PreparedFriendDownload {
        val user=before.user?:throw MusicFailure(MusicError.UNAVAILABLE)
        val shown=before.page?:throw MusicFailure(MusicError.INCOMPLETE)
        musicCheck(before.readSessionId!=null&&before.readSessionId==access.readSessionId,MusicError.ACCESS)
        musicCheck(before.viewerAccount==null||before.viewerAccount==viewer,MusicError.ACCOUNT_CHANGED)
        musicCheck(access.accountId==null||access.accountId==viewer,MusicError.ACCOUNT_CHANGED)
        guard()
        if(positions!=null) {
            musicCheck(positions.isNotEmpty()&&positions.all{it in 1..shown.tracks.size},MusicError.LIBRARY_CHANGED)
            if(positions.size==1) {
                val track=shown.tracks[positions.single()-1]
                musicCheck(track.metadataError==null&&!track.restricted&&track.duration>0,MusicError.UNAVAILABLE)
            }
            return PreparedFriendDownload(FriendMusicExport.selected(shown,user,viewer,before.playlist,positions)).also{guard()}
        }
        musicCheck(before.complete&&shown.tracks.size==shown.count,MusicError.INCOMPLETE)
        val reader=MusicSourceReader(api,cache)
        val list=before.playlist
        val observation=list?.let{reader.playlist(access,viewer,it,guard=guard)}
        val read=observation?.page?:reader.library(access,viewer,user.id,guard=guard)
        guard()
        return PreparedFriendDownload(FriendMusicExport.tasks(read,user,viewer,observation?.list),read,observation?.list)
    }
}
