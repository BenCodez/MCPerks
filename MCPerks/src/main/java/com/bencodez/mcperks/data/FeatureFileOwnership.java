package com.bencodez.mcperks.data;
import java.nio.file.*;
import java.nio.channels.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
/** Storage workers retain an OS lock through drain, including across plugin classloader replacement. */
public final class FeatureFileOwnership {
    private static final Map<Path, CompletableFuture<Void>> owners = new HashMap<>();
    private final Path path;
    private final CompletableFuture<Void> predecessor, drained = new CompletableFuture<>();
    private FileChannel channel;
    private FileLock lock;
    public FeatureFileOwnership(Path file) {
        path = file.toAbsolutePath().normalize();
        synchronized (owners) { predecessor = owners.put(path, drained); }
    }
    public synchronized void awaitPredecessor() {
        if (predecessor != null) predecessor.join();
        try {
            Files.createDirectories(path.getParent());
            Path guard = path.resolveSibling(path.getFileName() + ".lock");
            channel = FileChannel.open(guard, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (lock == null) {
                try { lock = channel.tryLock(); } catch (OverlappingFileLockException busy) { }
                if (lock != null) return;
                if (System.nanoTime() >= deadline) throw new IOException("Predecessor storage owner has not drained");
                try { TimeUnit.MILLISECONDS.sleep(25); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Storage ownership interrupted", interrupted); }
            }
        } catch (IOException failure) {
            try { if (channel != null) channel.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            channel = null;
            throw new UncheckedIOException(failure);
        }
    }
    public synchronized void drained() {
        if (drained.isDone()) return;
        try {
            if (channel != null) channel.close(); // Releases the lock before publishing drain completion.
            lock = null; channel = null; drained.complete(null);
        } catch (IOException failure) { drained.completeExceptionally(failure); }
        synchronized (owners) { owners.remove(path, drained); }
        // Keep the empty lock file: unlinking it could let another classloader lock a different inode.
    }
}
