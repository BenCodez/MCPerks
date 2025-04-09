package com.bencodez.mcperks.effects;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

public class RepairEffect {
	public void repairRandomTool(Player player) {
		if (player == null) {
			return;
		}
		List<ItemStack> damagedTools = new ArrayList<>();

		// Collect damaged tools from the player's inventory
		for (ItemStack item : player.getInventory().getContents()) {
			if (item != null && isTool(item) && isDamaged(item)) {
				damagedTools.add(item);
			}
		}

		// If there are no damaged tools, exit
		if (damagedTools.isEmpty()) {
			return;
		}

		// Pick a random tool from the list
		ItemStack toolToRepair = damagedTools.get(ThreadLocalRandom.current().nextInt(damagedTools.size()));

		// Repair the tool by reducing its damage
		Damageable meta = (Damageable) toolToRepair.getItemMeta();
		if (meta != null) {
			int currentDamage = meta.getDamage();
			meta.setDamage(Math.max(0, currentDamage - 1)); // Reduce damage by 1
			toolToRepair.setItemMeta(meta);
		}
	}

	public void repairTools(Player player) {
		if (player == null) {
			return;
		}

		// Collect damaged tools from the player's inventory
		for (ItemStack item : player.getInventory().getContents()) {
			if (item != null && isTool(item) && isDamaged(item)) {
				Damageable meta = (Damageable) item.getItemMeta();
				if (meta != null) {
					int currentDamage = meta.getDamage();
					meta.setDamage(Math.max(0, currentDamage - 1)); // Reduce damage by 1
					item.setItemMeta(meta);
				}
			}
		}

	}

	private boolean isTool(ItemStack item) {
		// Check if the item is a tool (e.g., pickaxe, axe, shovel, etc.)
		Material type = item.getType();
		return type.name().endsWith("_PICKAXE") || type.name().endsWith("_AXE") || type.name().endsWith("_SHOVEL")
				|| type.name().endsWith("_HOE") || type.name().endsWith("_SWORD");
	}

	private boolean isDamaged(ItemStack item) {
		// Check if the item is damageable and has damage
		if (item.getItemMeta() instanceof Damageable) {
			Damageable meta = (Damageable) item.getItemMeta();
			return meta.getDamage() > 0;
		}
		return false;
	}
}
