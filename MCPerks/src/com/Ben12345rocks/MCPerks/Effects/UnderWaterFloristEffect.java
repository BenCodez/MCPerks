/*
 *
 */
package com.Ben12345rocks.MCPerks.Effects;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import com.Ben12345rocks.AdvancedCore.Util.Misc.MiscUtils;

// TODO: Auto-generated Javadoc
/**
 * The Class FloristEffect.
 */
public class UnderWaterFloristEffect {

	@SuppressWarnings("deprecation")
	public void deductBoneMeal(PlayerInteractEvent event) {
		// Main.plugin.debug("Amount: " + event.getItem().getAmount());
		if (event.getItem().getAmount() == 1) {
			Player player = event.getPlayer();
			if (player.getItemInHand().getType().equals(Material.BONE_MEAL)) {
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
	 * @param radius
	 *            Radius
	 */
	public void generateFlowers(Location loc, int radius) {
		int y = loc.getBlockY();
		int x = loc.getBlockX();
		int z = loc.getBlockZ();

		for (int i = x - radius; i <= x + radius; ++i) {
			for (int j = z - radius; j <= z + radius; ++j) {
				if (loc.getWorld().getBlockAt(i, y, j).getType().isSolid()
						&& loc.getWorld().getBlockAt(i, y + 1, j).getType().equals(Material.WATER)
						&& loc.getWorld().getBlockAt(i, y + 2, j).getType().equals(Material.WATER)) {
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
			if (MiscUtils.getInstance().checkChance(10, 100)) {
				loc.getBlock().setType(Material.SEA_PICKLE);
			} else if (MiscUtils.getInstance().checkChance(10, 100)) {
				loc.getBlock().setType(Material.KELP);
			}

		}
	}

}
