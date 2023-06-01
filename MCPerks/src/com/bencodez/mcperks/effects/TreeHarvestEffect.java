/*
 *
 */
package com.bencodez.mcperks.effects;

import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import com.bencodez.advancedcore.api.misc.ArrayUtils;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.perk.Effect;
import com.bencodez.mcperks.perk.Perk;

public class TreeHarvestEffect {

	public TreeHarvestEffect(MCPerksMain plugin, Player player, Block block, Perk perk) {
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
		int blocksBroken = breakRelativeLogs(plugin, player, block, 2);
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
				if (dMeta.getDamage() > (int)(itemInHand.getType().getMaxDurability())) {
					player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
				}
			}
		}
	}

	public Material getSapling(Material log) {
		switch (log) {
		case OAK_LOG:
			return Material.OAK_SAPLING;
		case ACACIA_LOG:
			return Material.ACACIA_SAPLING;
		case BIRCH_LOG:
			return Material.BIRCH_SAPLING;
		case CHERRY_LOG:
			return Material.CHERRY_SAPLING;
		case DARK_OAK_LOG:
			return Material.DARK_OAK_SAPLING;
		case JUNGLE_LOG:
			return Material.JUNGLE_SAPLING;
		case MANGROVE_LOG:
			return Material.MANGROVE_PROPAGULE;
		case SPRUCE_LOG:
			return Material.SPRUCE_SAPLING;
		default:
			return null;
		}
	}

	public void replant(Block replantBlock, Material sapling) {

		if (sapling != null) {
			replantBlock.setType(sapling);
		}
	}

	public int breakRelativeLogs(MCPerksMain plugin, Player player, Block orgBlock, int range) {
		int numberOfBlocksBroken = 0;
		int x = orgBlock.getX();
		int y = orgBlock.getY();
		int z = orgBlock.getZ();
		for (int i = (int) (x - range); i <= x + range; ++i) {
			for (int j = (int) (z - range); j <= z + range; ++j) {
				for (int k = (int) y - range; k <= y + range; k++) {
					Block block = orgBlock.getWorld().getBlockAt(i, k, j);
					if (Tag.LOGS.isTagged(block.getType())) {
						boolean isBottom = false;
						final Material sapling = getSapling(block.getType());
						if (Tag.DIRT.isTagged(block.getRelative(BlockFace.DOWN).getType())) {
							isBottom = true;
						}

						if (canBreakBlock(player, orgBlock)) {
							if (plugin.getPerkHandler().effectActive(Effect.AutoPickupItems,
									player.getUniqueId().toString(), player.getWorld().getName())) {
								plugin.getMcperksUserManager().getMCPerksUser(player).giveItems(ArrayUtils.getInstance()
										.convertItems(block.getDrops(player.getInventory().getItemInMainHand())));
								block.setType(Material.AIR);
							} else {
								block.breakNaturally(player.getInventory().getItemInMainHand());
							}

							numberOfBlocksBroken += breakRelativeLogs(plugin, player, block, range);
							numberOfBlocksBroken++;
							if (isBottom && sapling != null) {
								if (hasSapling(player, sapling)) {
									deductSapling(player, sapling);
									Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {

										@Override
										public void run() {
											replant(block, sapling);
										}
									}, 1);

								}

							}
						}
					}
				}
			}
		}
		return numberOfBlocksBroken;
	}

	public boolean hasSapling(Player player, Material seed) {
		if (player.getGameMode().equals(GameMode.CREATIVE)) {
			return true;
		}

		for (ItemStack item : player.getInventory().getContents()) {
			if (item != null) {
				if (item.getType().equals(seed)) {
					return true;
				}
			}
		}
		return false;
	}

	public void deductSapling(Player player, Material seed) {
		if (player.getGameMode().equals(GameMode.CREATIVE)) {
			return;
		}

		for (ItemStack item : player.getInventory().getContents()) {
			if (item != null) {
				if (item.getType().equals(seed)) {
					if (item.getAmount() == 1) {
						item.setAmount(0);
						item.setType(Material.AIR);
					} else {
						item.setAmount(item.getAmount() - 1);
					}
					player.updateInventory();
					return;
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
