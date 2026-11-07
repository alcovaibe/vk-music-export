package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Production catalog selection orchestration. The host owns admission/cancellation;
 * this controller owns section/playlist transitions and read identity checks.
 * Local files are never promoted to online authorization.
 */
internal class MusicCatalogController(
    private val _state: MutableStateFlow<NativeMusicState>,
    private val api: MusicApi,
    private val exportReads: ExportReadCache,
    private val access: suspend()->MusicAccess,
    private val checked: suspend(MusicAccess)->MusicAccess,
    private val operation: (String,suspend(Int)->Unit)->Unit,
    private val active: suspend(Int)->Unit,
    private val isCurrent: (Int)->Boolean,
    private val localPlayback: suspend(Long,NativePlaylist)->LocalCollectionPlayback?,
    private val remember: suspend(Long,PlaylistObservation)->Unit,
    private val report: (String,Map<String,Any?>)->Unit={_,_->}
) {
    val state=_state.asStateFlow()
    fun section(section: CatalogSection) {
        if(_state.value.busy)return
        _state.update{it.copy(catalogSection=section,selected=null,
            page=if(section==CatalogSection.LIBRARY)it.probe?.library else null,localPlayback=null)}
    }
    private suspend fun showLocalCollection(g: Int,owner: Long,list: NativePlaylist,reason: MusicError?=null): Boolean {
        val local=localPlayback(owner,list)?:return false
        active(g)
        musicCheck(_state.value.probe?.owner==owner||
            _state.value.probe==null&&_state.value.localCatalogAccount==owner,MusicError.ACCOUNT_CHANGED)
        _state.update{it.copy(page=NativePage(local.rows.map{row->row.track},local.rows.size),
            selected=list,catalogSection=CatalogSection.forKind(list.kind),localPlayback=local,localCatalogAccount=owner,
            error=null,message="Показаны сохранённые позиции подборки. Список ВК сейчас не подтверждён."+
                (reason?.let{" Код: "+it.name}?:""))}
        return true
    }
    fun select(list: NativePlaylist?) {
        val before=_state.value;val owner=before.probe?.owner?:before.localCatalogAccount?:return
        if(list==null){if(!before.busy)_state.update{it.copy(page=before.probe?.library,selected=null,
            catalogSection=CatalogSection.LIBRARY,localPlayback=null)};return}
        val displayed=before.catalog?.lists?.singleOrNull{it.key==list.key&&!it.deleted}?:return
        operation("playlist") selection@{g->
            _state.update{it.copy(page=null,selected=displayed,localPlayback=null,
                catalogSection=CatalogSection.forKind(displayed.kind))}
            if(displayed.key in before.localCatalogKeys&&showLocalCollection(g,owner,displayed))return@selection
            try {
                val probe=before.probe?:throw MusicFailure(MusicError.ACCESS)
                val a=access();active(g)
                if(before.readSessionId==null||a.readSessionId!=before.readSessionId)throw MusicReadChanged()
                val read=MusicSourceReader(api,exportReads).playlist(a,probe.owner,displayed,guard={active(g)},
                    progress={n,total->_state.update{it.copy(preparing="Получено треков: $n/$total")}},
                    partial={part->if(isCurrent(g))_state.update{old->if(old.selected?.key==displayed.key)old.copy(page=part)else old}})
                active(g)
                val current=checked(a);MusicReadIdentity(a).require(current)
                musicCheck(current.accountId==null||current.accountId==owner,MusicError.ACCOUNT_CHANGED)
                remember(owner,read)
                MusicReadIdentity(a).require(checked(a))
                active(g)
                _state.update{old->old.copy(page=read.page,selected=read.list,localPlayback=null,
                    catalogSection=CatalogSection.forKind(read.list.kind),
                    catalog=old.catalog?.let{catalog->catalog.copy(lists=catalog.lists.map{if(it.key==read.list.key)read.list else it})})}
                report("native_music_sample",mapOf("stage" to "playlist","entries" to read.page.tracks.size,
                    "expected_count" to read.page.count,"complete" to true))
            }catch(e: CancellationException){throw e}
             catch(e: MusicReadChanged){
                _state.update{it.copy(page=null,localPlayback=null)}
                throw e
             }catch(e: MusicFailure){
                if(e.code !in setOf(MusicError.NETWORK,MusicError.TIMEOUT,MusicError.HTTP,MusicError.TLS,
                    MusicError.DENIED,MusicError.UNAVAILABLE,MusicError.METHOD,MusicError.ACCESS,MusicError.INVALID_ACCESS)||
                    !showLocalCollection(g,owner,displayed,e.code))throw e
            }
        }
    }

}
