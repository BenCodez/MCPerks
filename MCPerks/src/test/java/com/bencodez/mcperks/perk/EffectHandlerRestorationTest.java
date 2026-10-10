package com.bencodez.mcperks.perk;

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
        when(perk.getEffects()).thenReturn(new ArrayList<>(List.of(Effect.Fly, Effect.Rewards, Effect.Commands, Effect.GlobalCommands)));
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
                handler.activate(perk, user, true);
                verify(utils, times(1)).executeConsoleCommands(any(Player.class), any(ArrayList.class), any(HashMap.class), eq(false));
                verify(utils, times(1)).executeConsoleCommands(any(ArrayList.class), any(HashMap.class), eq(false));
                assertEquals(1, rewards.constructed().size());
            }
        }
    }
}
