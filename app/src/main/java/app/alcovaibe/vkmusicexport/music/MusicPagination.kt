package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Fixed categories only: cursor strings and playlist identities must never enter diagnostics. */
internal enum class CatalogStopReason {
    COMPLETE, EARLY_END, EMPTY_PAGE, REPEATED_PAGE, REPEATED_CURSOR, SKIPPED_POSITIONS, PAGE_LIMIT
}
internal data class CatalogPaginationStats(
    val pages: Int, val positions: Int, val received: Int, val unique: Int,
    val expected: Int, val reason: CatalogStopReason, val retries: Int
)

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

    /** Retry only bounded transient failures; never retry authentication, rate limits or invalid payloads. */
    suspend fun <T> retryTransientPage(onRetry: ()->Unit={},fetch:suspend()->T): T {
        var attempt=0
        while(true) {
            currentCoroutineContext().ensureActive()
            try {return fetch()}
            catch(e: MusicFailure) {
                val transient=e.code in setOf(MusicError.NETWORK,MusicError.TIMEOUT) ||
                    (e.code==MusicError.HTTP && e.httpStatus in setOf(500,502,503,504))
                if(!transient||attempt>=2)throw e
                attempt++;onRetry()
                delay(attempt*500L)
            }
        }
    }

    suspend fun catalogCursor(fetch: suspend(Int,String?)->NativeCatalog,
        progress:(Int,Int)->Unit={_,_->},partial:(NativeCatalog)->Unit={}): NativeCatalog {
        val lists=linkedMapOf<String,NativePlaylist>();var offset=0;var expected: Int?=null
        val pages=hashSetOf<List<String>>();val cursors=hashSetOf<String>()
        var cursor: String?=null;var complete=false;var requests=0;var received=0;var retries=0
        var skippedPositions=false;var reason=CatalogStopReason.EARLY_END

        do {
            currentCoroutineContext().ensureActive()
            if(requests>=10000){reason=CatalogStopReason.PAGE_LIMIT;break}
            requests++
            val page=retryTransientPage(onRetry={retries++}){fetch(offset,cursor)}
            musicCheck(expected==null||page.count==expected,MusicError.LIBRARY_CHANGED);expected=page.count
            received+=page.lists.size
            if(page.lists.isEmpty()) {
                val next=page.nextFrom
                if(next!=null) {
                    if(!cursors.add(next)){reason=CatalogStopReason.REPEATED_CURSOR;break}
                    cursor=next;continue
                }
                if(cursor!=null||page.cursorBased) {
                    complete=offset==page.count
                    reason=if(complete)CatalogStopReason.COMPLETE else CatalogStopReason.EMPTY_PAGE
                    break
                }
                if(page.nextOffset!=null) {
                    musicCheck(page.nextOffset>offset&&page.nextOffset<=page.count,MusicError.LIBRARY_CHANGED)
                    // An empty page that advances an offset cannot prove the missing positions were loaded.
                    skippedPositions=true;offset=page.nextOffset
                    if(offset==page.count){reason=CatalogStopReason.SKIPPED_POSITIONS;break}
                    continue
                }
                complete=offset==page.count
                reason=if(complete)CatalogStopReason.COMPLETE else CatalogStopReason.EMPTY_PAGE
                break
            }
            if(!pages.add(page.lists.map{it.key})) {
                page.lists.forEach{lists[it.key]=lists[it.key]?.reconciled(it)?:it}
                partial(NativeCatalog(lists.values.toList(),page.count,false))
                reason=CatalogStopReason.REPEATED_PAGE;break
            }
            musicCheck(offset+page.lists.size<=page.count,MusicError.LIBRARY_CHANGED)
            page.lists.forEach{lists[it.key]=lists[it.key]?.reconciled(it)?:it}
            val advanced=page.nextOffset?:offset+page.lists.size
            musicCheck(advanced>offset&&advanced<=page.count,MusicError.LIBRARY_CHANGED)
            if(advanced>offset+page.lists.size)skippedPositions=true
            offset=advanced
            progress(lists.size,page.count);partial(NativeCatalog(lists.values.toList(),page.count,false))
            val next=page.nextFrom
            if(next!=null) {
                if(!cursors.add(next)){reason=CatalogStopReason.REPEATED_CURSOR;break}
                cursor=next
            }else if(page.cursorBased||cursor!=null) {
                complete=offset==page.count
                reason=if(complete)CatalogStopReason.COMPLETE else CatalogStopReason.EARLY_END
                break
            }else if(offset==page.count) {
                complete=true;reason=CatalogStopReason.COMPLETE;break
            }
        }while(true)
        if(skippedPositions) {
            complete=false
            if(reason==CatalogStopReason.COMPLETE)reason=CatalogStopReason.SKIPPED_POSITIONS
        }
        val stats=CatalogPaginationStats(requests,offset,received,lists.size,expected?:0,reason,retries)
        return NativeCatalog(lists.values.toList(),expected?:0,complete,pagination=stats)
    }
}
