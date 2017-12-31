/*
 *
 */
package com.Ben12345rocks.MCPerks.Effects;

import java.util.ArrayList;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.Ben12345rocks.MCPerks.Main;

// TODO: Auto-generated Javadoc
/**
 * The Class HealAllEffect.
 */
public class HealAllEffect {

	/**
	 * Heal players.
	 */
	public void healPlayers(final ArrayList<Player> players) {
		Bukkit.getScheduler().runTask(Main.plugin, new Runnable() {

			@SuppressWarnings("deprecation")
			@Override
			public void run() {
				for (Player player : players) {
					player.setHealth(player.getMaxHealth());
				}
			}
		});

	}
}
