package com.bencodez.mcperks.tests.data;
import static com.bencodez.mcperks.tests.TestAccess.*;
import com.bencodez.mcperks.data.*;
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



}
