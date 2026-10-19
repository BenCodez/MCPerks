package com.bencodez.mcperks.tests.perk;
import com.bencodez.mcperks.perk.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class BankedClockTest {
    @Test void onlineEligibleElapsedUsesMonotonicTimeAndOfflineResumeStartsANewCheckpoint() {
        BankedClock clock = new BankedClock(); assertEquals(0, clock.checkpoint(1_000_000_000L, true));
        assertEquals(2500, clock.checkpoint(3_500_000_000L, true)); clock.pause();
        assertEquals(0, clock.checkpoint(100_000_000_000L, true)); assertEquals(1000, clock.checkpoint(101_000_000_000L, true));
    }
    @Test void manualWorldAndIgnoreTransitionsDoNotChargeIneligibleIntervals() {
        BankedClock clock = new BankedClock(); clock.checkpoint(1_000_000_000L, true);
        assertEquals(1000, clock.checkpoint(2_000_000_000L, false)); assertEquals(0, clock.checkpoint(200_000_000_000L, false));
        assertEquals(0, clock.checkpoint(201_000_000_000L, true)); assertEquals(1000, clock.checkpoint(202_000_000_000L, true));
    }
    @Test void pendingDebitAndReadOnlyElapsedQueriesCannotMintTimeDuringStorageDelays() {
        BankedClock clock = new BankedClock();
        var confirmed = new java.util.concurrent.atomic.AtomicLong(1000);
        clock.checkpoint(1_000_000_000L, true);
        assertEquals(500, clock.remaining(confirmed::get, 1_500_000_000L));
        assertEquals(500, clock.remaining(confirmed::get, 1_500_000_000L));
        assertEquals(500, clock.checkpoint(1_500_000_000L, true));
        assertEquals(0, clock.remaining(confirmed::get, 2_000_000_000L));
        confirmed.set(500); // Published balance, before callback acknowledges the matching debit.
        assertEquals(0, clock.remaining(confirmed::get, 2_000_000_000L));
        clock.acknowledge(500);
        assertEquals(0, clock.remaining(confirmed::get, 2_000_000_000L));
        assertEquals(500, clock.checkpoint(2_000_000_000L, false));
        confirmed.set(0); clock.acknowledge(500);
        assertEquals(0, clock.remaining(confirmed::get, 50_000_000_000L));
    }
    @Test void grantPublicationMovesBoundaryAfterOldBalanceIsConsumed() {
        BankedClock clock = new BankedClock();
        clock.checkpoint(1_000_000_000L, true);
        var grant = clock.prepareGrant(3_000_000_000L, 1000, () -> 3_000_000_000L);
        assertEquals(1000, grant.elapsed());
        assertEquals(0, clock.remaining(() -> 11_000, 3_500_000_000L));
        grant.commit().run();
        assertEquals(1000, clock.checkpoint(4_000_000_000L, false));
    }
    @Test void grantPublicationPreservesAnEarlierPendingDebit() {
        BankedClock clock = new BankedClock();
        clock.checkpoint(1_000_000_000L, true);
        assertEquals(1000, clock.checkpoint(2_000_000_000L, true));
        var grant = clock.prepareGrant(3_000_000_000L, 1000, () -> 3_000_000_000L);
        assertEquals(0, grant.elapsed()); grant.commit().run();
        clock.acknowledge(1000);
        assertEquals(1000, clock.checkpoint(4_000_000_000L, false));
    }
    @Test void staleReplacementAcknowledgementCannotReleaseANewerDebitReservation() {
        var clock = new BankedClock(); var confirmed = new java.util.concurrent.atomic.AtomicLong(10000);
        clock.checkpoint(1_000_000_000L, true);
        var oldDebit = clock.reserveCheckpoint(2_000_000_000L, true, confirmed::get);
        clock.prepareReplacement(() -> 3_000_000_000L).commit().run();
        var currentDebit = clock.reserveCheckpoint(4_000_000_000L, true, confirmed::get);
        assertFalse(clock.owns(oldDebit)); assertTrue(clock.owns(currentDebit));
        assertEquals(9000, clock.remaining(confirmed::get, 4_000_000_000L));
        clock.acknowledge(oldDebit);
        assertEquals(9000, clock.remaining(confirmed::get, 4_000_000_000L));
        confirmed.set(9000); clock.acknowledge(currentDebit);
        assertEquals(9000, clock.remaining(confirmed::get, 4_000_000_000L));
    }
}
