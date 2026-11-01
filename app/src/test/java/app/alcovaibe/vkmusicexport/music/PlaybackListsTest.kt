package app.alcovaibe.vkmusicexport.music

import org.junit.Assert.*
import org.junit.Test

class PlaybackListsTest {
    private val song=NativeTrack("12_7","Название","Исполнитель",180)
    private fun saved(uri: String="content://media/external_primary/audio/media/1")=PublishedAudio("key",song,uri,"song.mp3",ExportLayout.ROOT+"Моя музыка/","mp3","audio/mpeg",180000,100,"a".repeat(64))
    @Test fun filteringUnavailableItemsKeepsTheExactSelectedOccurrenceAndOriginalOrder() {
        val first=PlaybackEntry(song,"Друг",12)
        val missing=PlaybackEntry(song.copy(id="12_8",metadataError="title"),"Друг",12)
        val last=first.copy(sourceLabel="Повтор той же записи")
        val selected=PlaybackLists.select(listOf(first,missing,last),2)!!
        assertEquals(listOf(first,last),selected.entries);assertEquals(1,selected.start)
        assertNull(PlaybackLists.select(listOf(first,missing,last),1))
    }
    @Test fun badSelectionNeverStartsAnotherSongInstead() {
        assertNull(PlaybackLists.select(emptyList(),0));assertNull(PlaybackLists.select(listOf(PlaybackEntry(song,"Список",12)),-1))
        assertNull(PlaybackLists.select(listOf(PlaybackEntry(song.copy(restricted=true),"Список",12)),0))
    }
    @Test fun offlineQueueUsesOnlyPresentFilesWithoutVkAccessAndDeduplicatesUri() {
        val file=saved();val other=saved("content://media/external_primary/audio/media/2")
        val rows=PlaybackLists.saved(listOf(SavedLibraryEntry(file,true),SavedLibraryEntry(file,true),SavedLibraryEntry(other,false)))
        assertEquals(1,rows.size);assertTrue(rows.single().offline);assertNull(rows.single().account)
        assertEquals(file.uri,rows.single().saved?.uri);assertNull(rows.single().download)
    }
    @Test fun verifiedLocalCopyCanPlayEvenWhenVkNowMarksItsOriginalRestricted() {
        val entry=PlaybackEntry(song.copy(restricted=true),"Сохранённая музыка",saved=saved())
        assertNotNull(PlaybackLists.select(listOf(entry),0))
    }
    @Test fun friendQueuePreservesViewerSourceAndAllRepeatedPositions() {
        val friend=MusicFriend(34,"Алина","Смирнова","@friend")
        val state=FriendMusicState(user=friend,viewerAccount=12,tracks=NativePage(listOf(song,song),2),tracksComplete=true)
        val rows=PlaybackLists.friends(state)
        assertEquals(2,rows.size);assertTrue(rows.all{it.account==12L&&!it.offline&&it.sourceLabel=="Музыка друга · @friend"})
        assertEquals(listOf(1,2),rows.map{it.download?.position})
        assertNotEquals(rows[0].download?.key,rows[1].download?.key)
        assertTrue(PlaybackLists.friends(state.copy(tracksComplete=false)).isEmpty())
        assertTrue(PlaybackLists.friends(state.copy(viewerAccount=null)).isEmpty())
    }
    @Test fun stringRepresentationsDoNotExposeUserMetadataOrUris() {
        val entry=PlaybackEntry(song,"Частное имя",saved=saved())
        assertFalse(entry.toString().contains("content:"));assertFalse(entry.toString().contains("Частное"))
        assertFalse(PlaybackState(entries=listOf(entry)).toString().contains(song.title))
    }
}
