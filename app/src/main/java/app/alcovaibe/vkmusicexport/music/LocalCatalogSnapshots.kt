package app.alcovaibe.vkmusicexport.music

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Local browsing metadata only: no accessKey, photo/media URL, token, or authorization proof. */
@Serializable internal data class LocalCatalogSnapshot(
    val account: Long,val owner: Long,val id: Long,val title: String,val count: Int,val kind: PlaylistKind,
    val order: List<String> = emptyList()
) {
    val key get()="playlist:$owner:$id"
    fun playlist()=NativePlaylist(owner,id,title,count,null,null,null,null,kind)
    override fun toString()="LocalCatalogSnapshot([metadata omitted])"
}
internal data class LocalCatalogRead(val account: Long,val catalog: NativeCatalog,
    val retained: Set<String>,val counts: Map<String,Int>) {
    override fun toString()="LocalCatalogRead([metadata omitted])"
}
internal data class LocalCollectionPlayback(val account: Long,val key: String,val rows: List<PublishedAudio>) {
    override fun toString()="LocalCollectionPlayback(count="+rows.size+")"
}

/** Small durable descriptors in indexed meta keys; physical membership stays in file_sources. IO only. */
internal class LocalCatalogSnapshots(private val store: AudioExportStore) {
    private val json=Json{encodeDefaults=true;ignoreUnknownKeys=true}
    private fun prefix(account: Long): String {
        musicCheck(account>0,MusicError.STORAGE)
        return "local_catalog:$account:"
    }
    private fun marker(account: Long,key: String)=prefix(account)+key
    private fun decode(account: Long,name: String,payload: String): LocalCatalogSnapshot {
        val row=try{json.decodeFromString<LocalCatalogSnapshot>(payload)}
            catch(_: Exception){throw MusicFailure(MusicError.STORAGE)}
        musicCheck(row.account==account&&row.id>0&&row.owner!=0L&&row.count>=0&&
            name==marker(account,row.key),MusicError.STORAGE)
        return row
    }
    fun remember(account: Long,list: NativePlaylist,page: NativePage?=null) {
        val name=marker(account,list.key)
        store.updateCheckpoint(name){previousPayload->
            val previous=previousPayload?.let{decode(account,name,it)}
            val order=if(page!=null&&page.tracks.size==page.count)
                ExportLayout.playlist(page,list,account=account).map{it.key}
                else previous?.order.orEmpty()
            json.encodeToString(LocalCatalogSnapshot(account,list.owner,list.id,list.title,list.count,list.kind,order))
        }
    }
    fun read(account: Long,remote: NativeCatalog?=null): LocalCatalogRead {
        val counts=store.sourceCounts(account,"catalog")
        val present=remote?.lists.orEmpty().mapTo(hashSetOf()){it.key}
        val prefix=prefix(account)
        val retained=store.checkpoints(prefix).filter{(name,_)->
            val key=name.removePrefix(prefix);(counts[key]?:0)>0&&key !in present
        }.map{(name,payload)->decode(account,name,payload)}
        val lists=remote?.lists.orEmpty()+retained.map{it.playlist()}
        val catalog=if(remote!=null)remote.copy(lists=lists,count=remote.count+retained.size)
            else NativeCatalog(lists,lists.size,complete=false,baseComplete=false)
        return LocalCatalogRead(account,catalog,retained.mapTo(linkedSetOf()){it.key},counts)
    }
    suspend fun available(account: Long,remote: NativeCatalog?=null,
        presence: suspend(List<PublishedAudio>)->Map<String,SavedAvailability>): LocalCatalogRead {
        val candidate=read(account,remote)
        if(candidate.retained.isEmpty())return candidate
        val rows=candidate.retained.associateWith{store.sourceFiles(ExportSource(account,"catalog",it))}
        val hints=presence(rows.values.flatten().distinctBy{it.uri})
        fun playable(row: PublishedAudio)=hints[row.uri] !in setOf(SavedAvailability.MISSING,SavedAvailability.CHANGED)
        val counts=candidate.counts.toMutableMap()
        val usable=rows.filter{(key,files)->
            val count=files.count(::playable);counts[key]=count;count>0
        }.keys
        val removed=candidate.retained-usable
        return candidate.copy(catalog=candidate.catalog.copy(
            lists=candidate.catalog.lists.filter{it.key !in removed},
            count=(candidate.catalog.count-removed.size).coerceAtLeast(0)),
            retained=usable,counts=counts)
    }
    suspend fun availablePlayback(account: Long,key: String,
        presence: suspend(List<PublishedAudio>)->Map<String,SavedAvailability>): LocalCollectionPlayback? {
        val local=playback(account,key)?:return null
        val hints=presence(local.rows.distinctBy{it.uri})
        return local.copy(rows=local.rows.filter{hints[it.uri] !in setOf(SavedAvailability.MISSING,SavedAvailability.CHANGED)})
    }
    fun playback(account: Long,key: String): LocalCollectionPlayback? {
        val name=marker(account,key)
        val descriptor=store.checkpoint(name)?.let{decode(account,name,it)}?:return null
        val source=ExportSource(account,"catalog",key)
        val rows=store.sourceFiles(source)
        musicCheck(rows.all{it.source==source},MusicError.STORAGE)
        if(rows.isEmpty())return null
        val byKey=rows.associateBy{it.taskKey}
        val known=descriptor.order.toHashSet()
        val ordered=descriptor.order.mapNotNull{byKey[it]}+
            rows.filter{it.taskKey !in known}
        return LocalCollectionPlayback(account,key,ordered)
    }
}
