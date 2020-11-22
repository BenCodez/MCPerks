package com.bencodez.mcperks.listeners.compatability;

import java.util.ArrayList;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.effects.McmmoXPEffect;
import com.bencodez.mcperks.perk.Effect;
import com.bencodez.mcperks.perk.Perk;
import com.gmail.nossr50.events.experience.McMMOPlayerXpGainEvent;

public class McMMOEvents implements Listener {

	private MCPerksMain plugin;

	public McMMOEvents(MCPerksMain plugin) {
		this.plugin = plugin;
	}

	@SuppressWarnings("deprecation")
	@EventHandler
	public void onMcMMOPlayerXpGainEvent(McMMOPlayerXpGainEvent event) {
		Player player = event.getPlayer();
		if (plugin.getPerkHandler().effectActive(Effect.McmmoXP, player.getUniqueId().toString(),
				player.getWorld().getName())) {
			for (Perk active : plugin.getPerkHandler().getActivePerks()) {
				if (active.getEffects().contains(Effect.McmmoXP)
						&& active.getEffectedPlayers().contains(player.getUniqueId().toString())) {
					if (active.isNotInDisabledWorld(player.getWorld().getName())) {
						ArrayList<String> skills = active.getMcmmoSkills();
						boolean boost = false;
						if (skills.isEmpty()) {
							boost = true;
						} else {
							for (String skill : skills) {
								if (event.getSkill().toString().equalsIgnoreCase(skill)) {
									boost = true;
								}
							}
						}

						if (boost) {
							McmmoXPEffect me = new McmmoXPEffect(active);
							event.setXpGained((int) me.increaseXP(event.getXpGained()));

						}
					}
				}
			}
		}

	}
}
