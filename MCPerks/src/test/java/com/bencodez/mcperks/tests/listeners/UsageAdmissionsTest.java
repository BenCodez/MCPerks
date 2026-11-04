package com.bencodez.mcperks.tests.listeners;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.bencodez.mcperks.tests.listeners.UsageAdmissionFixture.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.bencodez.mcperks.listeners.EffectBatch;
import com.bencodez.mcperks.userapi.UsageCharges;
import com.bencodez.mcperks.userapi.UsageCharges.Kind;
import com.bencodez.mcperks.tests.userapi.UsageChargesFixture;

class UsageAdmissionsTest {
    @TempDir Path directory;
    private final UUID owner = UUID.randomUUID();
    private final Player oldPlayer = mock(Player.class), newPlayer = mock(Player.class);
    private final UsageAdmissionFixture state = new UsageAdmissionFixture();

    @Test void twoSameUuidPlayersRequireCurrentIdentityAndLateQuitCannotRetireSuccessor() {
        Object old = state.capture(owner, oldPlayer, oldPlayer);
        Object next = state.capture(owner, newPlayer, newPlayer);
        assertNotSame(old, next);
        assertFalse(state.current(old, oldPlayer));
        assertNull(state.admit(old, oldPlayer, Kind.AutoBlockRefill));
        assertNull(state.capture(owner, oldPlayer, newPlayer), "stale capture must not evict the successor");
        state.quit(owner, oldPlayer);
        assertTrue(state.current(next, newPlayer));
        state.quit(owner, newPlayer);
        assertFalse(state.current(next, newPlayer));
        verifyNoInteractions(oldPlayer, newPlayer); // State/retirement performs no Bukkit access.
    }

    @Test void captureIsBoundToActualListenerAndEpochEvenWithSamePlayerAndUuid() {
        Object old = state.capture(owner, oldPlayer, oldPlayer);
        state.invalidate();
        Object next = state.capture(owner, oldPlayer, oldPlayer);
        assertNotSame(old, next); assertFalse(state.current(old, oldPlayer));
        assertTrue(state.current(next, oldPlayer));
        UsageAdmissionFixture replacement = new UsageAdmissionFixture();
        assertNull(replacement.admit(next, oldPlayer, Kind.AutoBlockRefill));
        state.retire().join();
        assertNull(state.capture(owner, oldPlayer, oldPlayer));
    }

    @Test void genuineJoinRotatesEvenWhenPlayerObjectIsReused() {
        Object before = state.capture(owner, oldPlayer, oldPlayer);
        Object after = state.join(owner, oldPlayer, oldPlayer);
        assertNotSame(before, after, "a genuine join is a new session, not an ordinary capture");
        assertFalse(state.current(before, oldPlayer));
        state.retireScope(before);
        assertTrue(state.current(after, oldPlayer), "late captured retirement must not affect the new epoch");
        assertSame(after, state.capture(owner, oldPlayer, oldPlayer));
    }

    @Test void kindAdmissionIsIndependentButDuplicateSameKindJoinsNoSecondBatch() {
        Object scope = state.capture(owner, oldPlayer, oldPlayer);
        Object refill = state.admit(scope, oldPlayer, Kind.AutoBlockRefill);
        Object vein = state.admit(scope, oldPlayer, Kind.VeinMining);
        assertNotNull(refill); assertNotNull(vein);
        assertNull(state.admit(scope, oldPlayer, Kind.AutoBlockRefill));
        complete(refill);
        assertNotNull(state.admit(scope, oldPlayer, Kind.AutoBlockRefill));
        assertNull(state.admit(scope, oldPlayer, Kind.VeinMining));
    }

    @Test void lateOldSettlementCannotRemoveNewSessionBatch() {
        Object oldScope = state.capture(owner, oldPlayer, oldPlayer);
        Object old = state.admit(oldScope, oldPlayer, Kind.AutoBlockRefill);
        AtomicInteger cancellations = new AtomicInteger(); onCancel(old, cancellations::incrementAndGet);
        Object nextScope = state.capture(owner, newPlayer, newPlayer);
        Object next = state.admit(nextScope, newPlayer, Kind.AutoBlockRefill);
        assertNotNull(next); assertEquals(1, cancellations.get());
        complete(old); complete(old); state.quit(owner, oldPlayer);
        assertNull(state.admit(nextScope, newPlayer, Kind.AutoBlockRefill));
        assertTrue(execute(next, newPlayer)); assertFalse(execute(next, newPlayer));
        complete(next);
        assertNotNull(state.admit(nextScope, newPlayer, Kind.AutoBlockRefill));
    }

