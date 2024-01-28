/*
 *
 */
package com.bencodez.mcperks.effects;

import java.util.Map.Entry;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.enchantments.EnchantmentTarget;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.perk.Perk;

public class AutoPlant {

	@SuppressWarnings("deprecation")
	public AutoPlant(MCPerksMain plugin, Player player, Block block, Perk perk, BlockBreakEvent event) {
		if (!isFarmable(block)) {
			return;
		}
		ItemStack itemInHand = player.getInventory().getItemInMainHand();
		if (!perk.getWhitelistedTools().isEmpty()) {
			if (!perk.getWhitelistedTools().contains(itemInHand.getType())) {
				return;
			}
		}
		if (perk.getRequiredEnchants().size() > 0) {
			for (Entry<String, Integer> enchants : perk.getRequiredEnchants().entrySet()) {
				if (itemInHand.getEnchantmentLevel(
						Enchantment.getByKey(NamespacedKey.minecraft(enchants.getKey()))) != enchants.getValue()) {
					return;
				}
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

		Material seedBlockType = getSeedBlockType(block.getType());
		Material seedItemType = getSeedItemType(block.getType());
		if (seedBlockType == null || seedItemType == null) {
			return;
		}
		BlockData data = block.getBlockData();
		if (data instanceof Ageable) {
			Ageable ageData = (Ageable) data;
			if (ageData.getMaximumAge() != ageData.getAge()) {
				event.setCancelled(true);
				return;
			}
		}
		if (hasSeed(player, seedItemType)) {
			deductSeed(player, seedItemType);
			plant(plugin, player, block, seedBlockType);
		}

		if (EnchantmentTarget.TOOL.includes(itemInHand)) {
			ItemMeta meta = itemInHand.getItemMeta();
			if (meta instanceof Damageable) {
				Damageable dMeta = (Damageable) meta;
				int level = itemInHand.getEnchantmentLevel(Enchantment.DURABILITY);
				int chance = (100 / (level + 1));
				int addedDamage = 0;
				if (chance == 100 || ThreadLocalRandom.current().nextInt(100) < chance) {
					addedDamage++;
				}

				if (addedDamage > 0) {
					dMeta.setDamage(dMeta.getDamage() + addedDamage);
					itemInHand.setItemMeta(dMeta);
					if (dMeta.getDamage() > (int) (itemInHand.getType().getMaxDurability())) {
						player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
					}
				}
			}
		}
	}

	private void plant(MCPerksMain plugin, Player player, Block block, Material seedType) {
		Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {

			@Override
			public void run() {
				block.setType(seedType);
			}
		}, 1);
	}

	public Material getSeedBlockType(Material crop) {
		switch (crop) {
		case WHEAT:
			return Material.WHEAT;
		case WHEAT_SEEDS:
			return Material.WHEAT;
		case POTATOES:
			return Material.POTATOES;
		case BEETROOT:
			return Material.BEETROOT_SEEDS;
		case BEETROOT_SEEDS:
			return Material.BEETROOT_SEEDS;
		case CARROTS:
			return Material.CARROTS;
		case BEETROOTS:
			return Material.BEETROOTS;
		default:
			return null;
		}
	}

	public Material getSeedItemType(Material crop) {
		switch (crop) {
		case WHEAT:
			return Material.WHEAT_SEEDS;
		case WHEAT_SEEDS:
			return Material.WHEAT_SEEDS;
		case POTATOES:
			return Material.POTATO;
		case BEETROOT:
			return Material.BEETROOT_SEEDS;
		case BEETROOTS:
			return Material.BEETROOT_SEEDS;
		case BEETROOT_SEEDS:
			return Material.BEETROOT_SEEDS;
		case CARROTS:
			return Material.CARROT;
		default:
			return null;
		}
	}

	public boolean hasSeed(Player player, Material seed) {
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

	public void deductSeed(Player player, Material seed) {
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

	public boolean isFarmable(Block b) {
		return b.getType().equals(Material.POTATOES) || b.getType().equals(Material.CARROTS)
				|| b.getType().equals(Material.WHEAT) || b.getType().equals(Material.WHEAT_SEEDS)
				|| b.getType().equals(Material.BEETROOTS);
	}
}