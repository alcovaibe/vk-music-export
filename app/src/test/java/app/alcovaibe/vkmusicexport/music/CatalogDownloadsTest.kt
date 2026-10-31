package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CatalogDownloadsTest {
    private val list=NativePlaylist(12,1,"I AM",2,null,null,null,null,PlaylistKind.ALBUM)
    private val song=NativeTrack("12_1","Song","CI",2)
    private val page=NativePage(listOf(song,song),2)
    private val tasks=ExportLayout.playlist(page,list,account=12)
    private val rows=tasks.map{PublishedAudio(it.key,it.track,"content://media/external_primary/audio/media/1","CI.mp3",
        ExportLayout.relative(it),"mp3","audio/mpeg",2000,100,"abc",source=it.source,occurrence=it.occurrence)}
    @Test fun onlyCompleteMatchingCurrentSourceWithIntactOriginalsIsHidden()=runBlocking {
        val catalog=NativeCatalog(listOf(list),1)
        suspend fun hidden(saved: List<PublishedAudio>,intact: Boolean=true,current: NativePage=page)=
            CatalogDownloads.completed(catalog,12,saved,{current},{intact})
        assertEquals(setOf(list.key),hidden(rows))
        assertTrue(hidden(rows.take(1)).isEmpty())
        assertTrue(hidden(rows,false).isEmpty())
        assertTrue(CatalogDownloads.completed(catalog,12,rows,{page},{true},{_,_,_->false}).isEmpty())
        assertTrue(hidden(rows.map{it.copy(catalogueVerified=false)}).isEmpty())
        assertTrue(hidden(rows.map{it.copy(source=it.source!!.copy(account=13))}).isEmpty())
        assertTrue(hidden(rows,current=NativePage(listOf(song,song.copy(id="12_2")),2)).isEmpty())
        assertTrue(CatalogDownloads.completed(NativeCatalog(emptyList(),0),12,rows,{error("deleted source must not be fetched")},{true}).isEmpty())
    }
    @Test fun serverFailureKeepsCollectionVisibleAndCancellationPropagates()=runBlocking {
        val catalog=NativeCatalog(listOf(list),1)
        assertTrue(CatalogDownloads.completed(catalog,12,rows,{throw MusicFailure(MusicError.NETWORK)},{true}).isEmpty())
        var cancelled=false
        try{CatalogDownloads.completed(catalog,12,rows,{throw CancellationException()},{true})}
        catch(_: CancellationException){cancelled=true}
        assertTrue(cancelled)
    }
}
