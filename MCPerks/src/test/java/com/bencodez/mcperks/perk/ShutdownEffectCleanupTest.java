package com.bencodez.mcperks.perk;

import com.bencodez.mcperks.MCPerksMain;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShutdownEffectCleanupTest {
    @Test void shutdownFlightRemovalRunsOnOwnerWithoutSchedulingDisabledPluginWork() {
        MCPerksMain plugin = mock(MCPerksMain.class);
        Perk perk = mock(Perk.class);
        Player player = mock(Player.class);
        World world = mock(World.class);
        UUID owner = UUID.randomUUID();
        when(player.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("world");
        when(perk.getFlyWorlds()).thenReturn(new ArrayList<>(List.of("world")));
        when(plugin.isEnabled()).thenReturn(false);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(() -> Bukkit.getPlayer(owner)).thenReturn(player);
            Effect.Fly.removeEffectForShutdown(plugin, perk, new ArrayList<>(List.of(owner.toString())));
            verify(player).setAllowFlight(false);
            verify(player).setFlying(false);
            verify(player).setFallDistance(-100000);
            bukkit.verify(Bukkit::getScheduler, never());
        }
    }

    @Test void shutdownFlightRemovalPreservesBypassAndRejectsOffOwnerAccess() {
        MCPerksMain plugin = mock(MCPerksMain.class);
        Perk perk = mock(Perk.class);
        Player player = mock(Player.class);
        World world = mock(World.class);
        UUID owner = UUID.randomUUID();
        when(player.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("world");
        when(perk.getFlyWorlds()).thenReturn(new ArrayList<>(List.of("world")));
        when(player.hasPermission("MCPerks.ServerFly.Bypass")).thenReturn(true);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(() -> Bukkit.getPlayer(owner)).thenReturn(player);
            Effect.Fly.removeEffectForShutdown(plugin, perk, new ArrayList<>(List.of(owner.toString())));
            verify(player, never()).setAllowFlight(anyBoolean());
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            assertThrows(IllegalStateException.class, () -> Effect.Fly.removeEffectForShutdown(
                    plugin, perk, new ArrayList<>(List.of(owner.toString()))));
            bukkit.verify(Bukkit::getScheduler, never());
        }
    }
    @Test void shutdownCommandsRenderAndDispatchOnOwnerWithoutDisabledPluginScheduling() {
        MCPerksMain plugin = mock(MCPerksMain.class);
        Perk perk = mock(Perk.class);
        Player player = mock(Player.class);
        org.bukkit.Server server = mock(org.bukkit.Server.class);
        org.bukkit.command.ConsoleCommandSender console = mock(org.bukkit.command.ConsoleCommandSender.class);
        UUID owner = UUID.randomUUID();
        when(player.getName()).thenReturn("Tester");
        var configured = new ArrayList<>(List.of("/cleanup %player%"));
        var rendered = new ArrayList<>(List.of("/cleanup Tester"));
        when(perk.getDisableCommands()).thenReturn(configured);
        try (var bukkit = mockStatic(Bukkit.class);
             var placeholders = mockStatic(com.bencodez.advancedcore.api.messages.PlaceholderUtils.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(() -> Bukkit.getPlayer(owner)).thenReturn(player);
            bukkit.when(Bukkit::getServer).thenReturn(server);
            bukkit.when(Bukkit::getConsoleSender).thenReturn(console);
            placeholders.when(() -> com.bencodez.advancedcore.api.messages.PlaceholderUtils
                    .replaceJavascriptOnly(player, configured)).thenReturn(configured);
            placeholders.when(() -> com.bencodez.advancedcore.api.messages.PlaceholderUtils
                    .replacePlaceHolder(eq(configured), any(java.util.HashMap.class))).thenReturn(rendered);
            placeholders.when(() -> com.bencodez.advancedcore.api.messages.PlaceholderUtils
                    .replacePlaceHolders(rendered, player)).thenReturn(rendered);
            Effect.Commands.removeEffectForShutdown(plugin, perk, new ArrayList<>(List.of(owner.toString())));
            verify(server).dispatchCommand(console, "cleanup Tester");
            bukkit.verify(Bukkit::getScheduler, never());
        }
    }

    @Test void bossBarCleanupFailureDoesNotAbortDetachOrRepeatCleanup() throws Exception {
        MCPerksMain plugin = mock(MCPerksMain.class);
        PerkHandler handler = mock(PerkHandler.class);
        Perk perk = mock(Perk.class, CALLS_REAL_METHODS);
        var bossBar = mock(com.bencodez.advancedcore.api.misc.effects.BossBar.class);
        var active = new java.util.concurrent.CopyOnWriteArrayList<Perk>(List.of(perk));
        when(plugin.getPerkHandler()).thenReturn(handler);
        when(handler.getActivePerks()).thenReturn(active);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        var pluginField = Perk.class.getDeclaredField("plugin");
        pluginField.setAccessible(true);
        pluginField.set(perk, plugin);
        perk.setActive(true);
        perk.setBossBar(bossBar);
        doReturn(new ArrayList<Effect>()).when(perk).getEffects();
        doThrow(new IllegalStateException("fixture hide failure")).when(bossBar).hide();
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            assertTrue(perk.detachForShutdown());
            assertFalse(perk.isActive());
            assertNull(perk.getBossBar());
            assertTrue(active.isEmpty());
            assertFalse(perk.detachForShutdown());
            verify(bossBar).hide();
        }
    }

}
