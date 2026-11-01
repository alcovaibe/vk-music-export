package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Offset advances by returned positions, including duplicate tracks; never guess a complete list. */
internal object MusicPagination {
    suspend fun tracks(fetch: suspend(Int)->NativePage,progress:(Int,Int)->Unit={_,_->}): NativePage {
        val tracks=mutableListOf<NativeTrack>();val pages=hashSetOf<List<String>>()
        var expected: Int?=null
        do {
            currentCoroutineContext().ensureActive()
            val page=fetch(tracks.size)
            musicCheck(expected==null||page.count==expected,MusicError.LIBRARY_CHANGED);expected=page.count
            musicCheck(tracks.size+page.tracks.size<=page.count,MusicError.LIBRARY_CHANGED)
            if(page.tracks.isEmpty()){musicCheck(tracks.size==page.count,MusicError.INCOMPLETE);break}
            musicCheck(pages.add(page.tracks.map{it.id}),MusicError.INCOMPLETE)
            tracks.addAll(page.tracks);progress(tracks.size,page.count)
        }while(tracks.size<expected!!)
        return NativePage(tracks,expected?:0)
    }
    suspend fun catalog(fetch: suspend(Int)->NativeCatalog,progress:(Int,Int)->Unit={_,_->})=
        catalogCursor({offset,_->fetch(offset)},progress)
    suspend fun catalogCursor(fetch: suspend(Int,String?)->NativeCatalog,progress:(Int,Int)->Unit={_,_->},partial:(NativeCatalog)->Unit={}): NativeCatalog {
        val lists=linkedMapOf<String,NativePlaylist>();var offset=0;var expected: Int?=null
        val pages=hashSetOf<List<String>>();val cursors=hashSetOf<String>();var cursor: String?=null;var complete=false;var attempts=0
        do {
            currentCoroutineContext().ensureActive();if(++attempts>10000)break
            val page=fetch(offset,cursor)
            musicCheck(expected==null||page.count==expected,MusicError.LIBRARY_CHANGED);expected=page.count
            if(page.lists.isEmpty()) {
                val next=page.nextFrom
                if(next!=null&&cursors.add(next)){cursor=next;continue}
                if(page.nextOffset!=null) {
                    musicCheck(page.nextOffset>offset&&page.nextOffset<=page.count,MusicError.LIBRARY_CHANGED)
                    offset=page.nextOffset
                    if(offset==page.count){complete=true;break}
                    continue
                }
                complete=next==null&&offset==page.count;break
            }
            if(!pages.add(page.lists.map{it.key}))break
            musicCheck(offset+page.lists.size<=page.count,MusicError.LIBRARY_CHANGED)
            page.lists.forEach{lists[it.key]=lists[it.key]?.reconciled(it)?:it}
            val advanced=page.nextOffset?:offset+page.lists.size
            musicCheck(advanced>offset&&advanced<=page.count,MusicError.LIBRARY_CHANGED);offset=advanced
            musicCheck(offset<=1_000_000);progress(lists.size,page.count);partial(NativeCatalog(lists.values.toList(),page.count,false))
            val next=page.nextFrom
            if(next!=null) {if(!cursors.add(next))break;cursor=next}
            else if(page.cursorBased||cursor!=null) {complete=offset==page.count;break}
            else if(offset==page.count){complete=true;break}
        }while(true)
        return NativeCatalog(lists.values.toList(),expected?:0,complete)
    }
}
