package com.bencodez.mcperks.tests.scheduling;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.scheduling.OwnerTasks;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class OwnerTasksDisableCancellationTest {
    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void nativeCancellationRetiresWithoutExecutingQueuedCallback(boolean paper, boolean delayed) {
        MCPerksMain plugin = mock(MCPerksMain.class);
        when(plugin.isEnabled()).thenReturn(true);
        Player player = mock(Player.class);
        when(player.isValid()).thenReturn(true);
        var harness = new BundledOwnerTaskHarness(plugin, paper);
        var lifecycle = harness.lifecycle;
        doAnswer(ignored -> {
            harness.tasks.clear();
            harness.timers.clear();
            return null;
        }).when(harness.nativeScheduler).cancelTasks(plugin);
        AtomicInteger executed = new AtomicInteger(), retired = new AtomicInteger();
        if (delayed) {
            OwnerTasks.later(plugin, player, executed::incrementAndGet, retired::incrementAndGet, 20);
        } else {
            OwnerTasks.run(plugin, player, executed::incrementAndGet, retired::incrementAndGet);
        }
        assertEquals(1, harness.tasks.size() + harness.timers.size());
        lifecycle.disable(() -> harness.nativeScheduler.cancelTasks(plugin));
        verify(harness.nativeScheduler).cancelTasks(plugin);
        assertTrue(harness.tasks.isEmpty());
        assertTrue(harness.timers.isEmpty());
        assertEquals(0, executed.get());
        assertEquals(1, retired.get());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void disableEventRejectsNewAdmissionsBeforeEnabledFlagFalls(boolean delayed) {
        Fixture f = fixture(false);
        f.submit(delayed);
        f.harness.lifecycle.fireDisable(f.plugin);
        assertTrue(f.plugin.isEnabled());
        assertEquals(1, f.retired.get());
        f.submit(delayed);
        assertEquals(2, f.retired.get());
        assertEquals(1, f.harness.tasks.size() + f.harness.timers.size());
        f.disable();
        assertEquals(0, f.executed.get());
        assertEquals(2, f.retired.get());
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void completedAdmissionsAreNotRetiredByLaterDisable(boolean paper, boolean delayed) {
        Fixture f = fixture(paper);
        f.submit(delayed);
        f.take(delayed).run();
        f.disable();
        assertEquals(1, f.executed.get());
        assertEquals(0, f.retired.get());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void lateCallbackAndRepeatedDisableCannotCompleteOwnershipTwice(boolean delayed) {
        Fixture f = fixture(false);
        f.submit(delayed);
        Runnable late = delayed ? f.harness.timers.element() : f.harness.tasks.element();
        f.harness.lifecycle.fireDisable(f.plugin);
        f.harness.lifecycle.fireDisable(f.plugin);
        f.disable();
        late.run();
        late.run();
        assertEquals(0, f.executed.get());
        assertEquals(1, f.retired.get());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void throwingRetirementDoesNotStrandOtherPendingAdmissions(boolean linkageFailure) {
        Fixture f = fixture(false);
        when(f.plugin.getLogger()).thenReturn(mock(Logger.class));
        AtomicInteger throwing = new AtomicInteger();
        OwnerTasks.run(f.plugin, f.player, () -> fail("cancelled"), () -> {
            throwing.incrementAndGet();
            if (linkageFailure) throw new NoSuchMethodError("synthetic retirement linkage failure");
            throw new IllegalStateException("synthetic retirement failure");
        });
        f.submit(true);
        assertDoesNotThrow(f::disable);
        assertEquals(1, throwing.get());
        assertEquals(1, f.retired.get());
        assertEquals(0, f.executed.get());
        assertTrue(f.harness.tasks.isEmpty());
        assertTrue(f.harness.timers.isEmpty());
        verify(f.plugin.getLogger()).log(eq(java.util.logging.Level.WARNING),
                eq("Owner-task retirement callback failed"), any(Throwable.class));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void foreignPluginDisableKeepsOwnAdmissionPending(boolean delayed) {
        Fixture f = fixture(false);
        f.submit(delayed);
        f.harness.lifecycle.fireDisable(mock(Plugin.class));
        assertEquals(0, f.retired.get());
        f.take(delayed).run();
        assertEquals(1, f.executed.get());
        f.disable();
        assertEquals(0, f.retired.get());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void reenabledEpochCannotReviveOldCallbacks(boolean delayed) {
        Fixture f = fixture(false);
        f.submit(delayed);
        Runnable stale = delayed ? f.harness.timers.element() : f.harness.tasks.element();
        f.disable();
        when(f.plugin.isEnabled()).thenReturn(true);
        OwnerTasks.initialize(f.plugin);
        f.submit(delayed);
        stale.run();
        assertEquals(0, f.executed.get());
        assertEquals(1, f.retired.get());
        f.take(delayed).run();
        f.disable();
        assertEquals(1, f.executed.get());
        assertEquals(1, f.retired.get());
    }

    @Test void uninitializedOwnerRetiresWithoutLookingUpScheduler() {
        MCPerksMain plugin = mock(MCPerksMain.class);
        when(plugin.isEnabled()).thenReturn(true);
        Player player = mock(Player.class);
        AtomicInteger retired = new AtomicInteger();
        OwnerTasks.run(plugin, player, () -> fail("uninitialized"), retired::incrementAndGet);
        OwnerTasks.later(plugin, player, () -> fail("uninitialized"), retired::incrementAndGet, 20);
        assertEquals(2, retired.get());
        verify(plugin, never()).getBukkitScheduler();
        verify(player, never()).isValid();
    }

    @Test void disabledInitializationDoesNotRegisterAnOwner() {
        MCPerksMain plugin = mock(MCPerksMain.class);
        var lifecycle = new OwnerTaskLifecycleHarness(plugin);
        OwnerTasks.initialize(plugin);
        AtomicInteger retired = new AtomicInteger();
        OwnerTasks.run(plugin, mock(Player.class), () -> fail("disabled"), retired::incrementAndGet);
        assertEquals(1, retired.get());
        verifyNoInteractions(lifecycle.manager);
    }

    @Test void failedListenerRegistrationLeavesAdmissionClosed() {
        MCPerksMain plugin = mock(MCPerksMain.class);
        when(plugin.isEnabled()).thenReturn(true);
        var lifecycle = new OwnerTaskLifecycleHarness(plugin);
        doThrow(new IllegalStateException("synthetic registration rejection"))
                .when(lifecycle.manager).registerEvent(eq(PluginDisableEvent.class), any(Listener.class),
                        any(EventPriority.class), any(EventExecutor.class), eq(plugin));
        assertThrows(IllegalStateException.class, () -> OwnerTasks.initialize(plugin));
        AtomicInteger retired = new AtomicInteger();
        OwnerTasks.run(plugin, mock(Player.class), () -> fail("unregistered"), retired::incrementAndGet);
        assertEquals(1, retired.get());
        verify(plugin, never()).getBukkitScheduler();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void completionCallbacksDoNotHoldOwnerLock(boolean retirement) throws Exception {
        Fixture f = fixture(false);
        var executor = Executors.newSingleThreadExecutor();
        AtomicInteger callbacks = new AtomicInteger();
        Runnable callback = () -> {
            var closed = executor.submit(() -> OwnerTasks.shutdown(f.plugin));
            assertDoesNotThrow(() -> closed.get(3, TimeUnit.SECONDS));
            callbacks.incrementAndGet();
        };
        try {
            OwnerTasks.later(f.plugin, f.player,
                    retirement ? () -> fail("retired") : callback,
                    retirement ? callback : () -> fail("completed"), 20);
            if (retirement) OwnerTasks.shutdown(f.plugin);
            else f.take(true).run();
            assertEquals(1, callbacks.get());
        } finally {
            f.disable();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test void disableDuringAdmissionCannotExecuteItsLateQueuedCallback() {
        MCPerksMain plugin = mock(MCPerksMain.class);
        when(plugin.isEnabled()).thenReturn(true);
        Player player = mock(Player.class);
        var harness = new OwnerTaskHarness(plugin, player);
        doAnswer(invocation -> {
            harness.lifecycle.fireDisable(plugin);
            harness.timers.add(invocation.getArgument(0));
            return mock(com.bencodez.simpleapi.folialib.wrapper.task.WrappedTask.class);
        }).when(harness.implementation).runLater(any(Runnable.class), anyLong());
        AtomicInteger retired = new AtomicInteger(), executed = new AtomicInteger();
        OwnerTasks.later(plugin, player, executed::incrementAndGet, retired::incrementAndGet, 20);
        assertEquals(1, retired.get());
        harness.timers.remove().run();
        assertEquals(0, executed.get());
        assertEquals(1, retired.get());
        harness.lifecycle.disable(() -> harness.tasks.clear());
    }

    private static Fixture fixture(boolean paper) {
        MCPerksMain plugin = mock(MCPerksMain.class);
        when(plugin.isEnabled()).thenReturn(true);
        Player player = mock(Player.class);
        when(player.isValid()).thenReturn(true);
        var harness = new BundledOwnerTaskHarness(plugin, paper);
        doAnswer(ignored -> {
            harness.tasks.clear();
            harness.timers.clear();
            return null;
        }).when(harness.nativeScheduler).cancelTasks(plugin);
        return new Fixture(plugin, player, harness, new AtomicInteger(), new AtomicInteger());
    }

    private record Fixture(MCPerksMain plugin, Player player, BundledOwnerTaskHarness harness,
                           AtomicInteger executed, AtomicInteger retired) {
        void submit(boolean delayed) {
            if (delayed) OwnerTasks.later(plugin, player, executed::incrementAndGet, retired::incrementAndGet, 20);
            else OwnerTasks.run(plugin, player, executed::incrementAndGet, retired::incrementAndGet);
        }
        Runnable take(boolean delayed) { return delayed ? harness.timers.remove() : harness.tasks.remove(); }
        void disable() { harness.lifecycle.disable(() -> harness.nativeScheduler.cancelTasks(plugin)); }
    }
}
