package app.alcovaibe.vkmusicexport.music

/** Short-lived metadata only. No media URLs, credentials, receipts or file-integrity verdicts. */
internal class ExportReadCache(private val clock:()->Long,
    private val ttlMs: Long=60_000,private val maxTracks: Int=40_000,private val maxPages: Int=64) {
    data class Lease(val owner: Long,val session: String,val epoch: Long)
    private data class Stamp(val owner: Long,val id: Long,val title: String,val count: Int,
        val kind: PlaylistKind,val originalOwner: Long?,val originalId: Long?,val accessKey: String?,
        val following: Boolean?,val deleted: Boolean,val conflict: Boolean)
    private data class Page(val value: NativePage,val stamp: Stamp?,val at: Long)
    private data class Catalog(val value: NativeCatalog,val at: Long)
    private var epoch=0L
    private var scope: Lease?=null
    private var catalog: Catalog?=null
    private var positions=0
    private val pages=java.util.LinkedHashMap<String,Page>(16,.75f,true)
    init{require(ttlMs>0&&maxTracks>0&&maxPages>0)}
    private fun stamp(list: NativePlaylist)=Stamp(list.owner,list.id,list.title,list.count,list.kind,
        list.originalOwner,list.originalId,list.accessKey,list.following,list.deleted,list.typeConflict)
    private fun current(lease: Lease?)=lease!=null&&lease==scope
    private fun fresh(at: Long): Boolean {val age=clock()-at;return age>=0&&age<ttlMs}
    @Synchronized fun clear(){epoch++;scope=null;catalog=null;pages.clear();positions=0}
    @Synchronized fun begin(owner: Long,session: String): Lease {
        require(owner>0&&session.isNotEmpty());clear()
        return Lease(owner,session,epoch).also{scope=it}
    }
    @Synchronized fun lease(owner: Long,session: String)=scope?.takeIf{it.owner==owner&&it.session==session}
    @Synchronized fun invalidateCatalog(lease: Lease?) {
        if(!current(lease))return
        catalog=null
        val iterator=pages.iterator()
        while(iterator.hasNext()) {
            val entry=iterator.next()
            if(entry.value.stamp!=null){positions-=entry.value.value.tracks.size;iterator.remove()}
        }
    }
    @Synchronized fun rememberCatalog(lease: Lease?,value: NativeCatalog) {
        if(!current(lease))return
        // Missing entries in a partial catalog are not evidence of deletion.
        val known=value.lists.associateBy{it.key}
        val iterator=pages.iterator()
        while(iterator.hasNext()) {
            val entry=iterator.next();val before=entry.value.stamp?:continue
            val now=known[entry.key]
            if((now==null&&value.complete)||(now!=null&&before!=stamp(now))) {
                positions-=entry.value.value.tracks.size;iterator.remove()
            }
        }
        catalog=if(value.lists.size<=20_000)Catalog(value.copy(lists=value.lists.toList()),clock())else null
    }
    @Synchronized fun catalog(lease: Lease?,kind: PlaylistKind?): NativeCatalog? {
        if(!current(lease))return null
        return catalog?.takeIf{fresh(it.at)&&it.value.completeFor(kind)}?.value
    }
    @Synchronized fun rememberLibrary(lease: Lease?,page: NativePage)=remember(lease,"library",page,null)
    @Synchronized fun rememberPlaylist(lease: Lease?,list: NativePlaylist,page: NativePage) {
        if(page.count!=list.count){
            if(current(lease)) {catalog=null;remove(list.key)}
            return
        }
        remember(lease,list.key,page,stamp(list))
    }
    @Synchronized fun library(lease: Lease?)=read(lease,"library",null)
    @Synchronized fun playlist(lease: Lease?,list: NativePlaylist)=read(lease,list.key,stamp(list))
    private fun remove(key: String){pages.remove(key)?.let{positions-=it.value.tracks.size}}
    private fun remember(lease: Lease?,key: String,value: NativePage,stamp: Stamp?) {
        if(!current(lease))return
        remove(key)
        if(value.count<0||value.tracks.size!=value.count||value.count>maxTracks)return
        pages[key]=Page(value.copy(tracks=value.tracks.toList()),stamp,clock());positions+=value.tracks.size
        val iterator=pages.iterator()
        while((positions>maxTracks||pages.size>maxPages)&&iterator.hasNext()) {
            positions-=iterator.next().value.value.tracks.size;iterator.remove()
        }
    }
    private fun read(lease: Lease?,key: String,stamp: Stamp?): NativePage? {
        if(!current(lease))return null
        val entry=pages[key]?:return null
        if(entry.stamp!=stamp||!fresh(entry.at)){remove(key);return null}
        return entry.value
    }
}
