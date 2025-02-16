/*
 *
 */
package com.bencodez.mcperks.effects;

import com.bencodez.mcperks.perk.Effect;
import com.bencodez.mcperks.perk.Perk;

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
		percentIncrease = (int) timedPerk.getIncreasePercent(Effect.DoubleExperience);
	}

	/**
	 * Increase experience.
	 *
	 * @param amount
	 *            the ammount
	 * @return the int
	 */
	public int increaseExperience(int amount) {
		return amount * percentIncrease / 100;
	}
}
