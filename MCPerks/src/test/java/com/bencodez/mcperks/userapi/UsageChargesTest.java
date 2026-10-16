package com.bencodez.mcperks.userapi;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.bencodez.mcperks.listeners.EffectBatch;
import com.bencodez.mcperks.userapi.UsageCharges.Kind;
class UsageChargesTest {
    @TempDir Path directory;
    private static <T> T await(CompletableFuture<T> future) throws Exception { return future.get(5, TimeUnit.SECONDS); }
    @Test void batchNoopsPartialBudgetAndUncertainSideEffectRemainCorrectAfterRestart() throws Exception {
        UUID owner = UUID.randomUUID(); Path file = directory.resolve("charges.dat");
        try (UsageCharges store = new UsageCharges(file)) {
            await(store.ready()); await(store.set(owner, Kind.VeinMining, 3));
            var reservation = await(store.reserve(owner, Kind.VeinMining, 8)); assertEquals(3, reservation.units());
            var result = EffectBatch.execute(List.of(() -> false, () -> true, () -> { throw new IllegalStateException("possible effect"); }, () -> true), reservation.units());
            assertEquals(1, result.successful()); assertEquals(1, result.unused());
            await(store.finish(reservation, result.successful(), result.unused()));
            await(store.finish(reservation, result.successful(), result.unused()));
            assertEquals(1, store.get(owner, Kind.VeinMining)); assertEquals(1, store.successful(owner, Kind.VeinMining));
        }
        try (UsageCharges recovered = new UsageCharges(file)) {
            await(recovered.ready()); assertEquals(1, recovered.get(owner, Kind.VeinMining)); assertEquals(1, recovered.successful(owner, Kind.VeinMining));
        }
    }
    @Test void concurrentAdmissionCannotOverdrawAndDuplicateCompletionsCannotRefundTwice() throws Exception {
        UUID owner = UUID.randomUUID();
        try (UsageCharges store = new UsageCharges(directory.resolve("charges.dat"))) {
            await(store.set(owner, Kind.AutoBlockRefill, 10));
            List<CompletableFuture<UsageCharges.Reservation>> futures = new ArrayList<>();
            for (int i = 0; i < 30; i++) futures.add(store.reserve(owner, Kind.AutoBlockRefill, 1));
            List<UsageCharges.Reservation> admitted = new ArrayList<>();
            for (var future : futures) { var r = await(future); if (r != null) admitted.add(r); }
            assertEquals(10, admitted.size()); assertEquals(0, store.get(owner, Kind.AutoBlockRefill));
            for (var reservation : admitted) { await(store.finish(reservation, 0, 1)); await(store.finish(reservation, 0, 1)); }
            assertEquals(10, store.get(owner, Kind.AutoBlockRefill));
        }
    }
    @Test void restartRetainsUncertainSpendAndRejectsOldReleaseWithoutRegranting() throws Exception {
        UUID owner = UUID.randomUUID(); Path file = directory.resolve("charges.dat"); UsageCharges.Reservation old;
        try (UsageCharges store = new UsageCharges(file)) {
            await(store.add(owner, Kind.RightClickHarvest, 5)); old = await(store.reserve(owner, Kind.RightClickHarvest, 2));
        }
        try (UsageCharges store = new UsageCharges(file)) {
            await(store.ready()); await(store.finish(old, 0, 2)); assertEquals(3, store.get(owner, Kind.RightClickHarvest));
            await(store.record(owner, Kind.RightClickHarvest, 1));
            assertEquals(1, store.successful(owner, Kind.RightClickHarvest));
            assertEquals(0, store.get(owner, Kind.VeinMining));
        }
    }
    @Test void malformedLedgerFailsClosedRatherThanInitializingEmptyBalances() throws Exception {
        Path file = directory.resolve("charges.dat"); Files.writeString(file, "broken");
        try (UsageCharges store = new UsageCharges(file)) {
            assertThrows(ExecutionException.class, () -> await(store.ready()));
            assertThrows(ExecutionException.class, () -> await(store.set(UUID.randomUUID(), Kind.VeinMining, 4)));
            assertEquals("broken", Files.readString(file));
        }
    }
    @Test void failedPublicationNeverAdmitsGameplayAndFreezesLaterUpdates() throws Exception {
        Path file = directory.resolve("charges.dat"); UUID owner = UUID.randomUUID();
        try (UsageCharges store = new UsageCharges(file)) {
            await(store.set(owner, Kind.VeinMining, 2));
            Files.delete(file); Files.createDirectory(file);
            assertThrows(ExecutionException.class, () -> await(store.reserve(owner, Kind.VeinMining, 1)));
            assertThrows(ExecutionException.class, () -> await(store.add(owner, Kind.VeinMining, 2)));
        }
    }
    @Test void cancelledAndNoopUnitsRefundBudgetButSuccessesUseBudgetNotAttemptCount() {
        int[] successes = {0};
        var result = EffectBatch.execute(List.of(() -> false, () -> { successes[0]++; return true; }, () -> { successes[0]++; return true; }), 1);
        assertEquals(1, successes[0]); assertEquals(new EffectBatch.Result(1, 0), result);
        assertEquals(new EffectBatch.Result(0, 2), EffectBatch.execute(List.of(() -> false, () -> false), 2));
    }
    @Test void postPublicationReservationFailureNeverAuthorizesGameplayAndRestartKeepsItSpent() throws Exception {
        Path file=directory.resolve("charges.dat"); UUID owner=UUID.randomUUID(); java.util.concurrent.atomic.AtomicInteger publications=new java.util.concurrent.atomic.AtomicInteger();
        try (UsageCharges store=new UsageCharges(file,(staged,target) -> {
            com.bencodez.mcperks.data.DurableFeatureFiles.publish(staged,target);
            if (publications.incrementAndGet()==2) throw new java.io.IOException("published but acknowledgment uncertain");
        })) {
            await(store.set(owner,Kind.AutoBlockRefill,2));
            assertThrows(ExecutionException.class,() -> await(store.reserve(owner,Kind.AutoBlockRefill,1)));
            assertFalse(store.available()); assertThrows(ExecutionException.class,() -> await(store.add(owner,Kind.AutoBlockRefill,5)));
        }
        try (UsageCharges store=new UsageCharges(file)) { await(store.ready()); assertEquals(1,store.get(owner,Kind.AutoBlockRefill)); }
    }

