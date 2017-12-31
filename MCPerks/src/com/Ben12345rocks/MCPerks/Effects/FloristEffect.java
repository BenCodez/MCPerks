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
			if (player.getItemInHand().equals(new ItemStack(Material.INK_SACK, 1, (short) 15))) {
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
	@SuppressWarnings("deprecation")
	public void pickFlower(Location loc) {
		if (loc.getBlock().getType().equals(Material.AIR)) {
			double randomNum = Math.random();
			if (randomNum < 0.11D) {
				loc.getBlock().setType(Material.YELLOW_FLOWER);
			} else if (randomNum < 0.22D) {
				loc.getBlock().setType(Material.RED_ROSE);
			} else if (randomNum < 0.25D) {
				loc.getBlock().setType(Material.RED_ROSE);
				loc.getBlock().setData((byte) 1);
			} else if (randomNum < 0.28D) {
				loc.getBlock().setType(Material.RED_ROSE);
				loc.getBlock().setData((byte) 2);
			} else if (randomNum < 0.35D) {
				loc.getBlock().setType(Material.RED_ROSE);
				loc.getBlock().setData((byte) 3);
			} else if (randomNum < 0.42D) {
				loc.getBlock().setType(Material.RED_ROSE);
				loc.getBlock().setData((byte) 4);
			} else if (randomNum < 0.49D) {
				loc.getBlock().setType(Material.RED_ROSE);
				loc.getBlock().setData((byte) 5);
			} else if (randomNum < 0.56D) {
				loc.getBlock().setType(Material.RED_ROSE);
				loc.getBlock().setData((byte) 6);
			} else if (randomNum < 0.63D) {
				loc.getBlock().setType(Material.RED_ROSE);
				loc.getBlock().setData((byte) 7);
			} else if (randomNum < 0.7D) {
				loc.getBlock().setType(Material.RED_ROSE);
				loc.getBlock().setData((byte) 8);
			} else if (randomNum < 0.73D) {
				loc.getBlock().setType(Material.DOUBLE_PLANT, false);
				loc.getWorld().getBlockAt(new Location(loc.getWorld(), loc.getX(), loc.getY() + 1.0D, loc.getZ()))
						.setType(Material.DOUBLE_PLANT, false);
				loc.getWorld().getBlockAt(new Location(loc.getWorld(), loc.getX(), loc.getY() + 1.0D, loc.getZ()))
						.setData((byte) 8, false);
			} else if (randomNum < 0.78D) {
				loc.getBlock().setType(Material.DOUBLE_PLANT, false);
				loc.getWorld().getBlockAt(new Location(loc.getWorld(), loc.getX(), loc.getY() + 1.0D, loc.getZ()))
						.setType(Material.DOUBLE_PLANT, false);
				loc.getBlock().setData((byte) 1, false);
				loc.getWorld().getBlockAt(new Location(loc.getWorld(), loc.getX(), loc.getY() + 1.0D, loc.getZ()))
						.setData((byte) 8, false);
			} else if (randomNum < 0.83D) {
				loc.getBlock().setType(Material.DOUBLE_PLANT, false);
				loc.getWorld().getBlockAt(new Location(loc.getWorld(), loc.getX(), loc.getY() + 1.0D, loc.getZ()))
						.setType(Material.DOUBLE_PLANT, false);
				loc.getBlock().setData((byte) 4, false);
				loc.getWorld().getBlockAt(new Location(loc.getWorld(), loc.getX(), loc.getY() + 1.0D, loc.getZ()))
						.setData((byte) 8, false);
			} else if (randomNum < 0.88D) {
				loc.getBlock().setType(Material.DOUBLE_PLANT, false);
				loc.getBlock().setData((byte) 5, false);
				loc.getWorld().getBlockAt(new Location(loc.getWorld(), loc.getX(), loc.getY() + 1.0D, loc.getZ()))
						.setType(Material.DOUBLE_PLANT, false);
				loc.getWorld().getBlockAt(new Location(loc.getWorld(), loc.getX(), loc.getY() + 1.0D, loc.getZ()))
						.setData((byte) 8, false);
			}
		}

	}
}
