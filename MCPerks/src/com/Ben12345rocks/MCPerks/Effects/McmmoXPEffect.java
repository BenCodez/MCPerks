/*
 *
 */
package com.Ben12345rocks.MCPerks.Effects;

import com.Ben12345rocks.MCPerks.Objects.Perk;

// TODO: Auto-generated Javadoc
/**
 * The Class McmmoXPEffect.
 */
public class McmmoXPEffect {

	/** The percent increase. */
	private int percentIncrease;

	/**
	 * Instantiates a new mcmmo XP effect.
	 *
	 * @param perk
	 *            the perk
	 */
	public McmmoXPEffect(Perk perk) {
		percentIncrease = perk.getIncreasePercent();
	}

	/**
	 * Increase XP.
	 *
	 * @param ammount
	 *            the ammount
	 * @return the float
	 */
	public float increaseXP(float ammount) {
		return ammount + ammount * percentIncrease / 100.0F;
	}
}
