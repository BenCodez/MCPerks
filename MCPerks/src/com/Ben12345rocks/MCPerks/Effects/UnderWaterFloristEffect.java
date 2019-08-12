/*
 *
 */
package com.Ben12345rocks.MCPerks.Effects;

import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Bisected.Half;
import org.bukkit.block.data.type.CoralWallFan;
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
		if (event.getPlayer().getGameMode().equals(GameMode.CREATIVE)) {
			return;
		}
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
		if (loc.getBlock().getType().equals(Material.WATER)) {
			if (MiscUtils.getInstance().checkChance(15, 100)) {
				loc.getBlock().setType(Material.SEA_PICKLE);
				SeaPickle data = (SeaPickle) loc.getBlock().getBlockData();
				if (MiscUtils.getInstance().checkChance(85, 100)) {
					data.setPickles(ThreadLocalRandom.current().nextInt(1, 2));
				} else {
					data.setPickles(ThreadLocalRandom.current().nextInt(1, 4));
				}
				loc.getBlock().setBlockData(data);
			} else if (MiscUtils.getInstance().checkChance(30, 100)) {
				// coral blocks
				switch (ThreadLocalRandom.current().nextInt(1, 5)) {
					case 1:
						loc.getBlock().setType(Material.BRAIN_CORAL);
						break;
					case 2:
						loc.getBlock().setType(Material.BUBBLE_CORAL);
						break;
					case 3:
						loc.getBlock().setType(Material.FIRE_CORAL);
						break;
					case 4:
						loc.getBlock().setType(Material.HORN_CORAL);
						break;
					case 5:
						loc.getBlock().setType(Material.TUBE_CORAL);
						break;
					default:
						break;
				}
			} else if (MiscUtils.getInstance().checkChance(30, 100)) {
				// wall fans
				BlockFace face = BlockFace.DOWN;
				if (isValidBlock(loc.getBlock().getRelative(BlockFace.NORTH))) {
					face = BlockFace.NORTH;
				} else if (isValidBlock(loc.getBlock().getRelative(BlockFace.EAST))) {
					face = BlockFace.EAST;
				} else if (isValidBlock(loc.getBlock().getRelative(BlockFace.SOUTH))) {
					face = BlockFace.SOUTH;
				} else if (isValidBlock(loc.getBlock().getRelative(BlockFace.WEST))) {
					face = BlockFace.WEST;
				}

				// coral fan blocks
				if (face.equals(BlockFace.DOWN)) {
					switch (ThreadLocalRandom.current().nextInt(1, 5)) {
						case 1:
							loc.getBlock().setType(Material.BRAIN_CORAL_FAN);
							break;
						case 2:
							loc.getBlock().setType(Material.BUBBLE_CORAL_FAN);
							break;
						case 3:
							loc.getBlock().setType(Material.FIRE_CORAL_FAN);
							break;
						case 4:
							loc.getBlock().setType(Material.HORN_CORAL_FAN);
							break;
						case 5:
							loc.getBlock().setType(Material.TUBE_CORAL_FAN);
							break;
						default:
							break;
					}
				} else {
					switch (ThreadLocalRandom.current().nextInt(1, 5)) {
						case 1:
							loc.getBlock().setType(Material.BRAIN_CORAL_WALL_FAN);
							break;
						case 2:
							loc.getBlock().setType(Material.BUBBLE_CORAL_WALL_FAN);
							break;
						case 3:
							loc.getBlock().setType(Material.FIRE_CORAL_WALL_FAN);
							break;
						case 4:
							loc.getBlock().setType(Material.HORN_CORAL_WALL_FAN);
							break;
						case 5:
							loc.getBlock().setType(Material.TUBE_CORAL_WALL_FAN);
							break;
						default:
							break;
					}
					if (MiscUtils.getInstance().checkChance(80, 100)) {
						CoralWallFan data = (CoralWallFan) loc.getBlock().getBlockData();
						data.setFacing(face.getOppositeFace());
						loc.getBlock().setBlockData(data);
					}
				}

			} else if (MiscUtils.getInstance().checkChance(30, 100)) {
				loc.getBlock().setType(Material.KELP);
			} else if (MiscUtils.getInstance().checkChance(25, 100)) {
				loc.getBlock().setType(Material.SEAGRASS);
			} else if (MiscUtils.getInstance().checkChance(25, 100)) {
				setFlower(loc, Material.TALL_SEAGRASS);
			}
		}
	}

	public boolean isValidBlock(Block block) {
		if (!block.getType().isBlock() || !block.getType().isSolid()) {
			return false;
		}
		if (block.isEmpty() || block.isPassable() || block.isLiquid()) {
			return false;
		}

		if (block.getType().equals(Material.SAND) || block.getType().equals(Material.GRAVEL)
				|| block.getType().equals(Material.DIRT)) {
			return true;
		}
		return false;
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
