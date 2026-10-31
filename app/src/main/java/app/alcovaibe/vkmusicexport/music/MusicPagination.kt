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
    suspend fun catalog(fetch: suspend(Int)->NativeCatalog,progress:(Int,Int)->Unit={_,_->}): NativeCatalog {
        val lists=linkedMapOf<String,NativePlaylist>();var offset=0;var expected: Int?=null
        val pages=hashSetOf<List<String>>()
        do {
            currentCoroutineContext().ensureActive();val page=fetch(offset)
            musicCheck(expected==null||page.count==expected,MusicError.LIBRARY_CHANGED);expected=page.count
            if(page.lists.isEmpty()||!pages.add(page.lists.map{it.key}))break
            musicCheck(offset+page.lists.size<=page.count,MusicError.LIBRARY_CHANGED)
            page.lists.forEach{lists.putIfAbsent(it.key,it)};offset+=page.lists.size
            musicCheck(offset<=1_000_000)
            progress(lists.size,page.count)
        }while(offset<expected!!)
        return NativeCatalog(lists.values.toList(),expected?:0,offset==(expected?:0))
    }
}
