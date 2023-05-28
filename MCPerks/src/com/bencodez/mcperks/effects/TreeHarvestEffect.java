/*
 *
 */
package com.bencodez.mcperks.effects;

import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

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
		int blocksBroken = breakRelativeLogs(player, block, 2);
		ItemMeta meta = itemInHand.getItemMeta();
		if (meta instanceof Damageable) {
			Damageable dMeta = (Damageable) meta;
			int level = itemInHand.getEnchantmentLevel(Enchantment.DURABILITY);
			int chance = (100 / (level + 1));
			int addedDamage = 0;
			for (int i = 0; i < blocksBroken; i++) {
				if (chance == 100 || ThreadLocalRandom.current().nextInt(100) < chance) {
					addedDamage++;
				}
			}
			if (addedDamage > 0) {
				dMeta.setDamage(dMeta.getDamage() + addedDamage);
				itemInHand.setItemMeta(dMeta);
			}
		}
	}

	public int breakRelativeLogs(Player player, Block orgBlock, int range) {
		int numberOfBlocksBroken = 0;
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
							numberOfBlocksBroken += breakRelativeLogs(player, block, range);
							numberOfBlocksBroken++;
						}
					}
				}
			}
		}
		return numberOfBlocksBroken;
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
