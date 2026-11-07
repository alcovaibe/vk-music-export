package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class PreparedExport(val tasks: List<AudioExportTask>,val failures: List<ExportSourceFailure>,
    val catalog: NativeCatalog?=null,val reusedSources: Int=0,val selected: PlaylistObservation?=null)

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
            if(list==null)cache.rememberLibrary(lease,page)else cache.observePlaylist(lease,list,page)
            return page
        }
        if(category==null&&selected==null) {
            tasks.addAll(ExportLayout.library(read(null),albums=libraryAlbums,account=owner))
            guard();return PreparedExport(tasks,failures,reusedSources=reused)
        }
        if(category==null&&selected!=null) {
            // An explicit selected source does not require enumerating unrelated collections.
            val observation=MusicSourceReader(api,cache).playlist(access,owner,selected,guard=guard,
                progress={n,total->progress("Получено позиций: $n/$total")})
            guard();return PreparedExport(ExportLayout.playlist(observation.page,observation.list,observation.list.kind,account=owner),
                failures,reusedSources=if(observation.reused)1 else 0,selected=observation)
        }
        val kind=checkNotNull(category)
        val catalog=cache.catalog(lease,kind)?:loadCatalog().also{
            guard();cache.rememberCatalog(lease,it)
        }
        if(!catalog.completeFor(kind))failures.add(ExportSourceFailure(
            ExportSource(owner,"catalog_page",kind.name),"Каталог ${kind.folder}: получен не полностью",MusicError.INCOMPLETE,kind))
        val lists=catalog.lists.filter{it.kind==kind&&!it.deleted}.distinctBy{it.key}
        for((index,list) in lists.withIndex()) {
            currentCoroutineContext().ensureActive();guard()
            progress("Чтение ${index+1}/${lists.size}: ${kind.folder}")
            try{tasks.addAll(ExportLayout.playlist(read(list),list,kind,account=owner))}
            catch(e: MusicFailure) {
                if(e.code !in setOf(MusicError.DENIED,MusicError.UNAVAILABLE,MusicError.METHOD,MusicError.INCOMPLETE,MusicError.LIBRARY_CHANGED))throw e
                failures.add(ExportSourceFailure(ExportSource(owner,"catalog",list.key),list.title,e.code,kind))
            }
        }
        currentCoroutineContext().ensureActive();guard()
        return PreparedExport(tasks,failures,cache.catalog(lease,kind)?:catalog,reused)
    }
}
