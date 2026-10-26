package com.bencodez.mcperks.tests.scheduling;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.bencodez.mcperks.MCPerksMain;
import org.bukkit.Server;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredListener;

/** Real event registrations and delivery; the plugin manager/native cancellation boundary is controlled. */
public final class OwnerTaskLifecycleHarness {
    public final PluginManager manager = mock(PluginManager.class);
    private final MCPerksMain plugin;

    public OwnerTaskLifecycleHarness(MCPerksMain plugin) {
        this.plugin = plugin;
        Server server = plugin.getServer();
        if (server == null) {
            server = mock(Server.class);
            when(plugin.getServer()).thenReturn(server);
        }
        when(server.getPluginManager()).thenReturn(manager);
        doAnswer(invocation -> {
            Listener listener = invocation.getArgument(1);
            EventPriority priority = invocation.getArgument(2);
            EventExecutor executor = invocation.getArgument(3);
            Plugin owner = invocation.getArgument(4);
            PluginDisableEvent.getHandlerList().register(
                    new RegisteredListener(listener, executor, priority, owner, false));
            return null;
        }).when(manager).registerEvent(eq(PluginDisableEvent.class), any(Listener.class),
                any(EventPriority.class), any(EventExecutor.class), eq(plugin));
    }

    public void fireDisable(Plugin target) {
        PluginDisableEvent event = new PluginDisableEvent(target);
        for (RegisteredListener listener : event.getHandlers().getRegisteredListeners()) {
            if (listener.getPlugin().isEnabled()) {
                try {
                    listener.callEvent(event);
                } catch (org.bukkit.event.EventException failure) {
                    throw new AssertionError("Disable event delivery failed", failure);
                }
            }
        }
    }

    /** Mirrors the verified loader event, enabled flag, task cancellation and handler cleanup order. */
    public void disable(Runnable cancelNativeTasks) {
        fireDisable(plugin);
        when(plugin.isEnabled()).thenReturn(false);
        cancelNativeTasks.run();
        HandlerList.unregisterAll(plugin);
    }
}
