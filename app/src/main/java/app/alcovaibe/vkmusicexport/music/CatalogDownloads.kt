package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Hide only an entire current source with matching account, membership and intact local originals. */
internal object CatalogDownloads {
    suspend fun completed(catalog: NativeCatalog,account: Long,saved: List<PublishedAudio>,
        fetch: suspend(NativePlaylist)->NativePage,valid: suspend(PublishedAudio)->Boolean,
        sourceReady: suspend(ExportSource,List<AudioExportTask>,List<PublishedAudio>)->Boolean={_,_,_->true}): Set<String> {
        val sources=saved.filter{it.source?.account==account&&it.source.kind=="catalog"&&it.catalogueVerified}
            .groupBy{it.source!!.id}
        val checked=mutableMapOf<Triple<String,Long,String>,Boolean>()
        val complete=mutableSetOf<String>()
        for(list in catalog.lists) {
            currentCoroutineContext().ensureActive()
            val rows=sources[list.key].orEmpty()
            if(list.count==0||rows.size<list.count)continue
            try {
                val page=fetch(list)
                if(page.count!=list.count||page.tracks.size!=page.count)continue
                val expected=ExportLayout.playlist(page,list,account=account)
                val byKey=rows.associateBy{it.taskKey}
                var all=true
                for(task in expected) {
                    currentCoroutineContext().ensureActive()
                    val row=byKey[task.key]
                    if(row==null||row.track.id!=task.track.id||
                        !AudioCompleteness.matchesCatalogue(row.durationMs,task.track.duration,row.track.duration)) {all=false;break}
                    val file=Triple(row.uri,row.bytes,row.sha256)
                    val intact=checked[file]?:valid(row).also{checked[file]=it}
                    if(!intact){all=false;break}
                }
                if(all&&sourceReady(ExportSource(account,"catalog",list.key),expected,expected.map{byKey.getValue(it.key)}))complete.add(list.key)
            }catch(e: CancellationException){throw e}
             catch(_: Exception){/* Unconfirmed files or server contents keep the collection visible. */}
        }
        return complete
    }
}
