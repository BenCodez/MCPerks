/*
 *
 */
package com.Ben12345rocks.MCPerks.Effects;

import com.Ben12345rocks.MCPerks.Perk.Effect;
import com.Ben12345rocks.MCPerks.Perk.Perk;

// TODO: Auto-generated Javadoc
/**
 * The Class DoubleExperienceEffect.
 */
public class DoubleExperienceEffect {

	/** The percent increase. */
	private int percentIncrease;

	/**
	 * Instantiates a new double experience effect.
	 *
	 * @param timedPerk
	 *            the timed perk
	 */
	public DoubleExperienceEffect(Perk timedPerk) {
		percentIncrease = timedPerk.getIncreasePercent(Effect.DoubleExperience);
	}

	/**
	 * Increase experience.
	 *
	 * @param ammount
	 *            the ammount
	 * @return the int
	 */
	public int increaseExperience(int amount) {
		return amount * percentIncrease / 100;
	}
}
