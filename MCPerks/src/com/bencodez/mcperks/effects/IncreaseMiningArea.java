/*
 *
 */
package com.bencodez.mcperks.effects;

import java.util.Map.Entry;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.enchantments.EnchantmentTarget;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import com.bencodez.advancedcore.api.misc.ArrayUtils;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.perk.Effect;
import com.bencodez.mcperks.perk.Perk;

// TODO: Auto-generated Javadoc
/**
 * The Class DoubleExperienceEffect.
 */
public class IncreaseMiningArea {

	@SuppressWarnings("deprecation")
	public IncreaseMiningArea(MCPerksMain plugin, BlockBreakEvent event, Perk perk, Player p, double range,
			BlockFace face) {
		ItemStack itemInHand = p.getInventory().getItemInMainHand();
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
			if (!perk.getWhitelistedBlocks().contains(event.getBlock().getType())) {
				return;
			}
		}
		if (perk.getBlacklistedBlocks().contains(event.getBlock().getType())) {
			return;
		}
		boolean xdirection = false;
		boolean vertical = false;
		float pitch = p.getLocation().getPitch();
		if (pitch > 60 || pitch < -60) {
			vertical = true;
		}
		if (!vertical) {
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
			case UP:
				vertical = true;
				break;
			case DOWN:
				vertical = true;
				break;
			default:
				break;
			}
		}

		int numberOfBlocks = 0;

		if (vertical) {
			for (int x = (int) (event.getBlock().getX() - range); x <= event.getBlock().getX() + range; x++) {
				for (int z = (int) (event.getBlock().getZ() - range); z <= event.getBlock().getZ() + range; z++) {
					Block b = event.getBlock().getWorld().getBlockAt(x, event.getBlock().getY(), z);
					boolean canBreak = true;
					if (!perk.getWhitelistedBlocks().isEmpty()) {
						if (!perk.getWhitelistedBlocks().contains(b.getType())) {
							canBreak = false;
						}
					}
					if (perk.getBlacklistedBlocks().contains(b.getType())) {
						canBreak = false;
					}
					if (canBreak && !b.getType().equals(Material.BEDROCK) && canBreakBlock(p, b)) {
						if (plugin.getPerkHandler().effectActive(Effect.AutoPickupItems, p.getUniqueId().toString(),
								p.getWorld().getName())) {
							plugin.getMcperksUserManager().getMCPerksUser(p).giveItems(ArrayUtils.getInstance()
									.convertItems(b.getDrops(p.getInventory().getItemInMainHand())));
							b.setType(Material.AIR);
						} else {
							b.breakNaturally(itemInHand);
						}
						numberOfBlocks++;
					}
				}
			}
		} else {
			if (xdirection) {
				for (int x = (int) (event.getBlock().getX() - range); x <= event.getBlock().getX() + range; x++) {
					for (int y = (int) (event.getBlock().getY() - range); y <= event.getBlock().getY() + range; y++) {
						Block b = event.getBlock().getWorld().getBlockAt(x, y, event.getBlock().getZ());
						boolean canBreak = true;
						if (!perk.getWhitelistedBlocks().isEmpty()) {
							if (!perk.getWhitelistedBlocks().contains(b.getType())) {
								canBreak = false;
							}
						}
						if (perk.getBlacklistedBlocks().contains(b.getType())) {
							canBreak = false;
						}
						if (canBreak && !b.getType().equals(Material.BEDROCK) && canBreakBlock(p, b)) {
							if (plugin.getPerkHandler().effectActive(Effect.AutoPickupItems, p.getUniqueId().toString(),
									p.getWorld().getName())) {
								plugin.getMcperksUserManager().getMCPerksUser(p).giveItems(ArrayUtils.getInstance()
										.convertItems(b.getDrops(p.getInventory().getItemInMainHand())));
								b.setType(Material.AIR);
							} else {
								b.breakNaturally(itemInHand);
							}
							numberOfBlocks++;
						}
					}
				}
			} else {
				for (int z = (int) (event.getBlock().getZ() - range); z <= event.getBlock().getZ() + range; z++) {
					for (int y = (int) (event.getBlock().getY() - range); y <= event.getBlock().getY() + range; y++) {
						Block b = event.getBlock().getWorld().getBlockAt(event.getBlock().getX(), y, z);
						boolean canBreak = true;
						if (!perk.getWhitelistedBlocks().isEmpty()) {
							if (!perk.getWhitelistedBlocks().contains(b.getType())) {
								canBreak = false;
							}
						}
						if (perk.getBlacklistedBlocks().contains(b.getType())) {
							canBreak = false;
						}
						if (canBreak && !b.getType().equals(Material.BEDROCK) && canBreakBlock(p, b)) {
							if (plugin.getPerkHandler().effectActive(Effect.AutoPickupItems, p.getUniqueId().toString(),
									p.getWorld().getName())) {
								plugin.getMcperksUserManager().getMCPerksUser(p).giveItems(ArrayUtils.getInstance()
										.convertItems(b.getDrops(p.getInventory().getItemInMainHand())));
								b.setType(Material.AIR);
							} else {
								b.breakNaturally(itemInHand);
							}
							numberOfBlocks++;
						}
					}
				}
			}
		}

		if (EnchantmentTarget.TOOL.includes(itemInHand)) {
			ItemMeta meta = itemInHand.getItemMeta();
			if (meta instanceof Damageable) {
				Damageable dMeta = (Damageable) meta;
				int level = itemInHand.getEnchantmentLevel(Enchantment.DURABILITY);
				int chance = (100 / (level + 1));
				int addedDamage = 0;
				for (int i = 0; i < numberOfBlocks; i++) {
					if (chance == 100 || ThreadLocalRandom.current().nextInt(100) < chance) {
						addedDamage++;
					}
				}
				if (addedDamage > 0) {
					dMeta.setDamage(dMeta.getDamage() + addedDamage);
					itemInHand.setItemMeta(dMeta);
					if (dMeta.getDamage() > (int) (itemInHand.getType().getMaxDurability())) {
						p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
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
