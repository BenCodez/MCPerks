/*
 *
 */
package com.bencodez.mcperks.effects;

import org.bukkit.block.Block;

import com.bencodez.mcperks.perk.Effect;
import com.bencodez.mcperks.perk.Perk;

// TODO: Auto-generated Javadoc
/**
 * The Class FortuneEffect.
 */
public class FortuneEffect {

	/** The percent increase. */
	private int percentIncrease;

	private Perk perk;

	/**
	 * Instantiates a new fortune effect.
	 *
	 * @param perk
	 *            the perk
	 */
	public FortuneEffect(Perk perk) {
		this.perk = perk;
		percentIncrease = (int) perk.getIncreasePercent(Effect.Fortune);
	}

	/**
	 * Increase drops.
	 *
	 * @param ammount
	 *            the ammount
	 * @return the int
	 */
	public int increaseDrops(int ammount) {
		return ammount * percentIncrease / 100;
	}

	/**
	 * Checks if is white listed.
	 *
	 * @param b
	 *            the b
	 * @return true, if is white listed
	 */
	public boolean isWhiteListed(Block b) {
		return perk.getBlocks().contains(b.getType());
	}
}
