package app.alcovaibe.vkmusicexport.music

internal data class LinkedMusic(
    val page: NativePage,
    val account: Long,
    val readSessionId: String,
    val list: NativePlaylist? = null
) {
    fun entries(): List<PlaybackEntry> = page.tracks.map { track ->
        PlaybackEntry(track, list?.title ?: "Трек по ссылке", account,
            export = directTrackExport(account, track), readSessionId = readSessionId)
    }

    fun collectionTasks(): List<AudioExportTask> {
        val source = list ?: return emptyList()
        return ExportLayout.playlist(page, source, account = account)
    }
}

internal fun directTrackExport(account: Long, track: NativeTrack) =
    PlaybackExport(listOf("Поиск"), 1, ExportSource(account, "search", track.id), 1)

internal data class MusicLinkState(
    val request: Long = 0,
    val link: VkMusicLink? = null,
    val music: LinkedMusic? = null,
    val loading: Boolean = false,
    val error: MusicError? = null
)

internal suspend fun readLinkedMusic(api: MusicApi, access: MusicAccess, link: VkMusicLink): LinkedMusic {
    val account = access.accountId ?: api.accountOwner(access)
    musicCheck(account > 0, MusicError.ACCOUNT_CHANGED)
    return when (link) {
        is VkMusicLink.Track -> {
            val track = api.linkedTrack(access, link) ?: throw MusicFailure(MusicError.UNAVAILABLE)
            musicCheck(track.id == link.trackId)
            LinkedMusic(NativePage(listOf(track), 1), account, access.readSessionId)
        }
        is VkMusicLink.Collection -> {
            val reference = NativePlaylist(link.owner, link.id, "", 0, link.accessKey, null, null, null)
            val detail = api.playlistDetails(access, reference) ?: throw MusicFailure(MusicError.UNAVAILABLE)
            musicCheck(detail.key == reference.key && !detail.deleted, MusicError.UNAVAILABLE)
            val list = NativePlaylist(detail.owner, detail.id, detail.title, detail.count,
                link.accessKey ?: detail.accessKey, detail.originalOwner, detail.originalId, detail.nativeType,
                detail.kind, detail.following, photo = detail.photo, typeConflict = detail.typeConflict)
            val page = MusicPagination.tracks({ offset ->
                api.playlistPage(access, list, offset).also {
                    musicCheck(it.count <= 20_000, MusicError.TOO_LARGE)
                }
            })
            LinkedMusic(page, account, access.readSessionId, list.withCount(page.count))
        }
    }
}
