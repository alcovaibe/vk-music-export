package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class PlaylistObservation(val list: NativePlaylist,val page: NativePage,val reused: Boolean)

/** Shared bounded metadata reads. Callers supply the authenticated viewer and their cancellation guard. */
internal class MusicSourceReader(private val api: MusicApi,private val cache: ExportReadCache) {
    fun lease(viewer: Long,access: MusicAccess): ExportReadCache.Lease {
        musicCheck(viewer>0&&(access.accountId==null||access.accountId==viewer),MusicError.ACCOUNT_CHANGED)
        return cache.acquire(viewer,access.readSessionId)?:throw MusicFailure(MusicError.ACCOUNT_CHANGED)
    }
    private suspend fun active(guard:suspend()->Unit){currentCoroutineContext().ensureActive();guard();currentCoroutineContext().ensureActive()}
    suspend fun library(access: MusicAccess,viewer: Long,owner: Long=viewer,guard:suspend()->Unit={},
        partial:(NativePage)->Unit={},progress:(Int,Int)->Unit={_,_->}): NativePage {
        active(guard);val lease=lease(viewer,access)
        cache.library(lease,owner)?.let{partial(it);return it}
        val page=MusicPagination.tracks({offset->active(guard);api.libraryPage(access,owner,offset).also{active(guard)}},progress=progress,partial=partial)
        active(guard);cache.rememberLibrary(lease,page,owner);return page
    }
    suspend fun playlist(access: MusicAccess,viewer: Long,list: NativePlaylist,guard:suspend()->Unit={},
        partial:(NativePage)->Unit={},progress:(Int,Int)->Unit={_,_->}): PlaylistObservation {
        active(guard);val lease=lease(viewer,access)
        cache.playlist(lease,list)?.let{partial(it);return PlaylistObservation(list,it,true)}
        val page=MusicPagination.tracks({offset->active(guard);api.playlistPage(access,list,offset).also{active(guard)}},progress=progress,partial=partial)
        active(guard);val observed=cache.observePlaylist(lease,list,page)
        return PlaylistObservation(observed,page,false)
    }
    suspend fun catalog(access: MusicAccess,viewer: Long,owner: Long=viewer,force: Boolean=false,personal: Boolean=true,
        guard:suspend()->Unit={},partial:(NativeCatalog)->Unit={},onPagination:(String,NativeCatalog)->Unit={_,_->},
        progress:(Int,Int)->Unit={_,_->}): NativeCatalog {
        active(guard);val lease=lease(viewer,access)
        if(!force)cache.catalog(lease,null,owner)?.let{partial(it);return it}
        val result=MusicCatalog.load(api,access,owner,guard={active(guard)},personal=personal,partial=partial,onPagination=onPagination,progress=progress)
        active(guard);cache.rememberCatalog(lease,result,owner);return result
    }
}
