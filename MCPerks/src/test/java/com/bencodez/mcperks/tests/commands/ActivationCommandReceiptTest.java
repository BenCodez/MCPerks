package com.bencodez.mcperks.tests.commands;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.commands.CommandLoader;

class ActivationCommandReceiptTest {
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void playerReceiptReplyRunsOnOwnerAndSkipsOfflinePlayer(boolean online) throws Exception {
        var plugin = mock(MCPerksMain.class); var player = mock(org.bukkit.entity.Player.class); when(player.isOnline()).thenReturn(online);
        var receipt = new CompletableFuture<Void>(); var action = new AtomicReference<Runnable>();
        var method = CommandLoader.class.getDeclaredMethod("reportActivationResult", CommandSender.class, java.util.concurrent.CompletionStage.class, String.class);
        method.setAccessible(true);
        try (var owner = mockStatic(com.bencodez.mcperks.scheduling.OwnerTasks.class)) {
            owner.when(() -> com.bencodez.mcperks.scheduling.OwnerTasks.run(eq(plugin), eq(player), any(), any()))
                    .thenAnswer(call -> { action.set(call.getArgument(2)); return null; });
            method.invoke(new CommandLoader(plugin), player, receipt, "&cUpdated activations");
            assertNull(action.get()); receipt.complete(null); assertNotNull(action.get());
            verify(player, never()).sendMessage(anyString()); action.get().run();
            verify(player, times(online ? 1 : 0)).sendMessage(anyString());
        }
    }
    @org.junit.jupiter.api.Test void wildcardAddWaitsForEveryReceiptAndReportsPartialFailure() throws Exception {
        var plugin = mock(MCPerksMain.class); when(plugin.getOptions()).thenReturn(mock(com.bencodez.advancedcore.AdvancedCoreConfigOptions.class));
        when(plugin.getName()).thenReturn("MCPerks"); var commands = new java.util.ArrayList<com.bencodez.advancedcore.api.command.CommandHandler>();
        when(plugin.getCommands()).thenReturn(commands); var perks = mock(com.bencodez.mcperks.perk.PerkHandler.class);
        when(perks.getLoadedPerks()).thenReturn(new java.util.HashMap<>()); when(plugin.getPerkHandler()).thenReturn(perks);
        var users = mock(com.bencodez.mcperks.userapi.UserManager.class); when(plugin.getMcperksUserManager()).thenReturn(users);
        var one = mock(org.bukkit.entity.Player.class); var two = mock(org.bukkit.entity.Player.class);
        var first = mock(com.bencodez.mcperks.userapi.MCPerksUser.class); var second = mock(com.bencodez.mcperks.userapi.MCPerksUser.class);
        when(users.getMCPerksUser(one)).thenReturn(first); when(users.getMCPerksUser(two)).thenReturn(second);
        var firstReceipt = new CompletableFuture<Void>(); var secondReceipt = new CompletableFuture<Void>();
        when(first.addActivationAsync(3)).thenReturn(firstReceipt); when(second.addActivationAsync(3)).thenReturn(secondReceipt);
        var scheduler = mock(com.bencodez.simpleapi.scheduler.BukkitScheduler.class); when(plugin.getBukkitScheduler()).thenReturn(scheduler);
        when(plugin.getLogger()).thenReturn(mock(java.util.logging.Logger.class)); var reply = new AtomicReference<Runnable>();
        doAnswer(call -> { reply.set(call.getArgument(1)); return null; }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        var loader = spy(new CommandLoader(plugin)); doNothing().when(loader).loadAliases(); doNothing().when(loader).loadTabComplete();
        var core = mock(com.bencodez.advancedcore.command.CommandLoader.class);
        when(core.getBasicAdminCommands(anyString())).thenReturn(new java.util.ArrayList<>()); when(core.getBasicCommands(anyString())).thenReturn(new java.util.ArrayList<>());
        var previous = MCPerksMain.plugin; MCPerksMain.plugin = plugin;
        try (var coreStatic = mockStatic(com.bencodez.advancedcore.command.CommandLoader.class); var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            coreStatic.when(com.bencodez.advancedcore.command.CommandLoader::getInstance).thenReturn(core);
            bukkit.when(org.bukkit.Bukkit::getOnlinePlayers).thenReturn(java.util.List.of(one, two)); loader.loadCommands();
            var command = commands.stream().filter(c -> c.getArgs().length > 0 && c.getArgs()[0].equals("AddActivations")).findFirst().orElseThrow();
            var sender = mock(CommandSender.class); command.execute(sender, new String[] {"AddActivations", "*", "3"});
            firstReceipt.complete(null); assertNull(reply.get()); secondReceipt.completeExceptionally(new IllegalStateException("fixture"));
            assertNotNull(reply.get()); reply.get().run(); verify(sender).sendMessage(contains("could not be confirmed"));
            verify(first).addActivationAsync(3); verify(second).addActivationAsync(3);
        } finally { MCPerksMain.plugin = previous; }
    }
    @ParameterizedTest @ValueSource(strings = {"SetActivations", "RemoveActivations", "SetPerkActivations", "AddPerkActivations", "RemovePerkActivations"})
    void registeredMutationCommandsObserveTheirSpecificReceipts(String operation) throws Exception {
        var plugin = mock(MCPerksMain.class); when(plugin.getOptions()).thenReturn(mock(com.bencodez.advancedcore.AdvancedCoreConfigOptions.class));
        when(plugin.getName()).thenReturn("MCPerks"); var commands = new java.util.ArrayList<com.bencodez.advancedcore.api.command.CommandHandler>();
        when(plugin.getCommands()).thenReturn(commands); var perks = mock(com.bencodez.mcperks.perk.PerkHandler.class);
        when(perks.getLoadedPerks()).thenReturn(new java.util.HashMap<>()); when(plugin.getPerkHandler()).thenReturn(perks);
        var users = mock(com.bencodez.mcperks.userapi.UserManager.class); var user = mock(com.bencodez.mcperks.userapi.MCPerksUser.class);
        when(plugin.getMcperksUserManager()).thenReturn(users); when(users.getMCPerksUser("example")).thenReturn(user);
        var receipt = new CompletableFuture<Void>();
        when(user.setActivationsAsync(3)).thenReturn(receipt); when(user.removeActivationClampedAsync(3)).thenReturn(receipt);
        when(user.setActivationsAsync("vote", 3)).thenReturn(receipt); when(user.addActivationAsync("vote", 3)).thenReturn(receipt);
        when(user.removeActivationClampedAsync("vote", 3)).thenReturn(receipt);
        var scheduler = mock(com.bencodez.simpleapi.scheduler.BukkitScheduler.class); when(plugin.getBukkitScheduler()).thenReturn(scheduler);
        var reply = new AtomicReference<Runnable>(); doAnswer(call -> { reply.set(call.getArgument(1)); return null; }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        var loader = spy(new CommandLoader(plugin)); doNothing().when(loader).loadAliases(); doNothing().when(loader).loadTabComplete();
        var core = mock(com.bencodez.advancedcore.command.CommandLoader.class);
        when(core.getBasicAdminCommands(anyString())).thenReturn(new java.util.ArrayList<>()); when(core.getBasicCommands(anyString())).thenReturn(new java.util.ArrayList<>());
        var previous = MCPerksMain.plugin; MCPerksMain.plugin = plugin;
        try (var coreStatic = mockStatic(com.bencodez.advancedcore.command.CommandLoader.class)) {
            coreStatic.when(com.bencodez.advancedcore.command.CommandLoader::getInstance).thenReturn(core); loader.loadCommands();
            var command = commands.stream().filter(c -> c.getArgs().length > 0 && c.getArgs()[0].equals(operation)).findFirst().orElseThrow();
            var sender = mock(CommandSender.class);
            String[] args = operation.contains("Perk") ? new String[] {operation, "example", "vote", "3"} : new String[] {operation, "example", "3"};
            command.execute(sender, args); assertNull(reply.get());
            switch (operation) {
                case "SetActivations" -> verify(user).setActivationsAsync(3);
                case "RemoveActivations" -> verify(user).removeActivationClampedAsync(3);
                case "SetPerkActivations" -> verify(user).setActivationsAsync("vote", 3);
                case "AddPerkActivations" -> verify(user).addActivationAsync("vote", 3);
                case "RemovePerkActivations" -> verify(user).removeActivationClampedAsync("vote", 3);
                default -> fail("unexpected command");
            }
            receipt.complete(null); assertNotNull(reply.get()); verifyNoInteractions(sender); reply.get().run(); verify(sender).sendMessage(anyString());
        } finally { MCPerksMain.plugin = previous; }
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void registeredAddCommandObservesTheAwardReceipt(boolean failure) throws Exception {
        var plugin = mock(MCPerksMain.class); var options = mock(com.bencodez.advancedcore.AdvancedCoreConfigOptions.class);
        when(plugin.getOptions()).thenReturn(options); when(plugin.getName()).thenReturn("MCPerks");
        var commands = new java.util.ArrayList<com.bencodez.advancedcore.api.command.CommandHandler>(); when(plugin.getCommands()).thenReturn(commands);
        var perks = mock(com.bencodez.mcperks.perk.PerkHandler.class); when(perks.getLoadedPerks()).thenReturn(new java.util.HashMap<>()); when(plugin.getPerkHandler()).thenReturn(perks);
        var users = mock(com.bencodez.mcperks.userapi.UserManager.class); var user = mock(com.bencodez.mcperks.userapi.MCPerksUser.class);
        when(plugin.getMcperksUserManager()).thenReturn(users); when(users.getMCPerksUser("example")).thenReturn(user);
        var receipt = new CompletableFuture<Void>(); when(user.addActivationAsync(3)).thenReturn(receipt);
        var scheduler = mock(com.bencodez.simpleapi.scheduler.BukkitScheduler.class); when(plugin.getBukkitScheduler()).thenReturn(scheduler);
        when(plugin.getLogger()).thenReturn(mock(java.util.logging.Logger.class)); var reply = new AtomicReference<Runnable>();
        doAnswer(call -> { reply.set(call.getArgument(1)); return null; }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        var loader = spy(new CommandLoader(plugin)); doNothing().when(loader).loadAliases(); doNothing().when(loader).loadTabComplete();
        var core = mock(com.bencodez.advancedcore.command.CommandLoader.class);
        when(core.getBasicAdminCommands(anyString())).thenReturn(new java.util.ArrayList<>());
        when(core.getBasicCommands(anyString())).thenReturn(new java.util.ArrayList<>());
        var previous = MCPerksMain.plugin; MCPerksMain.plugin = plugin;
        try (var coreStatic = mockStatic(com.bencodez.advancedcore.command.CommandLoader.class)) {
            coreStatic.when(com.bencodez.advancedcore.command.CommandLoader::getInstance).thenReturn(core);
            loader.loadCommands();
            var command = commands.stream().filter(c -> c.getArgs().length > 0 && c.getArgs()[0].equals("AddActivations")).findFirst().orElseThrow();
            var sender = mock(CommandSender.class); command.execute(sender, new String[] {"AddActivations", "example", "3"});
            verify(user).addActivationAsync(3); verify(user, never()).addActivation(3); assertNull(reply.get());
            if (failure) receipt.completeExceptionally(new IllegalStateException("private fixture detail")); else receipt.complete(null);
            assertNotNull(reply.get()); verifyNoInteractions(sender); reply.get().run();
            verify(sender).sendMessage(contains(failure ? "could not be confirmed" : "Added activations"));
        } finally { MCPerksMain.plugin = previous; }
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void commandResponseWaitsForReceiptAndDoesNotReportFailureAsSuccess(boolean failure) throws Exception {
        var plugin = mock(MCPerksMain.class); var scheduler = mock(com.bencodez.simpleapi.scheduler.BukkitScheduler.class);
        when(plugin.getBukkitScheduler()).thenReturn(scheduler); when(plugin.getLogger()).thenReturn(mock(java.util.logging.Logger.class));
        var reply = new AtomicReference<Runnable>();
        doAnswer(call -> { reply.set(call.getArgument(1)); return null; }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        var sender = mock(CommandSender.class); var receipt = new CompletableFuture<Void>();
        var method = CommandLoader.class.getDeclaredMethod("reportActivationResult", CommandSender.class, java.util.concurrent.CompletionStage.class, String.class);
        method.setAccessible(true); method.invoke(new CommandLoader(plugin), sender, receipt, "&cUpdated activations");
        assertNull(reply.get()); verifyNoInteractions(sender);
        if (failure) receipt.completeExceptionally(new IllegalStateException("private fixture detail")); else receipt.complete(null);
        assertNotNull(reply.get()); verifyNoInteractions(sender); reply.get().run();
        verify(sender).sendMessage(contains(failure ? "could not be confirmed" : "Updated activations"));
        verify(sender, never()).sendMessage(contains(failure ? "Updated activations" : "could not be confirmed"));
        verify(sender, never()).sendMessage(contains("private fixture detail"));
    }
}
