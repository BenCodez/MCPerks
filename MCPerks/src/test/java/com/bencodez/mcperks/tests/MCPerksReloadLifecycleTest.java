package com.bencodez.mcperks.tests;
import com.bencodez.mcperks.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.bencodez.mcperks.data.FeatureState;
import com.bencodez.mcperks.perk.QueuePlanner;

/** Production reload admission remains pending until the ordered snapshot settles. */
@org.junit.jupiter.api.Timeout(10)
class MCPerksReloadLifecycleTest {
    @Test
    void reloadCommandBoundaryJoinsPendingOperationAndReportsBarrierFailure() throws Exception {
        MCPerksMain plugin = mock(MCPerksMain.class, CALLS_REAL_METHODS);
        FeatureState state = mock(FeatureState.class);
        QueuePlanner planner = mock(QueuePlanner.class);
        CompletableFuture<Void> barrier = new CompletableFuture<>();
        when(plugin.getFeatureState()).thenReturn(state);
        when(plugin.getQueuePlanner()).thenReturn(planner);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("reload-test"));
        set(plugin, "featureState", state);
        set(plugin, "queuePlanner", planner);
        set(plugin, "reloadPending", new java.util.concurrent.atomic.AtomicBoolean());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("reload-test"));
        doReturn(barrier).when(state).runtimeStorage(any());

        CompletableFuture<Void> first = plugin.reloadAsync();
        assertFalse(first.isDone(), "reload must not report success before the storage barrier");
        assertSame(first, plugin.reloadAsync(), "concurrent reload callers must join one lifecycle operation");
        verify(planner).suspend();

        barrier.completeExceptionally(new IllegalStateException("fixture publication failure"));
        ExecutionException failure = assertThrows(ExecutionException.class, () -> first.get(5, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals("fixture publication failure", failure.getCause().getMessage());
        verify(planner).resume();

        CompletableFuture<Void> successor = plugin.reloadAsync();
        assertNotSame(first, successor, "a completed failure must not poison a later reload request");
    }

    @Test
    void reloadWaitsForOwnerSchedulerAfterStorageBarrier() throws Exception {
        MCPerksMain plugin = mock(MCPerksMain.class, CALLS_REAL_METHODS);
        FeatureState state = mock(FeatureState.class);
        QueuePlanner planner = mock(QueuePlanner.class);
        CompletableFuture<Void> barrier = new CompletableFuture<>();
        AtomicReference<Runnable> scheduled = new AtomicReference<>();
        var scheduler = mock(com.bencodez.simpleapi.scheduler.BukkitScheduler.class);
        var folia = mock(com.bencodez.simpleapi.folialib.FoliaLib.class);
        var implementation = mock(com.bencodez.simpleapi.folialib.impl.PlatformScheduler.class);
        CompletableFuture<Void> dispatch = new CompletableFuture<>();
        when(scheduler.getFoliaLib()).thenReturn(folia);
        when(folia.getImpl()).thenReturn(implementation);
        doAnswer(invocation -> { scheduled.set(() -> invocation.<Consumer<com.bencodez.simpleapi.folialib.wrapper.task.WrappedTask>>getArgument(0)
                .accept(mock(com.bencodez.simpleapi.folialib.wrapper.task.WrappedTask.class))); return dispatch; })
                .when(implementation).runNextTick(any());
        when(plugin.getFeatureState()).thenReturn(state);
        when(plugin.getQueuePlanner()).thenReturn(planner);
        when(plugin.getBukkitScheduler()).thenReturn(scheduler);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("reload-test"));
        set(plugin, "featureState", state);
        set(plugin, "queuePlanner", planner);
        set(plugin, "reloadPending", new java.util.concurrent.atomic.AtomicBoolean());
        doReturn(barrier).when(state).runtimeStorage(any());

        CompletableFuture<Void> operation = plugin.reloadAsync();
        barrier.complete(null);
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (scheduled.get() == null && System.nanoTime() < deadline) Thread.yield();
        assertNotNull(scheduled.get(), "reload must dispatch its lifecycle work to Bukkit");
        assertFalse(operation.isDone(), "reload must remain pending until scheduled lifecycle work completes");
        verify(planner).suspend();
        verify(planner, never()).resume();
    }

    @Test
    void rejectedOwnerDispatchFailsReloadAndReleasesQueueAdmission() throws Exception {
        MCPerksMain plugin = mock(MCPerksMain.class, CALLS_REAL_METHODS);
        FeatureState state = mock(FeatureState.class);
        QueuePlanner planner = mock(QueuePlanner.class);
        CompletableFuture<Void> barrier = CompletableFuture.completedFuture(null);
        CompletableFuture<Void> dispatch = new CompletableFuture<>();
        var scheduler = mock(com.bencodez.simpleapi.scheduler.BukkitScheduler.class);
        var folia = mock(com.bencodez.simpleapi.folialib.FoliaLib.class);
        var implementation = mock(com.bencodez.simpleapi.folialib.impl.PlatformScheduler.class);
        when(scheduler.getFoliaLib()).thenReturn(folia);
        when(folia.getImpl()).thenReturn(implementation);
        when(implementation.runNextTick(any())).thenReturn(dispatch);
        when(plugin.getFeatureState()).thenReturn(state);
        when(plugin.getQueuePlanner()).thenReturn(planner);
        when(plugin.getBukkitScheduler()).thenReturn(scheduler);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("reload-test"));
        set(plugin, "featureState", state);
        set(plugin, "queuePlanner", planner);
        set(plugin, "reloadPending", new java.util.concurrent.atomic.AtomicBoolean());
        doReturn(barrier).when(state).runtimeStorage(any());

        CompletableFuture<Void> operation = plugin.reloadAsync();
        dispatch.completeExceptionally(new IllegalStateException("owner dispatch rejected"));
        ExecutionException failure = assertThrows(ExecutionException.class, () -> operation.get(5, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals("owner dispatch rejected", failure.getCause().getMessage());
        verify(planner).resume();
    }

    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
