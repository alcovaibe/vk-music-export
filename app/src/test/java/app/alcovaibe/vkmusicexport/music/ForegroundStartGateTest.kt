package app.alcovaibe.vkmusicexport.music

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ForegroundStartGateTest {
    @Test fun stoppingAnIdleTransportCanStopImmediately() {
        val gate=ForegroundStartGate()
        assertTrue(gate.stop());assertEquals(0,gate.pendingCount())
    }
    @Test fun everyAcceptedStartMustBeAcknowledgedBeforeADeferredStop() {
        val gate=ForegroundStartGate();val first=gate.begin();val second=gate.begin()
        assertFalse(gate.stop())
        assertEquals(ForegroundStartGate.Acknowledgement(true,false),gate.acknowledge(first))
        assertEquals(ForegroundStartGate.Acknowledgement(true,true),gate.acknowledge(second))
        assertEquals(0,gate.pendingCount())
    }
    @Test fun aNewUserStartSupersedesTheStopButAnOldAcknowledgementCannotConsumeIt() {
        val gate=ForegroundStartGate();val old=gate.begin();assertFalse(gate.stop())
        val next=gate.begin()
        assertEquals(ForegroundStartGate.Acknowledgement(false,false),gate.acknowledge(old))
        assertEquals(1,gate.pendingCount())
        assertEquals(ForegroundStartGate.Acknowledgement(false,true),gate.acknowledge(next))
    }
    @Test fun aRejectedStartOnlyRemovesItsOwnPendingTicket() {
        val gate=ForegroundStartGate();val accepted=gate.begin();val rejected=gate.begin()
        gate.acknowledge(rejected)
        assertEquals(1,gate.pendingCount());assertFalse(gate.stop())
        assertEquals(ForegroundStartGate.Acknowledgement(true,true),gate.acknowledge(accepted))
    }
    @Test fun duplicatedOrUnknownAcknowledgementsCannotConsumeAnotherStart() {
        val gate=ForegroundStartGate();val first=gate.begin();val second=gate.begin()
        gate.acknowledge(first);gate.acknowledge(first);gate.acknowledge(0)
        assertEquals(1,gate.pendingCount());assertFalse(gate.stop())
        assertEquals(ForegroundStartGate.Acknowledgement(true,true),gate.acknowledge(second))
    }
    @Test fun concurrentPauseAndPromotionAlwaysLeaveOnePathResponsibleForStopping() {
        val executor=Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val gate=ForegroundStartGate();val ticket=gate.begin();val ready=CountDownLatch(1)
                val stop=executor.submit<Boolean>{ready.await();gate.stop()}
                val promote=executor.submit<ForegroundStartGate.Acknowledgement>{ready.await();gate.acknowledge(ticket)}
                ready.countDown()
                val immediate=stop.get(5,TimeUnit.SECONDS);val acknowledged=promote.get(5,TimeUnit.SECONDS)
                assertTrue(immediate||acknowledged.cancelled&&acknowledged.drained)
                assertEquals(0,gate.pendingCount())
            }
        }finally{executor.shutdownNow()}
    }
}
