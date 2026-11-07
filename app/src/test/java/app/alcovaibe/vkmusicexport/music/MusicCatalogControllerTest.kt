package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MusicCatalogControllerTest {
    private val access=MusicAccess("a".repeat(64),"CI",accountId=12)
    private val playlist=NativePlaylist(12,100,"Test List",1,null,null,null,null,PlaylistKind.PLAYLIST)
    private val track=NativeTrack("12_100","Title","Artist",180)

    private inner class Fixture(scope: TestScope) {
        var current=access
        val state=MutableStateFlow(NativeMusicState(initialized=true,configured=true,
            probe=NativeProbe(12,NativePage(listOf(track),1)),page=NativePage(listOf(track),1),
            catalog=NativeCatalog(listOf(playlist),1),readSessionId=access.readSessionId))
        var load: suspend()->NativePage={NativePage(listOf(track),1)}
        var local: LocalCollectionPlayback?=null
        val remembered=mutableListOf<PlaylistObservation>()
        var accessCalls=0;var playlistCalls=0;var generation=0
        var work: Job?=null
        private val api=object: MusicApi {
            override suspend fun library(access: MusicAccess)=NativeProbe(12,NativePage(emptyList(),0))
            override suspend fun catalog(access: MusicAccess,owner: Long)=NativeCatalog(emptyList(),0)
            override suspend fun playlist(access: MusicAccess,list: NativePlaylist): NativePage {
                playlistCalls++;return load()
            }
            override suspend fun media(access: MusicAccess,track: NativeTrack): String=error("No stream request")
        }
        val controller=MusicCatalogController(state,api,ExportReadCache({1000L}),
            access={accessCalls++;current},checked={current},
            operation={stage,block->
                val g=++generation
                state.update{it.copy(busy=true,stage=stage,error=null)}
                work=scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
                    try{block(g)}
                    catch(e: CancellationException){throw e}
                    catch(e: MusicFailure){if(g==generation)state.update{it.copy(error=e.code)}}
                    finally{if(g==generation)state.update{it.copy(busy=false,preparing=null)}}
                }
            },
            active={g->currentCoroutineContext().ensureActive();if(g!=generation)throw CancellationException()},
            isCurrent={it==generation},localPlayback={_,_->local},remember={_,read->remembered.add(read);Unit})
        fun invalidate(){generation++;work?.cancel();state.update{it.copy(busy=false,page=null,localPlayback=null)}}
    }
    @Test fun selectSectionUpdatesSectionAndResetsPlaylist()=runTest {
        val f=Fixture(this)
        f.controller.section(CatalogSection.PLAYLISTS)
        assertEquals(CatalogSection.PLAYLISTS,f.state.value.catalogSection);assertNull(f.state.value.selected)
        f.controller.section(CatalogSection.LIBRARY)
        assertEquals(CatalogSection.LIBRARY,f.state.value.catalogSection);assertEquals(listOf(track),f.state.value.page?.tracks)
        f.state.update{it.copy(busy=true)}
        f.controller.section(CatalogSection.ALBUMS)
        assertEquals(CatalogSection.LIBRARY,f.state.value.catalogSection)
    }
    @Test fun selectPlaylistDeselectsWhenNullPassed()=runTest {
        val f=Fixture(this);f.state.update{it.copy(selected=playlist,catalogSection=CatalogSection.PLAYLISTS,page=null)}
        f.controller.select(null)
        assertNull(f.state.value.selected);assertEquals(CatalogSection.LIBRARY,f.state.value.catalogSection)
        assertEquals(listOf(track),f.state.value.page?.tracks)
    }
    @Test fun verifiedRenewalPublishesTheObservedPageAndRemembersOnlyCheckedMetadata()=runTest {
        val f=Fixture(this)
        f.load={f.current=MusicAccess("b".repeat(64),"CI",accountId=12,readSessionId=access.readSessionId);NativePage(listOf(track),1)}
        f.controller.select(playlist);runCurrent()
        assertNull(f.state.value.error);assertEquals(listOf(track),f.state.value.page?.tracks)
        assertEquals(1,f.remembered.size);assertEquals(playlist.key,f.remembered.single().list.key)
        assertEquals(1,f.playlistCalls)
    }
    @Test fun independentLoginOrChangedAccountCannotPublishOrPersistTheOldPage()=runTest {
        for(next in listOf(MusicAccess("b".repeat(64),"CI",accountId=12),
            MusicAccess("b".repeat(64),"CI",accountId=13,readSessionId=access.readSessionId))) {
            val f=Fixture(this)
            f.load={f.current=next;NativePage(listOf(track),1)}
            f.controller.select(playlist);runCurrent()
            assertEquals(MusicError.ACCESS,f.state.value.error)
            assertNull(f.state.value.page);assertNull(f.state.value.localPlayback);assertTrue(f.remembered.isEmpty())
        }
    }
    @Test fun cancellationRejectsANonCooperativeLatePageWithoutTouchingTheNewState()=runTest {
        val f=Fixture(this);val release=CompletableDeferred<Unit>()
        f.load={withContext(NonCancellable){release.await()};NativePage(listOf(track),1)}
        f.controller.select(playlist);runCurrent();assertTrue(f.state.value.busy)
        f.invalidate();val after=f.state.value
        release.complete(Unit);runCurrent()
        assertEquals(after,f.state.value);assertTrue(f.remembered.isEmpty())
    }
    @Test fun localSnapshotNeverReadsAccessOrTurnsRepeatedFilesIntoOnlineAuthority()=runTest {
        val f=Fixture(this);val source=ExportSource(12,"catalog",playlist.key)
        val task=ExportLayout.task(track,listOf("CI"),1,source)
        val row=PublishedAudio(task.key,track,"content://media/external/audio/media/11","CI.mp3",
            ExportLayout.ROOT+"CI/","mp3","audio/mpeg",180000,123,"a".repeat(64),source=source)
        f.local=LocalCollectionPlayback(12,playlist.key,listOf(row,row.copy(taskKey="second",occurrence=2)))
        f.state.update{it.copy(probe=null,readSessionId=null,localCatalogAccount=12,localCatalogKeys=setOf(playlist.key))}
        f.controller.select(playlist);runCurrent()
        assertEquals(0,f.accessCalls);assertEquals(0,f.playlistCalls);assertTrue(f.remembered.isEmpty())
        val entries=PlaybackLists.library(f.state.value,MusicLibrarySort.DEFAULT)
        assertEquals(2,entries.size);assertTrue(entries.all{it.offline&&it.account==null&&it.readSessionId==null&&it.download==null})
    }
}
