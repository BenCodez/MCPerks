package com.bencodez.mcperks.data;
import static org.junit.jupiter.api.Assertions.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class FeatureFileOwnershipTest {
    @TempDir Path root;
    @Test void freshClassloadersCannotReadOrPublishUntilPredecessorReleasesItsOsLock() throws Exception {
        var url = FeatureFileOwnership.class.getProtectionDomain().getCodeSource().getLocation();
        try (var first = new URLClassLoader(new java.net.URL[] {url}, ClassLoader.getPlatformClassLoader());
             var second = new URLClassLoader(new java.net.URL[] {url}, ClassLoader.getPlatformClassLoader())) {
            var a = first.loadClass(FeatureFileOwnership.class.getName()); var b = second.loadClass(FeatureFileOwnership.class.getName());
            assertNotSame(a,b); Path file=root.resolve("balances.dat");
            var old=a.getConstructor(Path.class).newInstance(file); var next=b.getConstructor(Path.class).newInstance(file);
            a.getMethod("awaitPredecessor").invoke(old);
            var entered=new CountDownLatch(1);
            var successor=CompletableFuture.runAsync(() -> {
                entered.countDown();
                try { b.getMethod("awaitPredecessor").invoke(next); assertEquals("old-drained",Files.readString(file)); Files.writeString(file,"successor-acknowledged"); }
                catch(Exception error) { throw new CompletionException(error); }
            });
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS));
                assertThrows(TimeoutException.class,()->successor.get(100,TimeUnit.MILLISECONDS));
                assertFalse(Files.exists(file));
                Files.writeString(file,"old-drained"); a.getMethod("drained").invoke(old);
                successor.get(5,TimeUnit.SECONDS); assertEquals("successor-acknowledged",Files.readString(file));
                a.getMethod("drained").invoke(old); assertEquals("successor-acknowledged",Files.readString(file));
            } finally { a.getMethod("drained").invoke(old); b.getMethod("drained").invoke(next); }
        }
    }
    @Test void lockGuardSymlinkIsRejectedWithoutReadingItsTarget() throws Exception {
        Path file=root.resolve("features.dat"), target=root.resolve("other"); Files.writeString(target,"untouched");
        Files.createSymbolicLink(root.resolve("features.dat.lock"),target);
        var owner=new FeatureFileOwnership(file);
        try { assertThrows(java.io.UncheckedIOException.class,owner::awaitPredecessor); assertEquals("untouched",Files.readString(target)); }
        finally { owner.drained(); }
    }
    @Test void ownershipFailureFreezesFeatureAndLegacyPublicationWithoutOverwritingExistingState() throws Exception {
        Path file = root.resolve("features.dat"); var key = new FeatureState.BankKey(java.util.UUID.randomUUID(), "vein");
        try (var seed = new FeatureState(file)) { seed.setBank(key, 9000).get(5, TimeUnit.SECONDS); }
        byte[] original = Files.readAllBytes(file);
        Files.deleteIfExists(root.resolve("features.dat.lock"));
        Path unrelated = root.resolve("unrelated"); Files.writeString(unrelated, "do not touch");
        Files.createSymbolicLink(root.resolve("features.dat.lock"), unrelated);
        try (var state = new FeatureState(file)) {
            assertThrows(ExecutionException.class, () -> state.ready().get(5, TimeUnit.SECONDS)); assertFalse(state.available());
            assertThrows(ExecutionException.class, () -> state.setBank(key, 1).get(5, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> state.enqueue(key.owner(), "vein", "", 0).get(5, TimeUnit.SECONDS));
            var invoked = new java.util.concurrent.atomic.AtomicBoolean();
            assertThrows(ExecutionException.class, () -> state.runtimeStorage(() -> { invoked.set(true); return null; }).get(5, TimeUnit.SECONDS));
            assertFalse(invoked.get()); assertArrayEquals(original, Files.readAllBytes(file));
            assertEquals("do not touch", Files.readString(unrelated));
        }
    }
    @Test void ownershipFailureFreezesChargeGrantsReservationsAndUnlimitedStatistics() throws Exception {
        Path file = root.resolve("charges.dat"); var owner = java.util.UUID.randomUUID();
        var kind = com.bencodez.mcperks.userapi.UsageCharges.Kind.VeinMining;
        try (var seed = new com.bencodez.mcperks.userapi.UsageCharges(file)) { seed.set(owner, kind, 12).get(5, TimeUnit.SECONDS); }
        byte[] original = Files.readAllBytes(file); Files.deleteIfExists(root.resolve("charges.dat.lock"));
        Path unrelated = root.resolve("unrelated-charge"); Files.writeString(unrelated, "do not touch");
        Files.createSymbolicLink(root.resolve("charges.dat.lock"), unrelated);
        try (var state = new com.bencodez.mcperks.userapi.UsageCharges(file)) {
            assertThrows(ExecutionException.class, () -> state.ready().get(5, TimeUnit.SECONDS)); assertFalse(state.available());
            assertThrows(ExecutionException.class, () -> state.set(owner, kind, 1).get(5, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> state.add(owner, kind, 1).get(5, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> state.reserve(owner, kind, 1).get(5, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> state.record(owner, kind, 1).get(5, TimeUnit.SECONDS));
            assertArrayEquals(original, Files.readAllBytes(file)); assertEquals("do not touch", Files.readString(unrelated));
        }
    }

}