    @Test void retirementBeforeReservationAcknowledgementRefundsOnceWithoutOwnerCallback() throws Exception {
        CountDownLatch publishing = new CountDownLatch(1), release = new CountDownLatch(1);
        var blocked = new java.util.concurrent.atomic.AtomicBoolean();
        try (UsageCharges charges = UsageChargesFixture.create(directory.resolve("charges.dat"), (staged, file) -> {
            if (blocked.get()) {
                publishing.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("fixture timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
            }
            com.bencodez.mcperks.data.DurableFeatureFiles.publish(staged, file);
        })) {
            charges.ready().get(5, TimeUnit.SECONDS); charges.set(owner, Kind.AutoBlockRefill, 4).get();
            Object scope = state.capture(owner, oldPlayer, oldPlayer);
            Object batch = state.admit(scope, oldPlayer, Kind.AutoBlockRefill);
            blocked.set(true);
            var admission = charges.reserve(owner, Kind.AutoBlockRefill, 1);
            assertTrue(publishing.await(5, TimeUnit.SECONDS));
            CompletableFuture<Void> observed = admission.thenAccept(reservation -> onCancel(batch, () ->
                    charges.finish(reservation, 0, reservation.units()).whenComplete((v, e) -> complete(batch))));
            var retired = state.retire(); assertFalse(retired.isDone());
            blocked.set(false); release.countDown();
            observed.get(5, TimeUnit.SECONDS); retired.get(5, TimeUnit.SECONDS);
            assertEquals(4, charges.get(owner, Kind.AutoBlockRefill));
            assertEquals(0, charges.successful(owner, Kind.AutoBlockRefill));
            assertFalse(execute(batch, oldPlayer));
        } finally { release.countDown(); }
    }

    @Test void reconnectAfterReservationReturnsOnlyUnusedUnitsAndPreservesSuccessor() throws Exception {
        try (UsageCharges charges = new UsageCharges(directory.resolve("charges.dat"))) {
            charges.ready().get(); charges.set(owner, Kind.VeinMining, 4).get();
            Object scope = state.capture(owner, oldPlayer, oldPlayer);
            Object batch = state.admit(scope, oldPlayer, Kind.VeinMining);
            var reservation = charges.reserve(owner, Kind.VeinMining, 3).get();
            var settlement = new CompletableFuture<Void>();
            onCancel(batch, () -> charges.finish(reservation, 0, 3).whenComplete((v, e) -> { complete(batch); settlement.complete(null); }));
            Object next = state.capture(owner, newPlayer, newPlayer);
            Object successor = state.admit(next, newPlayer, Kind.VeinMining);
            settlement.get(5, TimeUnit.SECONDS); cancel(batch); complete(batch);
            assertEquals(4, charges.get(owner, Kind.VeinMining));
            assertNull(state.admit(next, newPlayer, Kind.VeinMining));
            assertTrue(execute(successor, newPlayer)); complete(successor);
        }
    }

    @Test void duplicateCancellationAndActionRaceHasOneTerminalOwner() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 64; i++) {
                Object scope = state.capture(owner, oldPlayer, oldPlayer);
                Object batch = state.admit(scope, oldPlayer, Kind.AutoBlockRefill);
                AtomicInteger actions = new AtomicInteger(), retired = new AtomicInteger();
                onCancel(batch, () -> { retired.incrementAndGet(); complete(batch); });
                CountDownLatch start = new CountDownLatch(1);
                var action = pool.submit(() -> { start.await(); if (execute(batch, oldPlayer)) { actions.incrementAndGet(); complete(batch); } return null; });
                var cancellation = pool.submit(() -> { start.await(); cancel(batch); cancel(batch); return null; });
                start.countDown(); action.get(5, TimeUnit.SECONDS); cancellation.get(5, TimeUnit.SECONDS);
                assertEquals(1, actions.get() + retired.get());
            }
        }
    }

    @Test void outstandingSettlementsKeepGlobalBoundEvenWhenSessionsAreReplaced() {
        List<Object> old = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            Object scope = state.capture(owner, new Object(), null);
            assertNull(scope); // Failed proof never consumes capacity.
            Object actor = new Object(); scope = state.capture(owner, actor, actor);
            Object batch = state.admit(scope, actor, Kind.AutoBlockRefill); assertNotNull(batch); old.add(batch);
        }
        Object scope = state.capture(owner, newPlayer, newPlayer);
        assertNull(state.admit(scope, newPlayer, Kind.AutoBlockRefill));
        complete(old.getFirst());
        assertNotNull(state.admit(scope, newPlayer, Kind.AutoBlockRefill));
        old.forEach(UsageAdmissionFixture::complete);
        assertTrue(state.current(scope, newPlayer));
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 63})
    void exactUnitBudgetsDoNotInvokeAdditionalEffects(int budget) {
        AtomicInteger applied = new AtomicInteger();
        List<EffectBatch.Unit> units = java.util.stream.IntStream.range(0, 63)
                .<EffectBatch.Unit>mapToObj(i -> () -> { applied.incrementAndGet(); return true; }).toList();
        var result = EffectBatch.execute(units, budget);
        assertEquals(budget, applied.get()); assertEquals(budget, result.successful()); assertEquals(0, result.unused());
    }

    @Test void uncertainCurrentEffectStaysSpentWhileOnlyProvedUnusedBudgetIsRefunded() throws Exception {
        try (UsageCharges charges = new UsageCharges(directory.resolve("charges.dat"))) {
            charges.ready().get(); charges.set(owner, Kind.VeinMining, 5).get();
            var reservation = charges.reserve(owner, Kind.VeinMining, 4).get();
            AtomicInteger callbacks = new AtomicInteger();
            var result = EffectBatch.execute(List.of(() -> true, () -> { throw new Exception("uncertain effect"); },
                    () -> { callbacks.incrementAndGet(); return true; }, () -> false), 4);
            assertEquals(1, result.successful()); assertEquals(2, result.unused()); assertEquals(0, callbacks.get());
            charges.finish(reservation, result.successful(), result.unused()).get();
            charges.finish(reservation, result.successful(), result.unused()).get();
            assertEquals(3, charges.get(owner, Kind.VeinMining)); assertEquals(1, charges.successful(owner, Kind.VeinMining));
        }
    }
}
