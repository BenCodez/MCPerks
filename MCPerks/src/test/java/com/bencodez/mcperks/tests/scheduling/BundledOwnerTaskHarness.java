package com.bencodez.mcperks.tests.scheduling;
import com.bencodez.mcperks.scheduling.*;

import static org.mockito.Mockito.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import org.bukkit.Server;
import org.bukkit.scheduler.BukkitTask;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.simpleapi.folialib.FoliaLib;
import com.bencodez.simpleapi.folialib.impl.PaperImplementation;
import com.bencodez.simpleapi.folialib.impl.ServerImplementation;
import com.bencodez.simpleapi.folialib.impl.SpigotImplementation;

/** Real bundled implementation; only the native Bukkit scheduling boundary is controlled. */
public final class BundledOwnerTaskHarness {
    public final OwnerTaskLifecycleHarness lifecycle;
    public final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
    public final BlockingQueue<Runnable> timers = new LinkedBlockingQueue<>();
    public final org.bukkit.scheduler.BukkitScheduler nativeScheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
    public final ServerImplementation implementation;

    @SuppressWarnings("unchecked")
    public BundledOwnerTaskHarness(MCPerksMain plugin, boolean paper) {
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(nativeScheduler);
        lifecycle = new OwnerTaskLifecycleHarness(plugin);
        OwnerTasks.initialize(plugin);
        // Instantiate the selected facade's real default options when that API exists.
        // The released older facade has no options getter.
        FoliaLib folia = mock(FoliaLib.class, invocation -> {
            var method = invocation.getMethod();
            if (method.getName().equals("getOptions") && method.getParameterCount() == 0) {
                return method.getReturnType().getConstructor().newInstance();
            }
            return RETURNS_DEFAULTS.answer(invocation);
        });
        when(folia.getPlugin()).thenReturn(plugin);
        implementation = paper ? new PaperImplementation(folia) : new SpigotImplementation(folia);
        when(folia.getImpl()).thenAnswer(ignored -> implementation);
        var scheduler = mock(com.bencodez.simpleapi.scheduler.BukkitScheduler.class);
        when(scheduler.getFoliaLib()).thenReturn(folia);
        when(plugin.getBukkitScheduler()).thenReturn(scheduler);
        doAnswer(invocation -> {
            Consumer<BukkitTask> callback = invocation.getArgument(1);
            tasks.add(() -> callback.accept(mock(BukkitTask.class)));
            return null;
        }).when(nativeScheduler).runTask(eq(plugin), any(Consumer.class));
        when(nativeScheduler.runTaskLater(eq(plugin), any(Runnable.class), anyLong())).thenAnswer(invocation -> {
            timers.add(invocation.getArgument(1));
            return mock(BukkitTask.class);
        });
    }
}
