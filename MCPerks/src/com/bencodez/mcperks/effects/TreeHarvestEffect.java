/*
 *
 */
package com.bencodez.mcperks.effects;

import org.bukkit.Bukkit;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.perk.Perk;

public class TreeHarvestEffect {

	public TreeHarvestEffect(Player player, Block block, Perk perk) {
		ItemStack itemInHand = player.getInventory().getItemInMainHand();
		if (!perk.getWhitelistedTools().isEmpty()) {
			if (!perk.getWhitelistedTools().contains(itemInHand.getType())) {
				return;
			}
		}
		if (!perk.getWhitelistedBlocks().isEmpty()) {
			if (!perk.getWhitelistedBlocks().contains(block.getType())) {
				return;
			}
		}
		if (perk.getBlacklistedBlocks().contains(block.getType())) {
			return;
		}
		breakRelativeLogs(player, block, 2);
	}

	public void breakRelativeLogs(Player player, Block orgBlock, int range) {
		int x = orgBlock.getX();
		int y = orgBlock.getY();
		int z = orgBlock.getZ();
		for (int i = (int) (x - range); i <= x + range; ++i) {
			for (int j = (int) (z - range); j <= z + range; ++j) {
				for (int k = (int) y - range; k <= y + range; k++) {
					Block block = orgBlock.getWorld().getBlockAt(i, k, j);
					if (Tag.LOGS.isTagged(block.getType())) {
						if (canBreakBlock(player, orgBlock)) {
							block.breakNaturally(player.getInventory().getItemInMainHand());
							breakRelativeLogs(player, block, range);
						}
					}
				}
			}
		}
	}

	public boolean canBreakBlock(Player p, Block b) {
		BlockBreakEvent block = new BlockBreakEvent(b, p);
		MCPerksMain.plugin.getEffectHandler().getBlockBreakEvents().add(block);
		Bukkit.getPluginManager().callEvent(block);
		if (!block.isCancelled()) {
			return true;
		}
		return false;
	}

}