    @Test void saturatedOrdinaryWorkerSettlesEveryDurableIdentityAndReclaimsCapacity() throws Exception {
        UUID owner = UUID.randomUUID(); Path file = directory.resolve("saturated.dat");
        CountDownLatch publishing = new CountDownLatch(1), resume = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean block = new java.util.concurrent.atomic.AtomicBoolean();
        List<UsageCharges.Reservation> admitted = new ArrayList<>();
        List<CompletableFuture<Void>> settlements = new ArrayList<>(), ordinary = new ArrayList<>();
        try (UsageCharges store = new UsageCharges(file, (staged, target) -> {
            assertEquals("MCPerks-charge-store", Thread.currentThread().getName());
            if (block.compareAndSet(true, false)) {
                publishing.countDown();
                try { if (!resume.await(10, TimeUnit.SECONDS)) throw new java.io.IOException("publication test timeout"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new java.io.IOException(interrupted); }
            }
            com.bencodez.mcperks.data.DurableFeatureFiles.publish(staged, target);
        })) {
            await(store.set(owner, Kind.VeinMining, 768));
            for (int i = 0; i < 256; i++) admitted.add(await(store.reserve(owner, Kind.VeinMining, 3)));
            assertThrows(ExecutionException.class, () -> await(store.reserve(owner, Kind.VeinMining, 1)));
            block.set(true); ordinary.add(store.record(owner, Kind.VeinMining, 1));
            assertTrue(publishing.await(5, TimeUnit.SECONDS));
            try {
                // One running plus all 256 ordinary waiting slots. Further ordinary work
                // remains rejected while every existing durable identity can still settle.
                for (int i = 0; i < 256; i++) ordinary.add(store.record(owner, Kind.VeinMining, 1));
                assertThrows(ExecutionException.class, () -> await(store.add(owner, Kind.VeinMining, 1)));
                assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
                    for (var reservation : admitted) {
                        var completion = store.finish(reservation, 1, 1); settlements.add(completion);
                        for (int i = 0; i < 4; i++) assertSame(completion, store.finish(reservation, 1, 1));
                        assertSame(completion, store.finish(reservation, 0, 3)); // first proven outcome wins
                        assertFalse(completion.isDone());
                    }
                });
                assertEquals(0, store.get(owner, Kind.VeinMining));
            } finally { resume.countDown(); }
            for (var completion : settlements) await(completion);
            for (var completion : ordinary) await(completion);
            assertEquals(256, store.get(owner, Kind.VeinMining));
            assertEquals(513, store.successful(owner, Kind.VeinMining));
            // All 256 reservation slots are usable again without a restart.
            List<UsageCharges.Reservation> reused = new ArrayList<>();
            for (int i = 0; i < 256; i++) reused.add(await(store.reserve(owner, Kind.VeinMining, 1)));
            for (var reservation : reused) await(store.finish(reservation, 0, 1));
            for (var reservation : admitted) await(store.finish(reservation, 1, 1));
            assertEquals(256, store.get(owner, Kind.VeinMining));
            assertEquals(513, store.successful(owner, Kind.VeinMining));
        } finally { resume.countDown(); }
        try (UsageCharges recovered = new UsageCharges(file)) {
            await(recovered.ready());
            for (var old : admitted) await(recovered.finish(old, 0, 3));
            assertEquals(256, recovered.get(owner, Kind.VeinMining));
            assertEquals(513, recovered.successful(owner, Kind.VeinMining));
        }
    }

    @Test void closeDrainsSettlementAdmittedBehindSaturatedOrdinaryWrites() throws Exception {
        Path file = directory.resolve("drain.dat"); UUID owner = UUID.randomUUID();
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean pause = new java.util.concurrent.atomic.AtomicBoolean();
        UsageCharges store = new UsageCharges(file, (staged, target) -> {
            if (pause.compareAndSet(true, false)) {
                blocked.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new java.io.IOException("drain test timeout"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new java.io.IOException(interrupted); }
            }
            com.bencodez.mcperks.data.DurableFeatureFiles.publish(staged, target);
        });
        try {
            await(store.set(owner, Kind.AutoBlockRefill, 2));
            var reservation = await(store.reserve(owner, Kind.AutoBlockRefill, 2));
            pause.set(true); store.record(owner, Kind.AutoBlockRefill, 1); assertTrue(blocked.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 256; i++) store.record(owner, Kind.AutoBlockRefill, 1);
            var completion = store.finish(reservation, 0, 2);
            assertFalse(completion.isDone()); store.close(); release.countDown(); await(completion);
        } finally { release.countDown(); store.close(); }
        try (UsageCharges recovered = new UsageCharges(file)) {
            await(recovered.ready()); assertEquals(2, recovered.get(owner, Kind.AutoBlockRefill));
            assertEquals(257, recovered.successful(owner, Kind.AutoBlockRefill));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void uncertainSettlementNeverRetriesRefundAndRestartUsesPublishedEvidence(boolean moved) throws Exception {
        Path file = directory.resolve("uncertain-settlement.dat"); UUID owner = UUID.randomUUID();
        java.util.concurrent.atomic.AtomicInteger publications = new java.util.concurrent.atomic.AtomicInteger();
        UsageCharges.Reservation reservation;
        try (UsageCharges store = new UsageCharges(file, (staged, target) -> {
            if (publications.incrementAndGet() == 3) {
                if (moved) com.bencodez.mcperks.data.DurableFeatureFiles.publish(staged, target);
                throw new java.io.IOException("settlement outcome uncertain");
            }
            com.bencodez.mcperks.data.DurableFeatureFiles.publish(staged, target);
        })) {
            await(store.set(owner, Kind.RightClickHarvest, 3)); reservation = await(store.reserve(owner, Kind.RightClickHarvest, 2));
            assertThrows(ExecutionException.class, () -> await(store.finish(reservation, 1, 1)));
            assertFalse(store.available());
            assertThrows(ExecutionException.class, () -> await(store.finish(reservation, 1, 1)));
            assertThrows(ExecutionException.class, () -> await(store.add(owner, Kind.RightClickHarvest, 10)));
            assertEquals(3, publications.get()); assertEquals(1, store.get(owner, Kind.RightClickHarvest));
        }
        try (UsageCharges recovered = new UsageCharges(file)) {
            await(recovered.ready()); await(recovered.finish(reservation, 1, 1));
            assertEquals(moved ? 2 : 1, recovered.get(owner, Kind.RightClickHarvest));
            assertEquals(moved ? 1 : 0, recovered.successful(owner, Kind.RightClickHarvest));
        }
    }

}
