package com.bencodez.mcperks.effects;

import java.util.ArrayList;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import com.bencodez.mcperks.MCPerksMain;

public class PotionEffect {

	/**
	 * Gives a potion effect to the specified players.
	 *
	 * @param potion    the potion effect name
	 * @param duration  duration in seconds, or -1 for an infinite duration
	 * @param amplifier the effect amplifier
	 * @param players   the players receiving the effect
	 */
	@SuppressWarnings("deprecation")
	public void giveEffect(String potion, int duration, int amplifier, ArrayList<Player> players) {
		try {
			PotionEffectType potionEffect = PotionEffectType.getByName(potion);
			if (potionEffect == null) {
				MCPerksMain.plugin.debug("Unknown potion effect: " + potion);
				return;
			}

			if (duration < -1) {
				MCPerksMain.plugin.debug("Invalid potion duration: " + duration
						+ ". Use -1 for infinite or a non-negative duration in seconds.");
				return;
			}

			// Keep infinite duration unchanged; convert finite seconds to ticks.
			// multiplyExact prevents large durations from silently overflowing.
			int durationTicks = duration == -1
					? org.bukkit.potion.PotionEffect.INFINITE_DURATION
					: Math.multiplyExact(duration, 20);

			final org.bukkit.potion.PotionEffect effect = new org.bukkit.potion.PotionEffect(
					potionEffect, durationTicks, amplifier, true, false);

			Bukkit.getScheduler().runTask(MCPerksMain.plugin, new Runnable() {

				@Override
				public void run() {
					for (Player player : players) {
						player.addPotionEffect(effect);
					}
				}
			});
		} catch (Exception ex) {
			MCPerksMain.plugin.debug("Error occurred giving potion effect: " + ex.getMessage());
		}
	}
}