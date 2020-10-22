/*
 *
 */
package com.Ben12345rocks.MCPerks.Effects;

import com.Ben12345rocks.MCPerks.Perk.Effect;
import com.Ben12345rocks.MCPerks.Perk.Perk;

// TODO: Auto-generated Javadoc
/**
 * The Class McmmoXPEffect.
 */
public class McmmoXPEffect {

	/** The percent increase. */
	private double percentIncrease;

	/**
	 * Instantiates a new mcmmo XP effect.
	 *
	 * @param perk
	 *            the perk
	 */
	public McmmoXPEffect(Perk perk) {
		percentIncrease = perk.getIncreasePercent(Effect.McmmoXP);
	}

	/**
	 * Increase XP.
	 *
	 * @param ammount
	 *            the ammount
	 * @return the float
	 */
	public float increaseXP(float ammount) {
		return (float) (ammount + ammount * (percentIncrease/100));
	}
}
