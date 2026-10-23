package com.bencodez.mcperks.tests.data;
import static com.bencodez.mcperks.tests.TestAccess.*;
import com.bencodez.mcperks.data.*;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
class FeatureStateTest {
    @TempDir Path directory;
    private static <T> T await(CompletableFuture<T> future) throws Exception { return future.get(5, TimeUnit.SECONDS); }
    @Test void directStartUsesExistingBoundedWireFormatAndNeverBecomesWaitingOnRecovery() throws Exception {
        var file = directory.resolve("direct.dat"); UUID owner = UUID.randomUUID(); FeatureState.QueueEntry entry;
        try (var state = new FeatureState(file)) {
            await(state.ready()); entry = await(state.startDirect(owner, "personal", "Activations", 1));
            assertEquals(FeatureState.Stage.STARTING, entry.stage()); assertNull(await(state.claim(entry.id())));
            assertThrows(ExecutionException.class, () -> await(state.startDirect(owner, "other", "Activations", 2)));
            assertEquals(1, state.reserved(owner, "Activations"));
        }
        try (var recovered = new FeatureState(file)) {
            await(recovered.ready()); assertEquals(List.of(entry.withStage(FeatureState.Stage.UNCERTAIN)), recovered.queue());
            assertNull(await(recovered.claim(entry.id()))); assertEquals(1, recovered.reserved(owner, "Activations"));
        }
    }
    /** Real wire fixture avoids thousands of unrelated O(n) atomic publications in a boundary test. */
    private static String unicodeBankFixture(Path file) throws Exception {
        String name = "\uD83D\uDE00".repeat(62); // 124 UTF-16 code units, 372 modified UTF-8 bytes.
        try (var out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(file)))) {
            out.writeInt(0x4d435032); out.writeBoolean(false); out.writeInt(0); out.writeInt(7518);
            for (int i = 1; i <= 7518; i++) {
                out.writeLong(0); out.writeLong(i); out.writeUTF(name); out.writeLong(5000); out.writeBoolean(false);
            }
        }
        assertEquals(2_999_695, Files.size(file)); return name;
    }
    @Test void serializedBankCapacityRejectsBeforeTheActivationDebit() throws Exception {
        Path file = directory.resolve("bank-admission.dat");
        String name = "\uD83D\uDE00".repeat(62);
        try (var out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(file)))) {
            out.writeInt(0x4d435032); out.writeBoolean(false); out.writeInt(0); out.writeInt(7516);
            for (int i = 1; i <= 7516; i++) {
                out.writeLong(0); out.writeLong(i); out.writeUTF(name); out.writeLong(5000); out.writeBoolean(false);
            }
        }
        assertEquals(2_998_897, Files.size(file));
        UUID owner = new UUID(0, 9000); var debits = new java.util.concurrent.atomic.AtomicInteger();
        try (var state = new FeatureState(file)) {
            await(state.ready());
            var entry = await(state.enqueue(owner, name, "PerkActivations_" + name, 1));
            assertEquals(2_999_705, Files.size(file));
            await(state.claim(entry.id())); byte[] before = Files.readAllBytes(file);
            var result = await(state.storage(() -> state.addBankOnWorker(new FeatureState.BankKey(owner, name),
                    10000, () -> { debits.incrementAndGet(); return true; })));
            assertNull(result); assertEquals(0, debits.get()); assertTrue(state.available());
            assertFalse(state.banks().containsKey(new FeatureState.BankKey(owner, name)));
            assertArrayEquals(before, Files.readAllBytes(file));
            // The real planner releases this deterministic rejection, preserving unused entitlement.
            await(state.releaseClaim(entry.id()));
            assertEquals(FeatureState.Stage.WAITING, state.queue().getFirst().stage());
            assertEquals(1, state.reserved(owner, "PerkActivations_" + name));
        }
        try (var recovered = new FeatureState(file)) {
            await(recovered.ready()); assertTrue(recovered.available());
            assertEquals(FeatureState.Stage.WAITING, recovered.queue().getFirst().stage());
            assertEquals(7516, recovered.banks().size());
        }
    }
    @ParameterizedTest @ValueSource(ints = {1, 2, 3})
    void actualSerializedPublicationAcceptsBelowAndAtByteLimitAndRejectsAboveIt(int padding) throws Exception {
        Path file = directory.resolve("unicode.dat"); String legacyName = unicodeBankFixture(file);
        String name = "\uD83D\uDE00".repeat(46) + "x".repeat(padding);
        var key = new FeatureState.BankKey(new UUID(0, 8000), name);
        byte[] previous = Files.readAllBytes(file);
        try (var state = new FeatureState(file)) {
            await(state.ready()); assertEquals(7518, state.banks().size());
            if (padding <= 2) {
                await(state.setBank(key, 6000)); assertEquals(2_999_998L + padding, Files.size(file));
                await(state.pauseQueue(true)); // Another publication at this exact size remains valid.
                assertTrue(state.available());
                byte[] accepted = Files.readAllBytes(file);
                assertThrows(ExecutionException.class, () -> await(state.enqueue(UUID.randomUUID(), "overflow", "", 0)));
                assertArrayEquals(accepted, Files.readAllBytes(file)); assertTrue(state.queue().isEmpty());
            } else {
                assertThrows(ExecutionException.class, () -> await(state.setBank(key, 6000)));
                assertFalse(state.banks().containsKey(key)); assertArrayEquals(previous, Files.readAllBytes(file));
                // Capacity rejection does not disable safety/lifecycle updates of existing entries.
                await(state.pauseBank(new FeatureState.BankKey(new UUID(0, 1), legacyName), true));
            }
            assertTrue(state.available());
            try (var files = Files.list(directory)) { assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".feature-"))); }
        }
        try (var recovered = new FeatureState(file)) {
            await(recovered.ready()); assertTrue(recovered.available());
            assertEquals(padding <= 2 ? 7519 : 7518, recovered.banks().size());
            if (padding <= 2) assertEquals(new FeatureState.Bank(6000, false), recovered.banks().get(key));
            else assertTrue(recovered.banks().get(new FeatureState.BankKey(new UUID(0, 1), legacyName)).paused());
        }
    }
    @Test void oversizedRecoveryStillFailsClosedAtTheUnchangedByteBound() throws Exception {
        Path file = directory.resolve("oversized.dat"); unicodeBankFixture(file);
        try (var state = new FeatureState(file)) {
            await(state.setBank(new FeatureState.BankKey(new UUID(0, 8000), "\uD83D\uDE00".repeat(46) + "xx"), 6000));
            assertEquals(3_000_000, Files.size(file));
        }
        Files.write(file, new byte[] {0}, StandardOpenOption.APPEND);
        try (var state = new FeatureState(file)) {
            assertThrows(ExecutionException.class, () -> await(state.ready()));
            assertFalse(state.available()); assertTrue(state.banks().isEmpty());
            assertThrows(ExecutionException.class, () -> await(state.pauseQueue(true)));
            assertEquals(3_000_001, Files.size(file));
        }
    }
    @Test void queueOwnershipPauseReorderAndRestartAreDurable() throws Exception {
        Path file = directory.resolve("feature.dat"); UUID a = UUID.randomUUID(), b = UUID.randomUUID(); FeatureState.QueueEntry first, second;
        try (FeatureState state = new FeatureState(file)) {
            first = await(state.enqueue(a, "Old perk.v1", "Activations", 2)); second = await(state.enqueue(b, "vein", "", 0));
            assertFalse(await(state.cancel(first.id(), b, false))); await(state.pauseQueue(true)); assertNull(await(state.claim(first.id())));
            assertTrue(await(state.move(second.id(), 1))); assertEquals(second.id(), state.queue().getFirst().id());
            assertThrows(ExecutionException.class, () -> await(state.enqueue(a, "vein", "Activations", 2)));
        }
        try (FeatureState state = new FeatureState(file)) {
            await(state.ready()); assertTrue(state.paused()); assertEquals(List.of(second.id(), first.id()), state.queue().stream().map(FeatureState.QueueEntry::id).toList());
            assertTrue(await(state.cancel(first.id(), a, false))); assertFalse(await(state.cancel(first.id(), a, false))); assertEquals(0, state.reserved(a, "Activations"));
        }
    }
    @Test void startFenceIsSingleUseAndUncertainRestartNeverReplaysOrRefundsCredits() throws Exception {
        Path file = directory.resolve("feature.dat"); UUID owner = UUID.randomUUID(); FeatureState.QueueEntry entry;
        try (FeatureState state = new FeatureState(file)) {
            entry = await(state.enqueue(owner, "vein", "Activations", 1)); assertNotNull(await(state.claim(entry.id())));
            assertNull(await(state.claim(entry.id()))); assertFalse(await(state.cancel(entry.id(), owner, false)));
        }
        try (FeatureState state = new FeatureState(file)) {
            await(state.ready()); assertEquals(FeatureState.Stage.UNCERTAIN, state.queue().getFirst().stage()); assertNull(await(state.claim(entry.id())));
            assertEquals(1, state.reserved(owner, "Activations")); assertFalse(await(state.cancel(entry.id(), owner, false)));
            assertTrue(await(state.cancel(entry.id(), UUID.randomUUID(), true))); // explicit staff removal, no DB credit refund
        }
    }
    @Test void queueCapacityReclaimedByCancellationAndFailedPublicationNeverClaims() throws Exception {
        Path file = directory.resolve("feature.dat");
        try (FeatureState state = new FeatureState(file)) {
            FeatureState.QueueEntry first = null;
            for (int i = 0; i < 256; i++) { var entry = await(state.enqueue(UUID.randomUUID(), "vein", "", 0)); if (first == null) first = entry; }
            assertThrows(ExecutionException.class, () -> await(state.enqueue(UUID.randomUUID(), "vein", "", 0)));
            assertTrue(await(state.cancel(first.id(), first.owner(), false))); await(state.enqueue(UUID.randomUUID(), "vein", "", 0));
            var head = state.queue().getFirst(); Files.delete(file); Files.createDirectory(file);
            assertThrows(ExecutionException.class, () -> await(state.claim(head.id())));
            assertEquals(FeatureState.Stage.WAITING, state.queue().getFirst().stage());
        }
    }
    @Test void bankedLifecyclePersistsPauseAndRemainingWithoutChangingLegacyExpiration() throws Exception {
        Path file = directory.resolve("feature.dat"); var key = new FeatureState.BankKey(UUID.randomUUID(), "vein");
        try (FeatureState state = new FeatureState(file)) {
            await(state.addBank(key, 10000)); await(state.elapsed(key, 3000)); await(state.pauseBank(key, true)); await(state.elapsed(key, 3000));
            await(state.addBank(key, 2000)); assertEquals(new FeatureState.Bank(9000, true), state.banks().get(key));
        }
        try (FeatureState state = new FeatureState(file)) {
            await(state.ready()); assertEquals(new FeatureState.Bank(9000, true), state.banks().get(key));
            await(state.pauseBank(key, false)); await(state.elapsed(key, 15000)); assertEquals(0, state.banks().get(key).milliseconds());
            assertTrue(state.queue().isEmpty());
        }
    }
    @Test void malformedStateIsUnknownAndDoesNotErasePurchasedTime() throws Exception {
        Path file = directory.resolve("feature.dat"); Files.writeString(file, "not a ledger");
        try (FeatureState state = new FeatureState(file)) {
            assertThrows(ExecutionException.class, () -> await(state.ready())); assertThrows(ExecutionException.class, () -> await(state.setBank(new FeatureState.BankKey(UUID.randomUUID(), "vein"), 5000)));
            assertEquals("not a ledger", Files.readString(file));
        }
    }
    @Test void successorWaitsOffOwnerUntilPriorLifecycleDrainsAndReadsItsFinalPublishedState() throws Exception {
        Path file=directory.resolve("features.dat"); var key=new FeatureState.BankKey(UUID.randomUUID(),"passive");
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        FeatureState old=new FeatureState(file); await(old.ready());
        old.storage(() -> { entered.countDown(); try { if (!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); } return null; });
        assertTrue(entered.await(5,TimeUnit.SECONDS)); var write=old.setBank(key,5000); old.close();
        try (FeatureState successor=new FeatureState(file)) {
            assertFalse(successor.ready().isDone()); release.countDown(); await(write); await(successor.ready());
            assertEquals(5000,successor.banks().get(key).milliseconds());
        } finally { release.countDown(); old.close(); }
    }

    @Test void bankCapacityRejectsOverflowWithoutSilentTruncationAndSerializesConcurrentSetWithDebit() throws Exception {
        var key=new FeatureState.BankKey(UUID.randomUUID(),"passive"); long limit=31_536_000_000L;
        try(FeatureState state=new FeatureState(directory.resolve("features.dat"))) {
            await(state.setBank(key,limit)); await(state.pauseBank(key,true));
            assertThrows(ExecutionException.class,()->await(state.addBank(key,1)));
            var debits=new java.util.concurrent.atomic.AtomicInteger();
            assertNull(await(state.storage(()->state.addBankOnWorker(key,1,()->{ debits.incrementAndGet(); return true; }))));
            assertEquals(new FeatureState.Bank(limit,true),state.banks().get(key)); assertEquals(0,debits.get());
            await(state.setBank(key,limit-1000));
            var replacement=new java.util.concurrent.atomic.AtomicReference<CompletableFuture<FeatureState.Bank>>();
            assertEquals(limit,await(state.storage(()->state.addBankOnWorker(key,1000,()->{
                debits.incrementAndGet(); replacement.set(state.setBank(key,1234)); return true;
            }))).milliseconds());
            await(replacement.get()); assertEquals(1234,state.banks().get(key).milliseconds()); assertEquals(1,debits.get());
            assertThrows(IllegalStateException.class,()->state.addBankOnWorker(key,1,()->true));
        }
        try(FeatureState state=new FeatureState(directory.resolve("features.dat"))) {
            await(state.ready()); assertEquals(1234,state.banks().get(key).milliseconds());
        }
    }

    @Test void failedGrantPublicationRollsBackClockWithoutMintingTime() throws Exception {
        var fail = new java.util.concurrent.atomic.AtomicBoolean();
        var clock = new com.bencodez.mcperks.perk.BankedClock();
        var time = new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        var key = new FeatureState.BankKey(java.util.UUID.randomUUID(), "passive");
        try (var store = newFeatureState(directory.resolve("grant.dat"), (staged,file) -> {
            if (fail.get()) throw new java.io.IOException("fixture grant publication failed");
            DurableFeatureFiles.publish(staged,file);
        })) {
            await(store.setBank(key,10000)); clock.checkpoint(time.get(),true);
            store.onBankGrant((k,balance) -> clock.prepareGrant(time.get(),balance,time::get));
            time.addAndGet(2_000_000_000L); fail.set(true);
            assertThrows(java.util.concurrent.ExecutionException.class, () -> await(store.addBank(key,10000)));
            assertEquals(10000,store.banks().get(key).milliseconds());
            assertEquals(8000,clock.remaining(() -> store.banks().get(key).milliseconds(),time.get()));
            assertFalse(store.available());
        }
    }
    @Test void delayedGrantPublishesBeforeRebasingAndPreservesEarlierBoundedDebit() throws Exception {
        var clock = new com.bencodez.mcperks.perk.BankedClock();
        var time = new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        var block = new java.util.concurrent.atomic.AtomicBoolean();
        var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        var key = new FeatureState.BankKey(java.util.UUID.randomUUID(), "passive");
        try (var store = newFeatureState(directory.resolve("delayed-grant.dat"), (staged,file) -> {
            if (block.get()) { entered.countDown(); try { if (!release.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("fixture timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); } }
            DurableFeatureFiles.publish(staged,file);
        })) {
            await(store.setBank(key,1000)); clock.checkpoint(time.get(),true);
            time.addAndGet(2_000_000_000L);
            long debit = clock.checkpoint(time.get(),true,() -> store.banks().get(key).milliseconds());
            assertEquals(1000,debit);
            store.onBankGrant((k,balance) -> clock.prepareGrant(time.get(),balance,time::get));
            block.set(true); var grant = store.addBank(key,10000); assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0,clock.remaining(() -> store.banks().get(key).milliseconds(),time.get()));
            time.addAndGet(5_000_000_000L); assertEquals(0,clock.checkpoint(time.get(),true,() -> store.banks().get(key).milliseconds()));
            var pendingDebit = store.elapsed(key,debit).thenRun(() -> clock.acknowledge(debit));
            release.countDown(); await(grant); pendingDebit.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(10000,store.banks().get(key).milliseconds());
            time.addAndGet(1_000_000_000L);
            assertEquals(9000,clock.remaining(() -> store.banks().get(key).milliseconds(),time.get()));
        } finally { release.countDown(); }
    }

}
