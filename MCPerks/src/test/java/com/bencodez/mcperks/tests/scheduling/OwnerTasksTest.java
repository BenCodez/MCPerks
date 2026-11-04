package com.bencodez.mcperks.tests.scheduling;
import com.bencodez.mcperks.scheduling.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.simpleapi.folialib.enums.EntityTaskResult;
class OwnerTasksTest {
    @Test void rejectedEntitySchedulerCompletesAdmissionEvenWhenFallbackIsNotCalled() {
        MCPerksMain plugin=mock(MCPerksMain.class); when(plugin.isEnabled()).thenReturn(true); Player player=mock(Player.class); var harness=new OwnerTaskHarness(plugin,player);
        when(harness.implementation.runAtEntityWithFallback(eq(player),any(),any())).thenReturn(CompletableFuture.completedFuture(EntityTaskResult.SCHEDULER_RETIRED));
        AtomicInteger executed=new AtomicInteger(),retired=new AtomicInteger(); OwnerTasks.run(plugin,player,executed::incrementAndGet,retired::incrementAndGet);
        assertEquals(0,executed.get()); assertEquals(1,retired.get());
    }
    @Test void retirementAndDuplicateCallbackCannotBothCompleteTheSameAdmission() {
        MCPerksMain plugin=mock(MCPerksMain.class); when(plugin.isEnabled()).thenReturn(true); Player player=mock(Player.class); var harness=new OwnerTaskHarness(plugin,player);
        AtomicInteger executed=new AtomicInteger(),retired=new AtomicInteger(); OwnerTasks.run(plugin,player,executed::incrementAndGet,retired::incrementAndGet);
        Runnable action=harness.tasks.remove(); harness.retirement.get(action).run(); action.run(); action.run(); assertEquals(0,executed.get()); assertEquals(1,retired.get());
    }
    @Test void rejectedDelayedEntityTaskUsesExplicitTicksAndReleasesOwnership() {
        MCPerksMain plugin=mock(MCPerksMain.class); when(plugin.isEnabled()).thenReturn(true); Player player=mock(Player.class); var harness=new OwnerTaskHarness(plugin,player,true);
        when(harness.implementation.runAtEntityLater(eq(player),any(Runnable.class),any(Runnable.class),eq(20L))).thenReturn(null);
        AtomicInteger retired=new AtomicInteger(); OwnerTasks.later(plugin,player,()->fail("not admitted"),retired::incrementAndGet,20); assertEquals(1,retired.get());
        verify(harness.implementation,never()).runLater(any(Runnable.class),anyLong());
    }
    @Test void foliaDelayedEntityRetirementCannotAlsoExecuteTheAction() {
        MCPerksMain plugin=mock(MCPerksMain.class); when(plugin.isEnabled()).thenReturn(true); Player player=mock(Player.class); var harness=new OwnerTaskHarness(plugin,player,true);
        AtomicInteger executed=new AtomicInteger(),retired=new AtomicInteger();
        OwnerTasks.later(plugin,player,executed::incrementAndGet,retired::incrementAndGet,20);
        harness.retired.remove().run(); harness.timers.remove().run();
        assertEquals(0,executed.get()); assertEquals(1,retired.get());
        verify(harness.implementation,never()).runLater(any(Runnable.class),anyLong());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void bundledSpigotAndPaperDelayRunsOnceAfterTheRequestedTicks(boolean paper) {
        MCPerksMain plugin=mock(MCPerksMain.class); when(plugin.isEnabled()).thenReturn(true);
        Player player=mock(Player.class); when(player.isValid()).thenReturn(true);
        var harness=new BundledOwnerTaskHarness(plugin,paper);
        AtomicInteger executed=new AtomicInteger(),retired=new AtomicInteger();
        assertDoesNotThrow(()->OwnerTasks.later(plugin,player,executed::incrementAndGet,retired::incrementAndGet,20));
        verify(harness.nativeScheduler).runTaskLater(eq(plugin),any(Runnable.class),eq(20L));
        verify(player,never()).isValid(); // Entity validity is read only after returning to its owner.
        assertEquals(0,executed.get()); assertEquals(0,retired.get());
        Runnable delayed=harness.timers.remove(); delayed.run(); delayed.run();
        assertEquals(1,executed.get()); assertEquals(0,retired.get());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void bundledDelayRetiresAnEntityThatBecameInvalidBeforeExecution(boolean paper) {
        MCPerksMain plugin=mock(MCPerksMain.class); when(plugin.isEnabled()).thenReturn(true);
        Player player=mock(Player.class); when(player.isValid()).thenReturn(true);
        var harness=new BundledOwnerTaskHarness(plugin,paper);
        AtomicInteger executed=new AtomicInteger(),retired=new AtomicInteger();
        OwnerTasks.later(plugin,player,executed::incrementAndGet,retired::incrementAndGet,20);
        when(player.isValid()).thenReturn(false);
        Runnable delayed=harness.timers.remove(); delayed.run(); delayed.run();
        assertEquals(0,executed.get()); assertEquals(1,retired.get());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void bundledDelayRejectsSchedulerFailureAndDisabledPluginBeforeExecution(boolean paper) {
        MCPerksMain plugin=mock(MCPerksMain.class); when(plugin.isEnabled()).thenReturn(true);
        Player player=mock(Player.class); when(player.isValid()).thenReturn(true);
        var harness=new BundledOwnerTaskHarness(plugin,paper);
        AtomicInteger executed=new AtomicInteger(),retired=new AtomicInteger();
        OwnerTasks.later(plugin,player,executed::incrementAndGet,retired::incrementAndGet,20);
        when(plugin.isEnabled()).thenReturn(false);
        harness.timers.remove().run(); assertEquals(1,retired.get());
        when(plugin.isEnabled()).thenReturn(true);
        when(harness.nativeScheduler.runTaskLater(eq(plugin),any(Runnable.class),eq(20L))).thenThrow(new IllegalStateException("scheduler retired"));
        OwnerTasks.later(plugin,player,executed::incrementAndGet,retired::incrementAndGet,20);
        assertEquals(0,executed.get()); assertEquals(2,retired.get()); assertTrue(harness.timers.isEmpty());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void bundledImmediateCallbackAfterDisableRetiresOnceWithoutRunningTheAction(boolean paper) {
        MCPerksMain plugin=mock(MCPerksMain.class); when(plugin.isEnabled()).thenReturn(true);
        Player player=mock(Player.class); when(player.isValid()).thenReturn(true);
        var harness=new BundledOwnerTaskHarness(plugin,paper);
        AtomicInteger executed=new AtomicInteger(),retired=new AtomicInteger();
        OwnerTasks.run(plugin,player,executed::incrementAndGet,retired::incrementAndGet);
        when(plugin.isEnabled()).thenReturn(false);
        Runnable callback=harness.tasks.remove(); callback.run(); callback.run();
        assertEquals(0,executed.get()); assertEquals(1,retired.get());
        assertTrue(harness.tasks.isEmpty());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void bundledImmediateSiblingUsesTheNativeOwnerAndCompletesInvalidEntityRetirementOnce(boolean paper) {
        MCPerksMain plugin=mock(MCPerksMain.class); when(plugin.isEnabled()).thenReturn(true);
        Player player=mock(Player.class); when(player.isValid()).thenReturn(true);
        var harness=new BundledOwnerTaskHarness(plugin,paper);
        AtomicInteger executed=new AtomicInteger(),retired=new AtomicInteger();
        OwnerTasks.run(plugin,player,executed::incrementAndGet,retired::incrementAndGet);
        assertEquals(0,executed.get()); harness.tasks.remove().run(); assertEquals(1,executed.get());
        OwnerTasks.run(plugin,player,executed::incrementAndGet,retired::incrementAndGet);
        when(player.isValid()).thenReturn(false);
        Runnable rejected=harness.tasks.remove(); rejected.run(); rejected.run();
        assertEquals(1,executed.get()); assertEquals(1,retired.get());
    }
}
