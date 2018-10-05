/*
 *
 */
package com.Ben12345rocks.MCPerks.Effects;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.entity.Blaze;
import org.bukkit.entity.CaveSpider;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.MagmaCube;
import org.bukkit.entity.MushroomCow;
import org.bukkit.entity.Ocelot;
import org.bukkit.entity.Pig;
import org.bukkit.entity.PigZombie;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Spider;
import org.bukkit.entity.Squid;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.entity.Wolf;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import com.Ben12345rocks.MCPerks.Perk.Effect;
import com.Ben12345rocks.MCPerks.Perk.Perk;

// TODO: Auto-generated Javadoc
/**
 * The Class HeadDropperEffect.
 */
public class HeadDropperEffect {

	/** The percent increase. */
	private int percentIncrease;

	/** The entity. */
	private LivingEntity entity;

	/** The skulls. */
	private ArrayList<ItemStack> skulls = new ArrayList<ItemStack>();

	/** The skull. */
	private ItemStack skull;

	/** The random. */
	private double random;

	/**
	 * Instantiates a new head dropper effect.
	 *
	 * @param timedPerk
	 *            the timed perk
	 * @param e
	 *            the e
	 */
	public HeadDropperEffect(Perk timedPerk, LivingEntity e) {
		skull = new ItemStack(Material.SKELETON_SKULL);
		random = Math.random();
		percentIncrease = timedPerk.getIncreasePercent(Effect.HeadDropper);
		entity = e;
		checkEntity();
	}

	/**
	 * Adds the extra skulls.
	 *
	 * @return the list
	 */
	public List<ItemStack> addExtraSkulls() {
		if (random * 100.0D <= percentIncrease) {
			skulls.add(skull);
		}

		return skulls;
	}

	/**
	 * Check entity.
	 */
	private void checkEntity() {
		if (entity instanceof Player) {
			skull = new ItemStack(Material.PLAYER_HEAD);
			skull.setItemMeta(getSpecialSkull(entity.getName(), skull));
		} else if (entity instanceof Creeper) {
			skull = new ItemStack(Material.CREEPER_HEAD);
			skull.setItemMeta(getSpecialSkull(entity.getName(), skull));
		} else if (entity instanceof Ocelot) {
			skull = new ItemStack(Material.PLAYER_HEAD);
			skull.setItemMeta(getSpecialSkull("MHF_Ocelot", skull));
		} else if (entity instanceof MushroomCow) {
			skull = new ItemStack(Material.PLAYER_HEAD);
			skull.setItemMeta(getSpecialSkull("MHF_MushroomCow", skull));
		} else if (entity instanceof IronGolem) {
			skull = new ItemStack(Material.PLAYER_HEAD);
			skull.setItemMeta(getSpecialSkull("MHF_Golem", skull));
		} else if (entity instanceof Villager) {
			skull = new ItemStack(Material.PLAYER_HEAD);
			skull.setItemMeta(getSpecialSkull("MHF_Villager", skull));
		} else if (entity instanceof Squid) {
			skull = new ItemStack(Material.PLAYER_HEAD);
			skull.setItemMeta(getSpecialSkull("MHF_Squid", skull));
		} else if (entity instanceof Cow) {
			skull = new ItemStack(Material.PLAYER_HEAD);
			skull.setItemMeta(getSpecialSkull("MHF_Cow", skull));
		} else if (entity instanceof Sheep) {
			skull = new ItemStack(Material.PLAYER_HEAD);
			skull.setItemMeta(getSpecialSkull("MHF_Sheep", skull));
		} else if (entity instanceof Pig) {
			skull = new ItemStack(Material.PLAYER_HEAD);
			skull.setItemMeta(getSpecialSkull("MHF_Pig", skull));
		} else if (entity instanceof Chicken) {
			skull = new ItemStack(Material.PLAYER_HEAD);
			skull.setItemMeta(getSpecialSkull("MHF_Chicken", skull));
		} else if (entity instanceof Spider) {
			skull = new ItemStack(Material.PLAYER_HEAD);
			skull.setItemMeta(getSpecialSkull("MHF_Spider", skull));
		} else if (entity instanceof Slime) {
			skull = new ItemStack(Material.PLAYER_HEAD);
			skull.setItemMeta(getSpecialSkull("MHF_Slime", skull));
		} else if (entity instanceof MagmaCube) {
			skull = new ItemStack(Material.PLAYER_HEAD);
			skull.setItemMeta(getSpecialSkull("MHF_LavaSlime", skull));
		} else if (entity instanceof Enderman) {
			skull = new ItemStack(Material.PLAYER_HEAD);
			skull.setItemMeta(getSpecialSkull("MHF_Enderman", skull));
		} else if (entity instanceof PigZombie) {
			skull = new ItemStack(Material.PLAYER_HEAD);
			skull.setItemMeta(getSpecialSkull("MHF_PigZombie", skull));
		} else if (entity instanceof Ghast) {
			skull = new ItemStack(Material.PLAYER_HEAD);
			skull.setItemMeta(getSpecialSkull("MHF_Ghast", skull));
		} else if (entity instanceof CaveSpider) {
			skull = new ItemStack(Material.PLAYER_HEAD);
			skull.setItemMeta(getSpecialSkull("MHF_CaveSpider", skull));
		} else if (entity instanceof Blaze) {
			skull = new ItemStack(Material.PLAYER_HEAD);
			skull.setItemMeta(getSpecialSkull("MHF_Blaze", skull));
		} else if (entity instanceof Wolf) {
			skull = new ItemStack(Material.PLAYER_HEAD);
			skull.setItemMeta(getSpecialSkull("MHF_Wolf", skull));
		} else if (entity instanceof Zombie) {
			skull = new ItemStack(Material.ZOMBIE_HEAD);
		} else if (entity instanceof WitherSkeleton) {
			skull = new ItemStack(Material.WITHER_SKELETON_SKULL);
		} else if (entity instanceof Skeleton) {
			skull = new ItemStack(Material.SKELETON_SKULL);
		}

	}

	/**
	 * Gets the special skull.
	 *
	 * @param name
	 *            the name
	 * @param item
	 *            the item
	 * @return the special skull
	 */
	@SuppressWarnings("deprecation")
	public SkullMeta getSpecialSkull(String name, ItemStack item) {
		SkullMeta sm = (SkullMeta) item.getItemMeta();
		ArrayList<String> lore = new ArrayList<String>();
		sm.setOwner(name);
		lore.add(name + "\'s Coconut");
		sm.setLore(lore);
		return sm;
	}
}
