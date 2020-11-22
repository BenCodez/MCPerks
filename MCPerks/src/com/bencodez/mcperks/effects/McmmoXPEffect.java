/*
 *
 */
package com.bencodez.mcperks.effects;

import com.bencodez.mcperks.perk.Effect;
import com.bencodez.mcperks.perk.Perk;

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
	public double increaseXP(float ammount) {
		return (float) (ammount + ammount * percentIncrease / 100.0F);
	}
}
