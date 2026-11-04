package com.bencodez.mcperks.tests.perk;
import com.bencodez.mcperks.perk.*;
import com.bencodez.mcperks.perk.Effect;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.userapi.MCPerksUser;
import com.bencodez.advancedcore.api.rewards.RewardBuilder;
import com.bencodez.advancedcore.api.misc.MiscUtils;

class EffectHandlerRestorationTest {
    @Test void restorationSkipsOneShotEffectsButAppliesPersistentAudienceEffect() {
        UUID id = UUID.randomUUID(); Player player = mock(Player.class); Server server = mock(Server.class);
        when(player.getUniqueId()).thenReturn(id); when(player.getName()).thenReturn("owner");
        var world = mock(org.bukkit.World.class); when(world.getName()).thenReturn("world"); when(player.getWorld()).thenReturn(world);
        var scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
        doAnswer(inv -> { inv.getArgument(1, Runnable.class).run(); return null; }).when(scheduler).runTask(any(), any(Runnable.class));
        MCPerksMain plugin = mock(MCPerksMain.class); MCPerksMain.plugin = plugin;
        var users = mock(com.bencodez.mcperks.userapi.UserManager.class);
        when(plugin.getMcperksUserManager()).thenReturn(users); when(users.getMCPerksUser(player)).thenReturn(mock(MCPerksUser.class));
        Perk perk = mock(Perk.class); MCPerksUser user = mock(MCPerksUser.class);
        when(perk.getEffects()).thenReturn(new ArrayList<>(List.of(Effect.Fly, Effect.Rewards, Effect.Commands, Effect.GlobalCommands, Effect.CureHunger, Effect.CureSpells, Effect.HealAll)));
        when(perk.getEffectedPlayers()).thenReturn(new ArrayList<>(List.of(id.toString())));
        when(perk.getFlyWorlds()).thenReturn(new ArrayList<>(List.of("world"))); when(perk.getPerk()).thenReturn("restore");
        when(perk.getActivater()).thenReturn(user); when(user.getPlayerName()).thenReturn("owner");
        when(perk.getCommands()).thenReturn(new ArrayList<>(List.of("say %player%"))); when(perk.getGlobalCommands()).thenReturn(new ArrayList<>(List.of("say global")));
        try (var bukkit = mockStatic(Bukkit.class); var misc = mockStatic(MiscUtils.class);
             var rewards = mockConstruction(RewardBuilder.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(server); bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
            var utils = mock(MiscUtils.class); misc.when(MiscUtils::getInstance).thenReturn(utils);
            var config = mock(com.bencodez.mcperks.configs.ConfigPerks.class);
            try (var configs = mockStatic(com.bencodez.mcperks.configs.ConfigPerks.class)) {
                configs.when(com.bencodez.mcperks.configs.ConfigPerks::getInstance).thenReturn(config);
                when(config.getData("restore")).thenReturn(new YamlConfiguration());
                when(config.getPerkRewardsPath("restore")).thenReturn("Rewards");
                var handler = new EffectHandler(plugin);
                handler.activate(perk, user, false);
                verify(player).setAllowFlight(true);
                verify(utils, never()).executeConsoleCommands(any(Player.class), any(ArrayList.class), any(HashMap.class), eq(false));
                verify(utils, never()).executeConsoleCommands(any(ArrayList.class), any(HashMap.class), eq(false));
                assertTrue(rewards.constructed().isEmpty());
                verify(player, never()).setFoodLevel(anyInt());
                verify(player, never()).setHealth(anyDouble());
                verify(player, never()).removePotionEffect(any());
                when(perk.getEffects()).thenReturn(new ArrayList<>(List.of(Effect.Fly, Effect.Rewards, Effect.Commands, Effect.GlobalCommands)));
                handler.activate(perk, user, true);
                verify(utils, times(1)).executeConsoleCommands(any(Player.class), any(ArrayList.class), any(HashMap.class), eq(false));
                verify(utils, times(1)).executeConsoleCommands(any(ArrayList.class), any(HashMap.class), eq(false));
                assertEquals(1, rewards.constructed().size());
            }
        }
    }
    @Test void normalActivationStillAppliesHungerHealingAndCures() {
        UUID id = UUID.randomUUID(); Player player = mock(Player.class);
        MCPerksMain plugin = mock(MCPerksMain.class); MCPerksMain.plugin = plugin;
        Perk perk = mock(Perk.class); MCPerksUser user = mock(MCPerksUser.class);
        var scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
        doAnswer(inv -> { inv.getArgument(1, Runnable.class).run(); return null; })
                .when(scheduler).runTask(any(), any(Runnable.class));
        var version = mock(com.bencodez.mcperks.version.VersionHandle.class);
        when(plugin.getVersionHandle()).thenReturn(version);
        var health = mock(org.bukkit.attribute.AttributeInstance.class);
        when(player.getAttribute(any())).thenReturn(health); when(health.getValue()).thenReturn(20.0);
        when(perk.getEffects()).thenReturn(new ArrayList<>(List.of(Effect.CureHunger, Effect.HealAll, Effect.CureSpells)));
        when(perk.getEffectedPlayers()).thenReturn(new ArrayList<>(List.of(id.toString())));
        try (var bukkit = mockStatic(Bukkit.class); var misc = mockStatic(MiscUtils.class)) {
            bukkit.when(() -> Bukkit.getRegistry(any())).thenAnswer(inv -> {
                var registry = mock(org.bukkit.Registry.class);
                when(registry.getOrThrow(any())).thenAnswer(key -> mock(org.bukkit.potion.PotionEffectType.class));
                return registry;
            });
            when(player.hasPotionEffect(org.bukkit.potion.PotionEffectType.BLINDNESS)).thenReturn(true);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
            misc.when(MiscUtils::getInstance).thenReturn(mock(MiscUtils.class));
            new EffectHandler(plugin).activate(perk, user, true);
            verify(player).setFoodLevel(20);
            verify(player).setHealth(20.0);
            verify(player).removePotionEffect(org.bukkit.potion.PotionEffectType.BLINDNESS);
        }
    }

}
