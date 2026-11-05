package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class PreparedExport(val tasks: List<AudioExportTask>,val failures: List<ExportSourceFailure>,
    val catalog: NativeCatalog?=null,val reusedSources: Int=0)

/** Plan from recent complete observations; the engine still validates every local file/exact audio ID. */
internal class ExportPreparation(private val api: MusicApi,private val cache: ExportReadCache,
    private val guard: suspend()->Unit={},private val progress:(String)->Unit={}) {
    suspend fun plan(access: MusicAccess,owner: Long,selected: NativePlaylist?=null,
        category: PlaylistKind?=null,libraryAlbums: Boolean=false,
        loadCatalog: suspend()->NativeCatalog={MusicCatalog.load(api,access,owner,guard=guard)}): PreparedExport {
        currentCoroutineContext().ensureActive();guard()
        musicCheck(access.accountId==null||access.accountId==owner,MusicError.ACCOUNT_CHANGED)
        val lease=cache.lease(owner,access.readSessionId)?:cache.begin(owner,access.readSessionId)
        val tasks=mutableListOf<AudioExportTask>();val failures=mutableListOf<ExportSourceFailure>()
        var reused=0
        suspend fun read(list: NativePlaylist?): NativePage {
            currentCoroutineContext().ensureActive();guard()
            val cached=if(list==null)cache.library(lease)else cache.playlist(lease,list)
            if(cached!=null){reused++;return cached}
            val page=MusicPagination.tracks({offset->
                guard()
                (if(list==null)api.libraryPage(access,owner,offset)else api.playlistPage(access,list,offset)).also{guard()}
            }){n,total->progress("Получено позиций: $n/$total")}
            guard();currentCoroutineContext().ensureActive()
            if(list==null)cache.rememberLibrary(lease,page)else cache.rememberPlaylist(lease,list,page)
            return page
        }
        if(category==null&&selected==null) {
            tasks.addAll(ExportLayout.library(read(null),albums=libraryAlbums,account=owner))
            guard();return PreparedExport(tasks,failures,reusedSources=reused)
        }
        val catalog=cache.catalog(lease,category?:selected?.kind)?:loadCatalog().also{
            guard();cache.rememberCatalog(lease,it)
        }
        if(category==null) {
            val list=catalog.lists.singleOrNull{it.key==selected!!.key&&!it.deleted}
                ?:throw MusicFailure(MusicError.UNAVAILABLE)
            tasks.addAll(ExportLayout.playlist(read(list),list,list.kind,account=owner))
        }else {
            if(!catalog.completeFor(category))failures.add(ExportSourceFailure(
                ExportSource(owner,"catalog_page",category.name),"Каталог ${category.folder}: получен не полностью",MusicError.INCOMPLETE,category))
            // Do not scan OTHER categories or trust a previous 'hidden/downloaded' verdict.
            // Include each selected source once; the engine validates/reuses its files.
            val lists=catalog.lists.filter{it.kind==category&&!it.deleted}.distinctBy{it.key}
            for((index,list) in lists.withIndex()) {
                currentCoroutineContext().ensureActive();guard()
                progress("Чтение ${index+1}/${lists.size}: ${category.folder}")
                try{tasks.addAll(ExportLayout.playlist(read(list),list,category,account=owner))}
                catch(e: MusicFailure) {
                    if(e.code !in setOf(MusicError.DENIED,MusicError.UNAVAILABLE,MusicError.METHOD,MusicError.INCOMPLETE,MusicError.LIBRARY_CHANGED))throw e
                    failures.add(ExportSourceFailure(ExportSource(owner,"catalog",list.key),list.title,e.code,category))
                }
            }
        }
        currentCoroutineContext().ensureActive();guard()
        return PreparedExport(tasks,failures,catalog,reused)
    }
}
