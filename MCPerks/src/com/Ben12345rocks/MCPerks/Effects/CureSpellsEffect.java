/*
 *
 */
package com.Ben12345rocks.MCPerks.Effects;

import java.util.ArrayList;

import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

// TODO: Auto-generated Javadoc
/**
 * The Class CureSpellsEffect.
 */
public class CureSpellsEffect {

	/**
	 * Clear bad effects.
	 */
	public void clearBadEffects(ArrayList<Player> players) {
		for (Player player : players) {
			if (player.hasPotionEffect(PotionEffectType.BLINDNESS)) {
				player.removePotionEffect(PotionEffectType.BLINDNESS);
			}

			if (player.hasPotionEffect(PotionEffectType.CONFUSION)) {
				player.removePotionEffect(PotionEffectType.CONFUSION);
			}

			if (player.hasPotionEffect(PotionEffectType.HARM)) {
				player.removePotionEffect(PotionEffectType.HARM);
			}

			if (player.hasPotionEffect(PotionEffectType.POISON)) {
				player.removePotionEffect(PotionEffectType.POISON);
			}

			if (player.hasPotionEffect(PotionEffectType.SLOW)) {
				player.removePotionEffect(PotionEffectType.SLOW);
			}

			if (player.hasPotionEffect(PotionEffectType.SLOW_DIGGING)) {
				player.removePotionEffect(PotionEffectType.SLOW_DIGGING);
			}

			if (player.hasPotionEffect(PotionEffectType.WEAKNESS)) {
				player.removePotionEffect(PotionEffectType.WEAKNESS);
			}

			if (player.hasPotionEffect(PotionEffectType.WITHER)) {
				player.removePotionEffect(PotionEffectType.WITHER);
			}
		}

	}
}
