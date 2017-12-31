/*
 *
 */
package com.Ben12345rocks.MCPerks.Effects;

import java.util.ArrayList;

import org.bukkit.entity.Player;

// TODO: Auto-generated Javadoc
/**
 * The Class CureHungerEffect.
 */
public class CureHungerEffect {

	/**
	 * Heal players hunger.
	 */
	public void healPlayersHunger(ArrayList<Player> players) {
		for (Player player : players) {
			player.setFoodLevel(20);
		}
	}
}
