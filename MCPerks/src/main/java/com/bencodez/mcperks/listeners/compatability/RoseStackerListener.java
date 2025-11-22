package com.bencodez.mcperks.listeners.compatability;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.perk.Effect;
import com.bencodez.mcperks.perk.Perk;
import com.bencodez.simpleapi.array.ArrayUtils;

import dev.rosewood.rosestacker.event.PreDropStackedItemsEvent;

public class RoseStackerListener implements Listener {

	private MCPerksMain plugin;

	public RoseStackerListener(MCPerksMain plugin) {
		this.plugin = plugin;
	}

	/**
	 * Gets the nearest player to the given location within the specified max
	 * distance.
	 *
	 * @param location    The center location to search from.
	 * @param maxDistance The maximum distance (in blocks).
	 * @return The nearest Player, or null if none are within range.
	 */
	public Player getNearestPlayer(Location location, double maxDistance) {
		if (location == null || location.getWorld() == null) {
			return null;
		}

		Player nearest = null;
		double maxDistanceSquared = maxDistance * maxDistance;
		double closestSoFar = maxDistanceSquared;

		for (Player player : Bukkit.getOnlinePlayers()) {
			if (player.getWorld() != location.getWorld()) {
				continue;
			}

			double distanceSquared = player.getLocation().distanceSquared(location);
			if (distanceSquared <= closestSoFar) {
				closestSoFar = distanceSquared;
				nearest = player;
			}
		}

		return nearest;
	}

	@EventHandler
	public void onDropStack(PreDropStackedItemsEvent event) {
		Player player = getNearestPlayer(event.getLocation(), 6);
		List<ItemStack> items = new ArrayList<ItemStack>();

		for (Map.Entry<ItemStack, Integer> entry : event.getItems().entrySet()) {
			ItemStack original = entry.getKey();
			int amount = entry.getValue();

			if (original == null || amount <= 0)
				continue;

			// Clone to avoid mutating the stored ItemStack
			ItemStack clone = original.clone();
			clone.setAmount(amount);

			items.add(clone);
		}

		if (plugin.getPerkHandler().effectActive(Effect.InstantSmelt, player.getUniqueId().toString(),
				player.getWorld().getName())) {
			for (Perk active : plugin.getPerkHandler().getActivePerks()) {
				if (active.getEffects().contains(Effect.InstantSmelt)) {
					for (ItemStack item : items) {
						ItemStack smeltedItem = null;
						Iterator<Recipe> recipeIterator = plugin.getServer().recipeIterator();
						while (recipeIterator.hasNext()) {
							Recipe recipe = recipeIterator.next();
							if (recipe instanceof FurnaceRecipe) {
								FurnaceRecipe cookingRecipe = (FurnaceRecipe) recipe;
								if (cookingRecipe.getInputChoice().test(item)) {
									event.setCancelled(true);
									smeltedItem = cookingRecipe.getResult();
									int stackAmount = item.getAmount();
									smeltedItem.setAmount(stackAmount);
									player.giveExp(getExperienceAmount(cookingRecipe, stackAmount));
									final ItemStack toDrop = smeltedItem;
									if (plugin.getPerkHandler().effectActive(Effect.AutoPickupItems,
											player.getUniqueId().toString(), player.getWorld().getName())) {
										plugin.getMcperksUserManager().getMCPerksUser(player).giveItems(toDrop);
									} else {
										player.getWorld().dropItemNaturally(event.getLocation(), toDrop);
									}

								}
							}
						}
					}

				}
			}
		} else if (plugin.getPerkHandler().effectActive(Effect.AutoPickupItems, player.getUniqueId().toString(),
				player.getWorld().getName())) {
			event.setCancelled(true);
			plugin.getMcperksUserManager().getMCPerksUser(player).giveItems(ArrayUtils.convertItems(items));
		}
	}

	private int getExperienceAmount(FurnaceRecipe recipe, int stackAmount) {
		float experience = recipe.getExperience();

		int count = 1;
		int experienceAmount = 0;
		while (count <= stackAmount) {
			double rand = Math.random();
			if (rand <= (double) experience) {
				experienceAmount += (int) Math.ceil(experience);
			}
			count++;
		}
		return experienceAmount;
	}

}
