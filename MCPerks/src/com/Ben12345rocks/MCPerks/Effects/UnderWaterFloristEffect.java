/*
 *
 */
package com.Ben12345rocks.MCPerks.Effects;

import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Bisected.Half;
import org.bukkit.block.data.type.SeaPickle;
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
					if (loc.getBlock().getBiome().toString().contains("OCEAN")) {
						pickFlower(new Location(loc.getWorld(), i, y + 1, j));
					}

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
		if (loc.getBlock().getType().equals(Material.WATER)) {
			if (MiscUtils.getInstance().checkChance(20, 100)) {
				loc.getBlock().setType(Material.SEA_PICKLE);
				SeaPickle data = (SeaPickle) loc.getBlock().getBlockData();
				data.setPickles(ThreadLocalRandom.current().nextInt(1, 4));
				loc.getBlock().setBlockData(data);
			} else if (MiscUtils.getInstance().checkChance(30, 100)) {
				loc.getBlock().setType(Material.KELP);
			} else if (MiscUtils.getInstance().checkChance(50, 100)) {
				loc.getBlock().setType(Material.SEAGRASS);
			} else if (MiscUtils.getInstance().checkChance(50, 100)) {
				setFlower(loc, Material.TALL_SEAGRASS);
			}
		}
	}

	public void setFlower(Location loc, Material material) {
		Block flowerBlockLower = loc.getBlock();
		Block flowerBlockUpper = flowerBlockLower.getRelative(BlockFace.UP);
		setFlower(flowerBlockLower, material, Half.BOTTOM);
		setFlower(flowerBlockUpper, material, Half.TOP);
	}

	private void setFlower(Block block, Material type, Half half) {
		block.setType(type, false);
		Bisected data = (Bisected) block.getBlockData();
		data.setHalf(half);
		block.setBlockData(data, false);
	}

}
