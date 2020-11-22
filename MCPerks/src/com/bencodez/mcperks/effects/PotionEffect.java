package com.bencodez.mcperks.effects;

import java.util.ArrayList;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import com.bencodez.mcperks.MCPerksMain;

public class PotionEffect {

	public void giveEffect(String potion, int duration, int amplifier, ArrayList<Player> players) {
		try {
			PotionEffectType potionEffect = PotionEffectType.getByName(potion);
			final org.bukkit.potion.PotionEffect effect = new org.bukkit.potion.PotionEffect(potionEffect,
					(duration * 20), amplifier, true, false);
			Bukkit.getScheduler().runTask(MCPerksMain.plugin, new Runnable() {

				@Override
				public void run() {
					for (Player player : players) {
						player.addPotionEffect(effect);
					}
				}
			});

			// Main.plugin.debug(potion + " " + duration + " " + amplifier);
		} catch (Exception ex) {
			MCPerksMain.plugin.debug("Error occoured giving potion effect: " + ex.getMessage());
		}
	}
}
