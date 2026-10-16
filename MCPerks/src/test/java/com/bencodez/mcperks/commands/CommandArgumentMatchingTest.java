package com.bencodez.mcperks.commands;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import com.bencodez.advancedcore.api.command.CommandHandler;
import com.bencodez.mcperks.MCPerksMain;

/** Regression tests for the production SimpleAPI command matcher path. */
class CommandArgumentMatchingTest {

    @Test
    void featureArgumentMatchersReachHandlersThroughRunCommand() throws Exception {
        MCPerksMain plugin = plugin();
        CommandLoader loader = new CommandLoader(plugin);
        var register = CommandLoader.class.getDeclaredMethod("registerFeatureArgumentMatchers");
        register.setAccessible(true);
        register.invoke(loader);

        AtomicReference<String[]> received = new AtomicReference<>();
        CommandHandler command = command(plugin,
                new String[] { "SetUsageCharges", "NativeOwner", "(effect)", "2" }, received);
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("MCPerks.SetUsageCharges")).thenReturn(true);

        assertTrue(command.runCommand(sender,
                new String[] { "SetUsageCharges", "NativeOwner", "AutoBlockRefill", "2" }));
        assertArrayEquals(new String[] { "SetUsageCharges", "NativeOwner", "AutoBlockRefill", "2" },
                received.get());
    }

    @Test
    void queueAndBankIdentifiersAndNumbersUseRegisteredMatchers() throws Exception {
        MCPerksMain plugin = plugin();
        CommandLoader loader = new CommandLoader(plugin);
        var register = CommandLoader.class.getDeclaredMethod("registerFeatureArgumentMatchers");
        register.setAccessible(true);
        register.invoke(loader);

        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("MCPerks.QueueMove")).thenReturn(true);
        AtomicReference<String[]> received = new AtomicReference<>();
        assertTrue(command(plugin, new String[] { "QueueMove", "(id)", "(position)" }, received)
                .runCommand(sender, new String[] { "QueueMove", "entry", "12" }));
        assertArrayEquals(new String[] { "QueueMove", "entry", "12" }, received.get());
        received.set(null);
        when(sender.hasPermission("MCPerks.SetBankedTime")).thenReturn(true);
        assertTrue(command(plugin, new String[] { "SetBankedTime", "NativeOwner", "LiveEffects", "(seconds)" }, received)
                .runCommand(sender, new String[] { "SetBankedTime", "NativeOwner", "LiveEffects", "600" }));
        assertArrayEquals(new String[] { "SetBankedTime", "NativeOwner", "LiveEffects", "600" }, received.get());
    }

    @Test
    void wrongArityAndPermissionStillRejectDispatch() throws Exception {
        MCPerksMain plugin = plugin();
        CommandLoader loader = new CommandLoader(plugin);
        var register = CommandLoader.class.getDeclaredMethod("registerFeatureArgumentMatchers");
        register.setAccessible(true);
        register.invoke(loader);

        AtomicReference<String[]> received = new AtomicReference<>();
        CommandHandler command = command(plugin, new String[] { "AddUsageCharges", "NativeOwner", "(effect)", "2" },
                received);
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("MCPerks.AddUsageCharges")).thenReturn(false);

        // The command API reports a handled permission denial as true, but the
        // handler must never receive the invocation.
        assertTrue(command.runCommand(sender,
                new String[] { "AddUsageCharges", "NativeOwner", "VeinMining", "2" }));
        when(sender.hasPermission("MCPerks.AddUsageCharges")).thenReturn(true);
        assertFalse(command.runCommand(sender, new String[] { "AddUsageCharges", "NativeOwner", "VeinMining" }));
        assertFalse(command.runCommand(sender,
                new String[] { "AddUsageCharges", "NativeOwner", "VeinMining", "2", "extra" }));
        assertFalse(command.runCommand(sender,
                new String[] { "OtherCommand", "NativeOwner", "VeinMining", "2" }));
        assertEquals(null, received.get());
    }

    private static CommandHandler command(MCPerksMain plugin, String[] args, AtomicReference<String[]> received) {
        return new CommandHandler(plugin, args, "MCPerks." + args[0], "test") {
            @Override
            public void execute(CommandSender sender, String[] actual) {
                received.set(actual.clone());
            }
        };
    }

    private static MCPerksMain plugin() {
        MCPerksMain plugin = mock(MCPerksMain.class);
        var options = mock(com.bencodez.advancedcore.AdvancedCoreConfigOptions.class);
        when(options.getFormatNoPerms()).thenReturn("");
        when(plugin.getOptions()).thenReturn(options);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        var scheduler = mock(com.bencodez.simpleapi.scheduler.BukkitScheduler.class);
        doAnswer(invocation -> { invocation.<Runnable>getArgument(1).run(); return null; })
                .when(scheduler).runTaskAsynchronously(org.mockito.ArgumentMatchers.eq(plugin),
                        org.mockito.ArgumentMatchers.any(Runnable.class));
        when(plugin.getBukkitScheduler()).thenReturn(scheduler);
        return plugin;
    }
}
