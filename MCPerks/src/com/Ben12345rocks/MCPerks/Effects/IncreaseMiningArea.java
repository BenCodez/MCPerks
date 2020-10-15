/*
 *
 */
package com.Ben12345rocks.MCPerks.Effects;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import com.Ben12345rocks.MCPerks.Perk.Perk;

// TODO: Auto-generated Javadoc
/**
 * The Class DoubleExperienceEffect.
 */
public class IncreaseMiningArea {

	public IncreaseMiningArea(BlockBreakEvent event, Perk perk, Player p, double range, BlockFace face) {
		ItemStack itemInHand = p.getInventory().getItemInMainHand();
		if (!perk.getWhitelistedTools().isEmpty()) {
			if (!perk.getWhitelistedTools().contains(itemInHand.getType())) {
				return;
			}
		}
		boolean xdirection = false;
		switch (face) {
			case EAST:
				xdirection = false;
				break;
			case NORTH:
				xdirection = true;
				break;
			case SOUTH:
				xdirection = true;
				break;
			case WEST:
				xdirection = false;
				break;
			default:
				break;
		}

		if (xdirection) {
			for (int x = (int) (event.getBlock().getX() - range); x <= event.getBlock().getX() + range; x++) {
				for (int y = (int) (event.getBlock().getY() - range); y <= event.getBlock().getY() + range; y++) {
					Block b = event.getBlock().getWorld().getBlockAt(x, y, event.getBlock().getZ());

					if (!b.getType().equals(Material.BEDROCK) && canBreakBlock(p, b)) {
						b.breakNaturally(itemInHand);
					}
				}
			}
		} else {
			for (int z = (int) (event.getBlock().getZ() - range); z <= event.getBlock().getZ() + range; z++) {
				for (int y = (int) (event.getBlock().getY() - range); y <= event.getBlock().getY() + range; y++) {
					Block b = event.getBlock().getWorld().getBlockAt(event.getBlock().getX(), y, z);

					if (!b.getType().equals(Material.BEDROCK) && canBreakBlock(p, b)) {
						b.breakNaturally(itemInHand);
					}
				}
			}
		}
	}

	public boolean canBreakBlock(Player p, Block b) {
		BlockBreakEvent block = new BlockBreakEvent(b, p);
		Bukkit.getPluginManager().callEvent(block);
		if (!block.isCancelled()) {
			return true;
		}
		return false;
	}

}
