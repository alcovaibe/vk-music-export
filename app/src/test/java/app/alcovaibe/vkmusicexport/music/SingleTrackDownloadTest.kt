package app.alcovaibe.vkmusicexport.music

import org.junit.Assert.*
import org.junit.Test

class SingleTrackDownloadTest {
    private val track=NativeTrack("12_34","Song","Artist",180)
    private fun fails(code: MusicError,block:()->Unit) {
        try{block();fail("Expected $code")}catch(e: MusicFailure){assertEquals(code,e.code)}
    }
    @Test fun oneTaskKeepsBulkIdentityWithoutBuildingAnEntireQueueAtEveryLibrarySize() {
        for(size in listOf(100,4500,20000)) {
            val tracks=List(size){if(it==0||it==size-1)track else track.copy(id="12_${100+it}")}
            val page=NativePage(tracks,size)
            val single=ExportLayout.single(page,track,size,12)
            assertEquals(ExportLayout.library(page,albums=false,account=12).last(),single)
            assertEquals(2,single.occurrence);assertEquals(size,single.position)
        }
    }
    @Test fun playlistAndAlbumKeepSnapshotPositionOccurrenceFolderAndStableKey() {
        val page=NativePage(listOf(track,track.copy(id="12_35"),track),3)
        for(kind in listOf(PlaylistKind.ALBUM,PlaylistKind.PLAYLIST,PlaylistKind.UNKNOWN)) {
            val list=NativePlaylist(-56,78,"Collection",3,null,null,null,null,kind)
            assertEquals(ExportLayout.playlist(page,list,kind,12).last(),
                ExportLayout.single(page,track,3,12,list,kind))
        }
    }
    @Test fun aLoadedPrefixIsEnoughAndMissingRowsCannotBeInvented() {
        val page=NativePage(listOf(track,track),20000)
        assertEquals(2,ExportLayout.single(page,track,2,12).occurrence)
        fails(MusicError.LIBRARY_CHANGED){ExportLayout.single(page,track,3,12)}
    }
    @Test fun wrongPositionDifferentRecordAndChangedMetadataAreRejected() {
        val page=NativePage(listOf(track),1)
        for(position in listOf(-1,0,2))fails(MusicError.LIBRARY_CHANGED){ExportLayout.single(page,track,position,12)}
        fails(MusicError.LIBRARY_CHANGED){ExportLayout.single(page,track.copy(id="12_35"),1,12)}
        fails(MusicError.LIBRARY_CHANGED){ExportLayout.single(page,track.copy(title="Changed"),1,12)}
        fails(MusicError.LIBRARY_CHANGED){ExportLayout.single(null,track,1,12)}
        fails(MusicError.LIBRARY_CHANGED){ExportLayout.single(NativePage(listOf(track),0),track,1,12)}
    }
    @Test fun invalidOwnerAndUnusableRecordsNeverProduceTasks() {
        fails(MusicError.ACCOUNT_CHANGED){ExportLayout.single(NativePage(listOf(track),1),track,1,0)}
        for(value in listOf(track.copy(restricted=true),track.copy(duration=0),track.copy(metadataError="duration")))
            fails(MusicError.UNAVAILABLE){ExportLayout.single(NativePage(listOf(value),1),value,1,12)}
    }
}
