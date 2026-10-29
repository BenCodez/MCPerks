package com.bencodez.mcperks.data;

import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** One bounded file owner, retained until all admitted storage work has drained. */
public final class OrderedStorage implements AutoCloseable {
    private final FeatureFileOwnership ownership;
    private final ThreadPoolExecutor worker;
    private final CompletableFuture<Void> ready;
    private volatile Thread ownerThread;
    private volatile boolean ownershipFailed;

    public OrderedStorage(Path ownershipFile) {
        ownership = new FeatureFileOwnership(ownershipFile);
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256), task -> {
            Thread thread = new Thread(task, "MCPerks-feature-store");
            thread.setDaemon(true);
            ownerThread = thread;
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy()) {
            @Override protected void terminated() { ownership.drained(); }
        };
        // This is the first admitted operation. Later work cannot overtake ownership acquisition.
        ready = runtimeStorage(() -> {
            try { ownership.awaitPredecessor(); }
            catch (RuntimeException | Error failure) { ownershipFailed = true; throw failure; }
            return null;
        });
    }

    public CompletableFuture<Void> ready() { return ready; }

    public boolean isOwnerThread() { return Thread.currentThread() == ownerThread; }

    /** Independent native storage remains usable when optional feature data cannot be decoded. */
    public <T> CompletableFuture<T> runtimeStorage(Supplier<T> operation) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            worker.execute(() -> {
                if (ownershipFailed) {
                    future.completeExceptionally(new IllegalStateException("Storage owner unavailable until restart"));
                    return;
                }
                try { future.complete(operation.get()); }
                catch (Throwable failure) { future.completeExceptionally(failure); }
            });
        } catch (RejectedExecutionException failure) { future.completeExceptionally(failure); }
        return future;
    }

    /** Stops admission; queued work retains the OS lock until the worker actually terminates. */
    @Override public void close() { worker.shutdown(); }
}
