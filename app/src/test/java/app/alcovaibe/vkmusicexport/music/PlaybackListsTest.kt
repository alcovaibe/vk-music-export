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
        assertEquals(rows,PlaybackLists.friends(state.copy(tracksComplete=false,busy=true)))
        assertTrue(PlaybackLists.friends(state.copy(viewerAccount=null)).isEmpty())
    }
    @Test fun stringRepresentationsDoNotExposeUserMetadataOrUris() {
        val entry=PlaybackEntry(song,"Частное имя",saved=saved())
        assertFalse(entry.toString().contains("content:"));assertFalse(entry.toString().contains("Частное"))
        assertFalse(PlaybackState(entries=listOf(entry)).toString().contains(song.title))
    }

    private val artist=MusicArtist("artist-1","Исполнитель")
    private fun artistState()=MusicArtistsState(owner=12,selected=artist,readSessionId="read-1",
        details=MusicArtistDetails(artist,NativePage(listOf(song,song),2),emptyList()))

    @Test fun artistQueueUsesItsOwnSnapshotAndPreservesRepeatedOccurrences() {
        val rows=PlaybackLists.artist(artistState())
        assertEquals(2,rows.size)
        assertTrue(rows.all{it.account==12L&&it.readSessionId=="read-1"})
        val tasks=rows.map{it.download!!}
        assertEquals(listOf(1,2),tasks.map{it.position})
        assertEquals(listOf(1,2),tasks.map{it.occurrence})
        assertTrue(tasks.all{it.source==ExportSource(12,"artist",artist.id)&&it.folders==listOf("Исполнители",artist.name)})
        assertNotEquals(tasks[0].key,tasks[1].key)
        assertNotEquals(ExportLayout.single(NativePage(listOf(song),1),song,1,12).key,tasks[0].key)
    }

    @Test fun artistAlbumUsesTheSameDownloadIdentityAsTheCatalog() {
        val album=NativePlaylist(34,7,"Альбом",2,null,null,null,null,PlaylistKind.ALBUM)
        val page=NativePage(listOf(song,song),2)
        val state=artistState().copy(album=album,albumTracks=page)
        assertEquals(ExportLayout.playlist(page,album,account=12),PlaybackLists.artist(state).map{it.download})
        assertTrue(PlaybackLists.artist(state.copy(albumTracks=null)).isEmpty())
        val unknown=NativePlaylist(34,7,"Альбом",2,null,null,null,null,PlaylistKind.UNKNOWN)
        assertTrue(PlaybackLists.artist(state.copy(album=unknown)).all{it.download==null})
    }

    @Test fun artistQueueRequiresAccountSessionAndMatchingArtistDetails() {
        val state=artistState()
        assertTrue(PlaybackLists.artist(state.copy(owner=0)).isEmpty())
        assertTrue(PlaybackLists.artist(state.copy(readSessionId=null)).isEmpty())
        assertTrue(PlaybackLists.artist(state.copy(selected=artist.copy(id="other"))).isEmpty())
        val restricted=state.copy(details=state.details!!.copy(tracks=NativePage(listOf(song.copy(restricted=true),song),2)))
        assertNull(PlaybackLists.select(PlaybackLists.artist(restricted),0))
        assertEquals(2,PlaybackLists.select(PlaybackLists.artist(restricted),1)!!.entries.single().download!!.position)
    }

    @Test fun localCollectionPreservesRepeatedOccurrencesWithoutSessionOrOnlineDownloadAuthority() {
        val list=NativePlaylist(12,8,"Local collection",2,null,null,null,null,PlaylistKind.ALBUM)
        val first=saved().copy(taskKey="first",source=ExportSource(12,"catalog",list.key))
        val second=first.copy(taskKey="second",occurrence=2)
        val files=listOf(first,second)
        val state=NativeMusicState(probe=NativeProbe(12,NativePage(emptyList(),0)),selected=list,
            page=NativePage(files.map{it.track},2),localCatalogKeys=setOf(list.key),localCatalogAccount=12,
            localPlayback=LocalCollectionPlayback(12,list.key,files))
        val rows=PlaybackLists.library(state,MusicLibrarySort.DEFAULT)
        assertEquals(2,rows.size);assertEquals(files,rows.map{it.saved})
        assertTrue(rows.all{it.offline&&it.account==null&&it.readSessionId==null&&it.download==null})
        assertEquals(1,PlaybackLists.select(rows,1)!!.start)
        assertEquals(2,PlaybackLists.select(rows,1)!!.entries.size)
    }
    @Test fun wrongAccountSourceOrPageCannotTurnLocalMetadataIntoAnOnlineQueue() {
        val list=NativePlaylist(12,8,"Local collection",1,null,null,null,null,PlaylistKind.ALBUM)
        val file=saved().copy(source=ExportSource(12,"catalog",list.key))
        val state=NativeMusicState(probe=NativeProbe(12,NativePage(emptyList(),0)),selected=list,
            page=NativePage(listOf(file.track),1),readSessionId="new-independent-session",
            localCatalogKeys=setOf(list.key),localCatalogAccount=12,localPlayback=LocalCollectionPlayback(12,list.key,listOf(file)))
        val original=state.localPlayback!!
        val invalid=listOf(original.copy(account=13),original.copy(key="playlist:12:9"),
            original.copy(rows=listOf(file.copy(source=ExportSource(13,"catalog",list.key)))),
            original.copy(rows=listOf(file.copy(track=song.copy(id="12_999")))))
        for(local in invalid)
            assertTrue(PlaybackLists.library(state.copy(localPlayback=local),MusicLibrarySort.DEFAULT).isEmpty())
    }
}
