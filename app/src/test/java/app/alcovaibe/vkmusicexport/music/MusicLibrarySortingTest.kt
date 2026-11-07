package app.alcovaibe.vkmusicexport.music

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicLibrarySortingTest {
    private fun track(id: String,title: String,artist: String="Исполнитель",year: Int?=null)=
        NativeTrack(id,title,artist,180,releaseYear=year)
    private val vkOrder=listOf(
        track("1_1","Январь",year=2024),
        track("1_2","2 Apples",year=1999),
        track("1_3","Bravo",year=2005),
        track("1_4","Альфа",year=null),
        track("1_5","Apple",year=2005)
    )
    private fun ids(sort: MusicLibrarySort)=MusicLibrarySorting.tracks(vkOrder,sort).map{it.value.id}

    @Test fun defaultAndNewestPreserveVkPositions() {
        assertEquals(vkOrder.map{it.id},ids(MusicLibrarySort.DEFAULT))
        assertEquals(vkOrder.map{it.id},ids(MusicLibrarySort.NEWEST))
    }
    @Test fun oldestReversesVkPositions() {
        assertEquals(vkOrder.reversed().map{it.id},ids(MusicLibrarySort.OLDEST))
    }
    @Test fun titleUsesDigitsEnglishThenRussian() {
        assertEquals(listOf("1_2","1_5","1_3","1_4","1_1"),ids(MusicLibrarySort.TITLE))
    }
    @Test fun yearIsOldestFirstAndUnknownLast() {
        assertEquals(listOf("1_2","1_3","1_5","1_1","1_4"),ids(MusicLibrarySort.YEAR))
    }
    @Test fun performerGroupingUsesSameAlphabet() {
        val tracks=listOf(track("1_1","Z","Яков"),track("1_2","A","3 хита"),track("1_3","B","Zulu"),track("1_4","C","Алена"))
        assertEquals(listOf("1_2","1_3","1_4","1_1"),MusicLibrarySorting.tracks(tracks,MusicLibrarySort.ARTIST).map{it.value.id})
    }
    @Test fun collectionMenuOnlyOffersOrdersWithCollectionData() {
        assertEquals(listOf(MusicLibrarySort.DEFAULT,MusicLibrarySort.TITLE),MusicLibrarySorting.choices(null))
        val lists=listOf(
            NativePlaylist(1,2,"Бета",1,null,null,null,0),
            NativePlaylist(1,1,"Альфа",1,null,null,null,0))
        assertEquals(listOf(1L,2L),MusicLibrarySorting.collections(lists,MusicLibrarySort.TITLE).map{it.id})
    }
    @Test fun yearsAreNeverGuessedAndAdditionOrderIsOnlyOfferedForLibrary() {
        val unknown=listOf(track("1_8","Альбом 1999"))
        assertEquals(listOf(MusicLibrarySort.DEFAULT,MusicLibrarySort.ARTIST,MusicLibrarySort.TITLE),
            MusicLibrarySorting.choices(unknown))
        assertEquals(MusicLibrarySort.entries.toList(),MusicLibrarySorting.choices(vkOrder,additionOrder=true))
        assertEquals(listOf("1_8"),MusicLibrarySorting.tracks(unknown,MusicLibrarySort.YEAR).map{it.value.id})
    }
}
