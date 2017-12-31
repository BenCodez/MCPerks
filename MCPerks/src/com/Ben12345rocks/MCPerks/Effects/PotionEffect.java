package com.Ben12345rocks.MCPerks.Effects;

import java.util.ArrayList;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import com.Ben12345rocks.MCPerks.Main;

public class PotionEffect {

	public void giveEffect(String potion, int duration, int amplifier, ArrayList<Player> players) {
		try {
			PotionEffectType potionEffect = PotionEffectType.getByName(potion);
			final org.bukkit.potion.PotionEffect effect = new org.bukkit.potion.PotionEffect(potionEffect,
					(duration * 20), amplifier);
			Bukkit.getScheduler().runTask(Main.plugin, new Runnable() {

				@Override
				public void run() {
					for (Player player : players) {
						player.addPotionEffect(effect);
					}
				}
			});

			// Main.plugin.debug(potion + " " + duration + " " + amplifier);
		} catch (Exception ex) {
			Main.plugin.debug("Error occoured giving potion effect: " + ex.getMessage());
		}
	}
}
