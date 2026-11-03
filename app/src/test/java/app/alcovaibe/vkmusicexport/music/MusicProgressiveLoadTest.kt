package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MusicProgressiveLoadTest {
    private fun track(id: Int)=NativeTrack("12_$id","Track $id","CI",120)

    @Test fun verifiedTrackPagesArePublishedImmediatelyWithoutDeduplicatingPositions()=runTest {
        val first=track(1);val second=track(2)
        val states=mutableListOf<NativePage>();val offsets=mutableListOf<Int>()
        val result=MusicPagination.tracks({offset->
            offsets.add(offset)
            if(offset==0)NativePage(listOf(first,second),3)
            else NativePage(listOf(first),3)
        },partial=states::add)
        assertEquals(listOf(0,2),offsets)
        assertEquals(listOf(2,3),states.map{it.tracks.size})
        assertTrue(states.all{it.count==3})
        assertEquals(listOf(first,second,first),result.tracks)
        assertEquals(listOf(first,second),states.first().tracks)
    }

    @Test fun aFailingLaterPageKeepsOnlyTheEarlierVerifiedSnapshotAndRetriesTwice()=runTest {
        val states=mutableListOf<NativePage>();var secondPageRequests=0
        try {
            MusicPagination.tracks({offset->
                if(offset==0)NativePage(listOf(track(1)),2)
                else {secondPageRequests++;throw MusicFailure(MusicError.NETWORK)}
            },partial=states::add)
            fail("Second page failed")
        }catch(e: MusicFailure){assertEquals(MusicError.NETWORK,e.code)}
        assertEquals(3,secondPageRequests)
        assertEquals(1,states.size)
        assertEquals(listOf(track(1)),states.single().tracks)
        assertEquals(2,states.single().count)
    }

    @Test fun rateLimitsNeverTriggerAnAutomaticTrackRetry()=runTest {
        var requests=0
        try {
            MusicPagination.tracks({
                requests++;throw MusicFailure(MusicError.RATE_LIMIT)
            })
            fail("Rate limit must propagate")
        }catch(e: MusicFailure){assertEquals(MusicError.RATE_LIMIT,e.code)}
        assertEquals(1,requests)
    }
}
