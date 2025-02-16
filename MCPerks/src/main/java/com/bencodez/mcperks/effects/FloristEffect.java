/*
 *
 */
package com.bencodez.mcperks.effects;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Bisected.Half;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import com.bencodez.advancedcore.api.misc.MiscUtils;

// TODO: Auto-generated Javadoc
/**
 * The Class FloristEffect.
 */
public class FloristEffect {

	@SuppressWarnings("deprecation")
	public void deductBoneMeal(PlayerInteractEvent event) {
		// Main.plugin.debug("Amount: " + event.getItem().getAmount());
		if (event.getPlayer().getGameMode().equals(GameMode.CREATIVE)) {
			return;
		}
		if (event.getItem().getAmount() == 1) {
			Player player = event.getPlayer();
			if (player.getItemInHand().getType().equals(Material.BONE_MEAL)) {
				player.setItemInHand(new ItemStack(Material.AIR));
			}
			event.getPlayer().updateInventory();
		} else {
			event.getItem().setAmount(event.getItem().getAmount() - 1);
			event.getPlayer().updateInventory();
		}

	}

	/**
	 * Generate flowers.
	 *
	 * @param loc the loc
	 * @param d   Radius
	 */
	public void generateFlowers(Location loc, double d) {
		int y = loc.getBlockY();
		int x = loc.getBlockX();
		int z = loc.getBlockZ();

		for (int i = (int) (x - d); i <= x + d; ++i) {
			for (int j = (int) (z - d); j <= z + d; ++j) {
				if (loc.getWorld().getBlockAt(i, y, j).getType().equals(Material.GRASS_BLOCK)
						&& loc.getWorld().getBlockAt(i, y + 1, j).getType().equals(Material.AIR)
						&& loc.getWorld().getBlockAt(i, y + 2, j).getType().equals(Material.AIR)) {
					pickFlower(new Location(loc.getWorld(), i, y + 1, j));

				}
			}
		}

	}

	/**
	 * Pick flower.
	 *
	 * @param loc the loc
	 */
	public void pickFlower(Location loc) {
		if (loc.getBlock().getType().equals(Material.AIR)) {
			if (MiscUtils.getInstance().checkChance(11, 100)) {
				loc.getBlock().setType(Material.DANDELION);
			} else if (MiscUtils.getInstance().checkChance(11, 100)) {
				loc.getBlock().setType(Material.POPPY);
			} else if (MiscUtils.getInstance().checkChance(5, 100)) {
				loc.getBlock().setType(Material.BLUE_ORCHID);
			} else if (MiscUtils.getInstance().checkChance(7, 100)) {
				loc.getBlock().setType(Material.ALLIUM);
			} else if (MiscUtils.getInstance().checkChance(7, 100)) {
				loc.getBlock().setType(Material.AZURE_BLUET);
			} else if (MiscUtils.getInstance().checkChance(7, 100)) {
				loc.getBlock().setType(Material.RED_TULIP);
			} else if (MiscUtils.getInstance().checkChance(7, 100)) {
				loc.getBlock().setType(Material.ORANGE_TULIP);
			} else if (MiscUtils.getInstance().checkChance(7, 100)) {
				loc.getBlock().setType(Material.WHITE_TULIP);
			} else if (MiscUtils.getInstance().checkChance(7, 100)) {
				loc.getBlock().setType(Material.PINK_TULIP);
			} else if (MiscUtils.getInstance().checkChance(7, 100)) {
				loc.getBlock().setType(Material.OXEYE_DAISY);
			} else if (MiscUtils.getInstance().checkChance(5, 100)) {
				loc.getBlock().setType(Material.CORNFLOWER);
			} else if (MiscUtils.getInstance().checkChance(5, 100)) {
				// double tall
				setFlower(loc, Material.PEONY);
			} else if (MiscUtils.getInstance().checkChance(5, 100)) {
				// double tall
				setFlower(loc, Material.SUNFLOWER);
			} else if (MiscUtils.getInstance().checkChance(5, 100)) {
				// double tall
				setFlower(loc, Material.LILAC);
			} else if (MiscUtils.getInstance().checkChance(5, 100)) {
				// double tall
				setFlower(loc, Material.ROSE_BUSH);
			} else if (MiscUtils.getInstance().checkChance(5, 100)) {
				// double tall
				setFlower(loc, Material.LARGE_FERN);
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
