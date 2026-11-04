package com.bencodez.mcperks.tests.commands;
import com.bencodez.mcperks.commands.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.bencodez.advancedcore.api.command.CommandHandler;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.scheduling.OwnerTasks;

class ReloadCommandTest {
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void actualRegisteredReloadReportsOnlyCompletedOutcome(boolean fails) throws Exception {
        var plugin = mock(MCPerksMain.class);
        var commands = new ArrayList<CommandHandler>(); when(plugin.getCommands()).thenReturn(commands);
        when(plugin.getOptions()).thenReturn(mock(com.bencodez.advancedcore.AdvancedCoreConfigOptions.class));
        when(plugin.getName()).thenReturn("MCPerks");
        when(plugin.getDescription()).thenReturn(new PluginDescriptionFile("MCPerks", "test", "fixture.Main"));
        var completion = new CompletableFuture<Void>(); when(plugin.reloadAsync()).thenReturn(completion);
        var scheduler = mock(com.bencodez.simpleapi.scheduler.BukkitScheduler.class);
        when(plugin.getBukkitScheduler()).thenReturn(scheduler);
        var reply = new AtomicReference<Runnable>();
        doAnswer(i -> { reply.set(i.getArgument(1)); return null; }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        var loader = new CommandLoader(plugin);
        var register = CommandLoader.class.getDeclaredMethod("loadReloadCommand"); register.setAccessible(true); register.invoke(loader);
        assertEquals(1, commands.size()); assertEquals("MCPerks.Reload", commands.getFirst().getPerm());
        var sender = mock(CommandSender.class);
        commands.getFirst().execute(sender, new String[] {"Reload"});
        verifyNoInteractions(sender); assertNull(reply.get());
        if (fails) completion.completeExceptionally(new IllegalStateException("private fixture detail")); else completion.complete(null);
        verifyNoInteractions(sender); assertNotNull(reply.get()); reply.get().run();
        verify(sender).sendMessage(contains(fails ? "reload failed" : "reloaded!"));
        verify(sender, never()).sendMessage(contains(fails ? "reloaded!" : "reload failed"));
        verify(sender, never()).sendMessage(contains("private fixture detail"));
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void playerReplyUsesItsOwnerAndDropsDisconnectedRecipients(boolean online) throws Exception {
        var plugin = mock(MCPerksMain.class);
        var commands = new ArrayList<CommandHandler>(); when(plugin.getCommands()).thenReturn(commands);
        when(plugin.getOptions()).thenReturn(mock(com.bencodez.advancedcore.AdvancedCoreConfigOptions.class));
        when(plugin.getName()).thenReturn("MCPerks");
        when(plugin.getDescription()).thenReturn(new PluginDescriptionFile("MCPerks", "test", "fixture.Main"));
        var completion = new CompletableFuture<Void>(); when(plugin.reloadAsync()).thenReturn(completion);
        var player = mock(Player.class); when(player.isOnline()).thenReturn(online);
        var loader = new CommandLoader(plugin);
        var register = CommandLoader.class.getDeclaredMethod("loadReloadCommand"); register.setAccessible(true); register.invoke(loader);
        var action = new AtomicReference<Runnable>();
        try (var owners = mockStatic(OwnerTasks.class)) {
            owners.when(() -> OwnerTasks.run(eq(plugin), eq(player), any(), any())).thenAnswer(i -> { action.set(i.getArgument(2)); return null; });
            commands.getFirst().execute(player, new String[] {"Reload"}); completion.complete(null);
            verify(player, never()).sendMessage(anyString()); assertNotNull(action.get()); action.get().run();
            verify(player, times(online ? 1 : 0)).sendMessage(anyString());
        }
    }
}
