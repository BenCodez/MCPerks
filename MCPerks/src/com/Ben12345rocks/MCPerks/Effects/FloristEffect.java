/*
 *
 */
package com.Ben12345rocks.MCPerks.Effects;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

// TODO: Auto-generated Javadoc
/**
 * The Class FloristEffect.
 */
public class FloristEffect {

	/**
	 * Deduct bone meal.
	 *
	 * @param player
	 *            the player
	 */
	@SuppressWarnings("deprecation")
	public void deductBoneMeal(PlayerInteractEvent event) {
		// Main.plugin.debug("Amount: " + event.getItem().getAmount());
		if (event.getItem().getAmount() == 1) {
			Player player = event.getPlayer();
			if (player.getItemInHand().equals(new ItemStack(Material.BONE_MEAL, 1))) {
				player.setItemInHand(new ItemStack(Material.AIR));
			}
			event.getPlayer().updateInventory();
			// Main.plugin.debug("Removed item");
		} else {
			event.getItem().setAmount(event.getItem().getAmount() - 1);
			event.getPlayer().updateInventory();
			// Main.plugin.debug("New Amount: " + event.getItem().getAmount());
		}

	}

	/**
	 * Generate flowers.
	 *
	 * @param loc
	 *            the loc
	 */
	public void generateFlowers(Location loc) {
		int y = loc.getBlockY();
		int x = loc.getBlockX();
		int z = loc.getBlockZ();

		for (int i = x - 2; i <= x + 2; ++i) {
			for (int j = z - 2; j <= z + 2; ++j) {
				if (loc.getWorld().getBlockAt(i, y, j).getType().equals(Material.GRASS)
						&& loc.getWorld().getBlockAt(i, y + 1, z).getType().equals(Material.AIR)
						&& loc.getWorld().getBlockAt(i, y + 2, z).getType().equals(Material.AIR)) {
					pickFlower(new Location(loc.getWorld(), i, y + 1, j));
				}
			}
		}

	}

	/**
	 * Pick flower.
	 *
	 * @param loc
	 *            the loc
	 */
	public void pickFlower(Location loc) {
		if (loc.getBlock().getType().equals(Material.AIR)) {
			double randomNum = Math.random();
			if (randomNum < 0.11D) {
				loc.getBlock().setType(Material.DANDELION);
			} else if (randomNum < 0.22D) {
				loc.getBlock().setType(Material.POPPY);
			} else if (randomNum < 0.25D) {
				loc.getBlock().setType(Material.BLUE_ORCHID);
			} else if (randomNum < 0.28D) {
				loc.getBlock().setType(Material.ALLIUM);
			} else if (randomNum < 0.35D) {
				loc.getBlock().setType(Material.AZURE_BLUET);
			} else if (randomNum < 0.42D) {
				loc.getBlock().setType(Material.RED_TULIP);
			} else if (randomNum < 0.49D) {
				loc.getBlock().setType(Material.ORANGE_TULIP);
			} else if (randomNum < 0.56D) {
				loc.getBlock().setType(Material.WHITE_TULIP);
			} else if (randomNum < 0.63D) {
				loc.getBlock().setType(Material.PINK_TULIP);
			} else if (randomNum < 0.7D) {
				loc.getBlock().setType(Material.OXEYE_DAISY);
			} else if (randomNum < 0.73D) {
				// double tall
				loc.getBlock().setType(Material.PEONY, false);
			} else if (randomNum < 0.78D) {
				// double tall
				loc.getBlock().setType(Material.SUNFLOWER, false);
			} else if (randomNum < 0.83D) {
				// double tall
				loc.getBlock().setType(Material.LILAC, false);
			} else if (randomNum < 0.88D) {
				// double tall
				loc.getBlock().setType(Material.ROSE_BUSH, false);
			}
		}

	}
}
