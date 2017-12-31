/*
 *
 */
package com.Ben12345rocks.MCPerks.Effects;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.SkullType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.PigZombie;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;

import com.Ben12345rocks.MCPerks.Objects.Perk;
import com.Ben12345rocks.MCPerks.Utils.Skeleton.SkeletonHandler;

// TODO: Auto-generated Javadoc
/**
 * The Class MobDropEffect.
 */
public class MobDropEffect {

	/** The custom items. */
	private List<ItemStack> customItems;

	/** The percent increase. */
	private int percentIncrease;

	private Perk perk;

	/**
	 * Instantiates a new mob drop effect.
	 *
	 * @param perk
	 *            the timed perk
	 */
	public MobDropEffect(Perk perk) {
		this.perk = perk;
		customItems = new ArrayList<ItemStack>();
		percentIncrease = perk.getIncreasePercent();
	}

	/**
	 * Double common drop.
	 *
	 * @param list
	 *            the list
	 * @return the list
	 */
	public List<?> doubleCommonDrop(List<?> list) {
		return list;
	}

	/**
	 * Insert custom items.
	 *
	 * @return the list
	 */
	public List<ItemStack> insertCustomItems() {
		ArrayList<ItemStack> items = perk.getSpecialDrops();
		double random = Math.random();

		for (int i = 0; i < customItems.size(); ++i) {
			random = Math.random();
			if (random * 100.0D <= percentIncrease) {
				items.add(customItems.get(i));
			}
		}

		return items;
	}

	/**
	 * Insert rare items.
	 *
	 * @param e
	 *            the e
	 * @return the array list
	 */
	public ArrayList<ItemStack> insertRareItems(LivingEntity e) {
		ArrayList<ItemStack> items = new ArrayList<ItemStack>();
		double ran = Math.random();
		if (e instanceof Rabbit) {
			if (ran * 100.0D <= 10.0D) {
				items.add(new ItemStack(Material.RABBIT_FOOT));
			}
		} else if (SkeletonHandler.getInstance().isWitherSkeleton(e)) {
			if (ran * 100.0D <= 10.0D) {
				ItemStack skull = new ItemStack(Material.SKULL_ITEM);
				skull.setDurability((short) SkullType.WITHER.ordinal());
				items.add(skull);
			}
		} else if (e instanceof Zombie) {
			ran = Math.random();
			if (ran * 100.0D <= 10.0D) {
				items.add(new ItemStack(Material.IRON_INGOT));
			}

			ran = Math.random();
			if (ran * 100.0D <= 10.0D) {
				items.add(new ItemStack(Material.POTATO_ITEM));
			}

			ran = Math.random();
			if (ran * 100.0D <= 10.0D) {
				items.add(new ItemStack(Material.CARROT_ITEM));
			}
		} else if (e instanceof PigZombie && ran * 100.0D <= 10.0D) {
			items.add(new ItemStack(Material.GOLD_INGOT));
		}

		return items;
	}
}
