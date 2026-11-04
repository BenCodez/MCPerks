package com.bencodez.mcperks.tests.listeners.compatability;
import com.bencodez.mcperks.listeners.compatability.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.effects.McmmoXPEffect;
import com.bencodez.mcperks.perk.Effect;
import com.bencodez.mcperks.perk.Perk;
import com.bencodez.mcperks.perk.PerkHandler;
import com.gmail.nossr50.events.experience.McMMOPlayerXpGainEvent;

class McMMOEventsTest {
    @Test
    void expiredBankGrantIsSkippedWhileValidGrantStillBoostsXp() {
        MCPerksMain plugin = mock(MCPerksMain.class);
        PerkHandler handler = mock(PerkHandler.class);
        Player player = mock(Player.class);
        World world = mock(World.class);
        UUID owner = UUID.randomUUID();
        when(plugin.getPerkHandler()).thenReturn(handler);
        when(player.getUniqueId()).thenReturn(owner);
        when(player.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("world");
        when(handler.effectActive(Effect.McmmoXP, owner.toString(), "world")).thenReturn(true);

        Perk expired = perk(owner, 100.0);
        Perk valid = perk(owner, 50.0);
        when(handler.getActivePerks()).thenReturn(new CopyOnWriteArrayList<>(List.of(expired, valid)));
        when(handler.effectAllowed(expired, Effect.McmmoXP, owner.toString(), "world")).thenReturn(false);
        when(handler.effectAllowed(valid, Effect.McmmoXP, owner.toString(), "world")).thenReturn(true);

        McMMOPlayerXpGainEvent event = mock(McMMOPlayerXpGainEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getSkill()).thenReturn(null);
        when(event.getXpGained()).thenReturn(10);
        doAnswer(invocation -> {
            assertEquals(15, invocation.getArgument(0, Integer.class));
            return null;
        }).when(event).setXpGained(anyInt());

        new McMMOEvents(plugin).onMcMMOPlayerXpGainEvent(event);

        verify(event).setXpGained(15);
        verify(handler).effectAllowed(expired, Effect.McmmoXP, owner.toString(), "world");
        verify(handler).effectAllowed(valid, Effect.McmmoXP, owner.toString(), "world");
    }

    private static Perk perk(UUID owner, double increase) {
        Perk perk = mock(Perk.class);
        when(perk.getEffects()).thenReturn(new ArrayList<>(List.of(Effect.McmmoXP)));
        when(perk.getEffectedPlayers()).thenReturn(new ArrayList<>(List.of(owner.toString())));
        when(perk.isNotInDisabledWorld("world")).thenReturn(true);
        when(perk.getMcmmoSkills()).thenReturn(new ArrayList<>());
        when(perk.getIncreasePercent(Effect.McmmoXP)).thenReturn(increase);
        return perk;
    }
}
