package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MusicArtistsControllerTest {
    private val access=MusicAccess("a".repeat(64),"CI",accountId=12)
    private val artist=MusicArtist("1","Исполнитель")
    private inner class Api: MusicApi {
        var load: suspend()->List<MusicArtist> = {listOf(artist)}
        override suspend fun followedArtists(access: MusicAccess,owner: Long,progress:(List<MusicArtist>,Int)->Unit)=load()
        override suspend fun library(access: MusicAccess)=error("Not a library read")
        override suspend fun catalog(access: MusicAccess,owner: Long): NativeCatalog=error("Not a catalog read")
        override suspend fun playlist(access: MusicAccess,list: NativePlaylist): NativePage=error("Not a playlist read")
        override suspend fun media(access: MusicAccess,track: NativeTrack)=error("Not a media transfer")
    }
    @Test fun pauseFencesLateReaderAndItsFinallyCannotClearNewBusyState()=runTest {
        val api=Api();val first=CompletableDeferred<Unit>();val second=CompletableDeferred<Unit>();var calls=0
        api.load={if(++calls==1)withContext(NonCancellable){first.await()}else second.await();listOf(artist)}
        val controller=MusicArtistsController(backgroundScope,api,{12},{access},{it},{false})
        controller.show();runCurrent();assertTrue(controller.state.value.busy)
        controller.pause();controller.show(true);runCurrent()
        first.complete(Unit);runCurrent()
        assertTrue(controller.state.value.busy);assertFalse(controller.state.value.loaded)
        second.complete(Unit);runCurrent()
        assertTrue(controller.state.value.loaded);assertFalse(controller.state.value.busy)
    }
    @Test fun anotherSubsystemCanFinishWhileArtistReadIsStalledAndCancellationIsLocal()=runTest {
        val api=Api();api.load={awaitCancellation()}
        val first=MusicArtistsController(backgroundScope,api,{12},{access},{it},{false})
        val other=MusicArtistsController(backgroundScope,Api(),{12},{access},{it},{false})
        first.show();other.show();runCurrent()
        assertTrue(first.state.value.busy);assertTrue(other.state.value.loaded)
        other.pause();assertTrue(first.state.value.busy)
        first.pause();runCurrent();assertFalse(first.state.value.busy)
    }
    @Test fun independentLoginRejectsLateSuccessWhileVerifiedRenewalIsAccepted()=runTest {
        for(independent in listOf(false,true)) {
            val api=Api();val ready=CompletableDeferred<Unit>();api.load={ready.await();listOf(artist)}
            var current=access
            val controller=MusicArtistsController(backgroundScope,api,{12},{current},
                {initial->MusicReadIdentity(initial).require(current);current},{false})
            controller.show();runCurrent()
            current=MusicAccess("b".repeat(64),"CI",accountId=12,readSessionId=if(independent)"other" else access.readSessionId)
            ready.complete(Unit);runCurrent()
            assertEquals(!independent,controller.state.value.loaded)
        }
    }
}
