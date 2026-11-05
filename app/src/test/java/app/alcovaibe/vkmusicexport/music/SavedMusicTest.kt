package app.alcovaibe.vkmusicexport.music

import org.junit.Assert.*
import org.junit.Test

class SavedMusicTest {
    private val song=NativeTrack("12_7","Zulu","CI",180)
    private fun row(title: String,order: Long)=SavedLibraryEntry(PublishedAudio("key-$order",song.copy(title=title),"content://media/external_primary/audio/media/$order","song.mp3",ExportLayout.ROOT+"Моя музыка/","mp3","audio/mpeg",180000,100,"a".repeat(64)),downloadedOrder=order)
    @Test fun newestIsPhysicalPublicationOrderAndTitleUsesDigitsLatinThenCyrillic() {
        val rows=listOf(row("Я",1),row("apple",2),row("10",3),row("Б",4),row("2",5),row("100",6),row("Alpha",7))
        assertEquals(listOf(7L,6L,5L,4L,3L,2L,1L),SavedMusicSorting.sorted(rows,SavedMusicSort.NEWEST).map{it.downloadedOrder})
        assertEquals(listOf("2","10","100","Alpha","apple","Б","Я"),SavedMusicSorting.sorted(rows,SavedMusicSort.TITLE).map{it.file.track.title})
    }
    @Test fun titleComparisonIgnoresCaseAndHandlesLongNumbersWithoutOverflow() {
        assertEquals(0,SavedMusicSorting.titles.compare(" Ёж", "еж"))
        assertTrue(SavedMusicSorting.titles.compare("2".repeat(100),"1".repeat(101))<0)
        assertTrue(SavedMusicSorting.titles.compare("Song 2","song 10")<0)
        assertEquals(0,SavedMusicSorting.titles.compare("002","2"))
    }
    @Test fun uncheckedLocalRecordsArePlayableButMissingAndChangedAreNot() {
        val unknown=row("Local",1)
        assertFalse(unknown.present);assertTrue(unknown.playable)
        assertTrue(PlaybackLists.saved(listOf(unknown)).single().offline)
        assertTrue(PlaybackLists.saved(listOf(unknown.copy(availability=SavedAvailability.MISSING))).isEmpty())
        assertTrue(PlaybackLists.saved(listOf(unknown.copy(availability=SavedAvailability.CHANGED))).isEmpty())
    }
    @Test fun partialOwnPagePlaysClickedOccurrenceInVisibleOrderAndCarriesDownloadTask() {
        val page=NativePage(listOf(song,song.copy(id="12_8",title="Alpha"),song),50)
        val state=NativeMusicState(busy=true,probe=NativeProbe(12,page),page=page,readSessionId="session")
        val queue=PlaybackLists.library(state,MusicLibrarySort.TITLE)
        assertEquals(listOf("Alpha","Zulu","Zulu"),queue.map{it.track.title})
        val chosen=PlaybackLists.select(queue,2)!!
        assertEquals(2,chosen.start);assertEquals(3,chosen.entries[2].download!!.position)
        assertEquals(2,chosen.entries[2].download!!.occurrence)
        assertEquals(ExportLayout.library(page,false,12)[2],chosen.entries[2].download)
        assertTrue(chosen.entries.all{it.readSessionId=="session"})
    }
    @Test fun pagingDoesNotMutateTheAlreadySelectedQueueAndStalePagesCannotStart() {
        val prefix=NativePage(listOf(song,song),20)
        val state=NativeMusicState(probe=NativeProbe(12,prefix),page=prefix,readSessionId="session")
        val frozen=PlaybackLists.select(PlaybackLists.library(state,MusicLibrarySort.DEFAULT),1)!!
        val later=state.copy(page=NativePage(List(20){song},20))
        assertEquals(20,PlaybackLists.library(later,MusicLibrarySort.DEFAULT).size)
        assertEquals(2,frozen.entries.size);assertEquals(1,frozen.start)
        assertTrue(PlaybackLists.library(state.copy(libraryStale=true),MusicLibrarySort.DEFAULT).isEmpty())
        assertTrue(PlaybackLists.library(state.copy(readSessionId=null),MusicLibrarySort.DEFAULT).isEmpty())
    }
    @Test fun partialFriendsSearchPreservesOriginalPositionsViewerAndRepeatedOccurrences() {
        val friend=MusicFriend(34,"Friend","CI")
        val state=FriendMusicState(busy=true,user=friend,viewerAccount=12,readSessionId="session",musicQuery="Zulu",
            tracks=NativePage(listOf(song,song.copy(id="12_8",title="other"),song),100))
        val queue=PlaybackLists.friends(state)
        assertEquals(2,queue.size);assertEquals(listOf(1,3),queue.map{it.download!!.position})
        assertEquals(listOf(1,2),queue.map{it.download!!.occurrence})
        assertEquals(34L,ExportLayout.friendId(queue.last().download!!.source!!))
        assertTrue(queue.all{it.account==12L&&it.readSessionId=="session"})
        assertEquals(1,PlaybackLists.select(queue,1)!!.start)
    }
}
