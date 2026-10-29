package app.alcovaibe.vkmusicexport.music

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DurableSchedulingTest {
    @Test fun cancellationAfterQueuedStillSchedulesAndRecordsAcceptance()=runTest {
        var scheduled=false;var accepted=false
        launch {
            currentCoroutineContext().cancel() // Activity stops immediately after the durable QUEUED write.
            settleQueueScheduling(schedule={yield();scheduled=true;true},settled={accepted=it})
        }.join()
        assertTrue(scheduled);assertTrue(accepted)
    }
    @Test fun rejectedSchedulerRestoresPausedEvenWhenCallerIsCancelled()=runTest {
        var state="QUEUED"
        launch {
            currentCoroutineContext().cancel()
            settleQueueScheduling(schedule={throw IllegalStateException("scheduler unavailable")},settled={if(!it)state="PAUSED"})
        }.join()
        assertEquals("PAUSED",state)
    }
}
