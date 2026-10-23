package com.bencodez.mcperks.tests.scheduling;
import com.bencodez.mcperks.scheduling.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.simpleapi.scheduler.BukkitScheduler;
import com.bencodez.simpleapi.folialib.FoliaLib;
import com.bencodez.simpleapi.folialib.impl.PlatformScheduler;
import com.bencodez.simpleapi.folialib.enums.EntityTaskResult;
import com.bencodez.simpleapi.folialib.wrapper.task.WrappedTask;
/** Exercises production owner dispatch, including scheduler rejection/retirement completions. */
public final class OwnerTaskHarness {
    public final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>(), timers = new LinkedBlockingQueue<>(), retired = new LinkedBlockingQueue<>();
    public final java.util.Map<Runnable,Runnable> retirement = new ConcurrentHashMap<>();
    public final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    public final PlatformScheduler implementation = mock(PlatformScheduler.class);
    public OwnerTaskHarness(MCPerksMain plugin, Player player) {
        this(plugin, player, false);
    }
    public OwnerTaskHarness(MCPerksMain plugin, Player player, boolean foliaPlatform) {
        // Ordinary gameplay fixtures use Spigot. Entity-owner lifecycle tests explicitly
        // select Folia; real Spigot/Paper code is exercised by BundledOwnerTaskHarness.
        when(player.isValid()).thenReturn(true);
        FoliaLib folia = mock(FoliaLib.class); when(folia.isFolia()).thenReturn(foliaPlatform); when(scheduler.getFoliaLib()).thenReturn(folia); when(folia.getImpl()).thenReturn(implementation); when(plugin.getBukkitScheduler()).thenReturn(scheduler);
        when(folia.getScheduler()).thenReturn(implementation);
        when(implementation.runAtEntityWithFallback(eq(player), any(), any())).thenAnswer(invocation -> {
            Consumer<WrappedTask> action = invocation.getArgument(1); Runnable fallback = invocation.getArgument(2);
            CompletableFuture<EntityTaskResult> result = new CompletableFuture<>();
            Runnable task = () -> { action.accept(null); result.complete(EntityTaskResult.SUCCESS); };
            Runnable drop = () -> { fallback.run(); result.complete(EntityTaskResult.ENTITY_RETIRED); };
            retirement.put(task, drop); tasks.add(task); retired.add(drop);
            return result;
        });
        when(implementation.runAtEntityLater(eq(player), any(Runnable.class), any(Runnable.class), anyLong())).thenAnswer(invocation -> {
            timers.add(invocation.getArgument(1)); retired.add(invocation.getArgument(2)); return mock(WrappedTask.class);
        });
        when(implementation.runLater(any(Runnable.class), anyLong())).thenAnswer(invocation -> {
            timers.add(invocation.getArgument(0)); return mock(WrappedTask.class);
        });
    }
}
