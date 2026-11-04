package com.bencodez.mcperks.tests.data;

import static org.junit.jupiter.api.Assertions.*;

import com.bencodez.mcperks.data.OrderedStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrderedStorageTest {
    @TempDir Path root;

    @Test void operationsUseOneWorkerAndRetainAdmissionOrder() throws Exception {
        try (var storage = new OrderedStorage(root.resolve("state.dat"))) {
            storage.ready().get(5, TimeUnit.SECONDS);
            assertFalse(storage.isOwnerThread());
            var observed = new ArrayList<Integer>();
            var operations = new ArrayList<CompletableFuture<?>>();
            for (int i = 0; i < 20; i++) {
                int position = i;
                operations.add(storage.runtimeStorage(() -> {
                    assertTrue(storage.isOwnerThread());
                    observed.add(position);
                    return null;
                }));
            }
            CompletableFuture.allOf(operations.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
            assertEquals(java.util.stream.IntStream.range(0, 20).boxed().toList(), observed);
        }
    }

    @Test void saturatedAdmissionFailsWithoutDroppingAcceptedWork() throws Exception {
        var release = new CountDownLatch(1);
        try (var storage = new OrderedStorage(root.resolve("state.dat"))) {
            storage.ready().get(5, TimeUnit.SECONDS);
            var entered = new CountDownLatch(1);
            var blocker = storage.runtimeStorage(() -> { entered.countDown(); await(release); return null; });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var queued = new ArrayList<CompletableFuture<Integer>>();
            for (int i = 0; i < 256; i++) { int value = i; queued.add(storage.runtimeStorage(() -> value)); }
            var overflowRan = new AtomicBoolean();
            var overflow = storage.runtimeStorage(() -> { overflowRan.set(true); return null; });
            assertInstanceOf(RejectedExecutionException.class,
                    assertThrows(ExecutionException.class, () -> overflow.get(5, TimeUnit.SECONDS)).getCause());
            assertFalse(overflowRan.get());
            release.countDown();
            blocker.get(5, TimeUnit.SECONDS);
            for (int i = 0; i < 256; i++) assertEquals(i, queued.get(i).get(5, TimeUnit.SECONDS));
        } finally { release.countDown(); }
    }

    @Test void operationFailureDoesNotDisableIndependentNativeStorage() throws Exception {
        try (var storage = new OrderedStorage(root.resolve("state.dat"))) {
            var failure = new IllegalArgumentException("invalid optional payload");
            var rejected = storage.runtimeStorage(() -> { throw failure; });
            assertSame(failure, assertThrows(ExecutionException.class,
                    () -> rejected.get(5, TimeUnit.SECONDS)).getCause());
            assertEquals("native", storage.runtimeStorage(() -> "native").get(5, TimeUnit.SECONDS));
        }
    }

    @Test void ownershipFailureFreezesAllSubsequentPublication() throws Exception {
        Path file = root.resolve("state.dat"), unrelated = root.resolve("unrelated");
        Files.writeString(unrelated, "unchanged");
        Files.createSymbolicLink(root.resolve("state.dat.lock"), unrelated);
        try (var storage = new OrderedStorage(file)) {
            assertThrows(ExecutionException.class, () -> storage.ready().get(5, TimeUnit.SECONDS));
            var invoked = new AtomicBoolean();
            assertThrows(ExecutionException.class, () -> storage.runtimeStorage(() -> {
                invoked.set(true); return null;
            }).get(5, TimeUnit.SECONDS));
            assertFalse(invoked.get());
            assertEquals("unchanged", Files.readString(unrelated));
            assertFalse(Files.exists(file));
        }
    }

    @Test void closeRejectsNewWorkButRetainsOwnershipThroughActualDrain() throws Exception {
        Path file = root.resolve("state.dat");
        var release = new CountDownLatch(1);
        try (var first = new OrderedStorage(file)) {
            first.ready().get(5, TimeUnit.SECONDS);
            var entered = new CountDownLatch(1);
            var writing = first.runtimeStorage(() -> {
                entered.countDown(); await(release);
                try { Files.writeString(file, "old-drained"); }
                catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
                return null;
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            first.close();
            assertThrows(ExecutionException.class, () -> first.runtimeStorage(() -> null).get(5, TimeUnit.SECONDS));
            try (var successor = new OrderedStorage(file)) {
                assertThrows(TimeoutException.class, () -> successor.ready().get(100, TimeUnit.MILLISECONDS));
                assertFalse(Files.exists(file));
                release.countDown();
                writing.get(5, TimeUnit.SECONDS);
                successor.ready().get(5, TimeUnit.SECONDS);
                assertEquals("old-drained", Files.readString(file));
            }
        } finally { release.countDown(); }
    }

    @Test void cancellingAnObservationDoesNotReleasePendingWriterOwnership() throws Exception {
        var release = new CountDownLatch(1);
        try (var storage = new OrderedStorage(root.resolve("state.dat"))) {
            storage.ready().get(5, TimeUnit.SECONDS);
            var entered = new CountDownLatch(1);
            var published = new AtomicBoolean();
            var writing = storage.runtimeStorage(() -> {
                entered.countDown(); await(release); published.set(true); return null;
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(writing.cancel(false));
            var barrier = storage.runtimeStorage(() -> null);
            assertFalse(barrier.isDone());
            release.countDown();
            barrier.get(5, TimeUnit.SECONDS);
            assertTrue(published.get());
        } finally { release.countDown(); }
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Worker release timed out"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
}
