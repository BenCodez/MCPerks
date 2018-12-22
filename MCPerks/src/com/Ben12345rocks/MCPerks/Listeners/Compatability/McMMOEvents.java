package com.Ben12345rocks.MCPerks.Listeners.Compatability;

import java.util.ArrayList;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import com.Ben12345rocks.MCPerks.Main;
import com.Ben12345rocks.MCPerks.Effects.McmmoXPEffect;
import com.Ben12345rocks.MCPerks.Perk.Effect;
import com.Ben12345rocks.MCPerks.Perk.Perk;
import com.gmail.nossr50.events.experience.McMMOPlayerXpGainEvent;

public class McMMOEvents implements Listener {

	private Main plugin;

	public McMMOEvents(Main plugin) {
		this.plugin = plugin;
	}

	@EventHandler
	public void onMcMMOPlayerXpGainEvent(McMMOPlayerXpGainEvent event) {
		for (Perk active : plugin.getPerkHandler().getActivePerks()) {
			if (active.getEffects().contains(Effect.McmmoXP)
					&& active.getEffectedPlayers().contains(event.getPlayer().getUniqueId().toString())) {
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
					event.setRawXpGained(me.increaseXP(event.getRawXpGained()));
				}
			}
		}

	}
}
