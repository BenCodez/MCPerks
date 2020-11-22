/*
 *
 */
package com.bencodez.mcperks.effects;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import com.bencodez.advancedcore.api.item.ItemBuilder;
import com.bencodez.advancedcore.api.misc.MiscUtils;
import com.bencodez.mcperks.MCPerksMain;

public class BoneMealAura {

	private int range = 5;

	public void checkPlants(Player p, Location loc, int chance) {
		int y = loc.getBlockY();
		int x = loc.getBlockX();
		int z = loc.getBlockZ();
		if (!canInteract(p, loc.getBlock())) {
			return;
		}

		for (int i = (int) (x - range); i <= x + range; ++i) {
			for (int j = (int) (z - range); j <= z + range; ++j) {
				for (int k = (int) y - range; k <= y + range; k++) {
					Block block = loc.getWorld().getBlockAt(i, k, j);
					BlockData d = block.getBlockData();
					if (d instanceof Ageable) {
						Ageable crop = (Ageable) d;
						if (crop.getAge() < crop.getMaximumAge()) {
							if (MiscUtils.getInstance().checkChance(chance, 100)) {
								crop.setAge(crop.getAge() + 1);
								block.setBlockData(crop);
							}
						}
					} else {
						if (loc.getWorld().getBlockAt(i, k, j).getType().equals(Material.GRASS_BLOCK)
								&& loc.getWorld().getBlockAt(i, k + 1, j).getType().equals(Material.AIR)
								&& loc.getWorld().getBlockAt(i, k + 2, j).getType().equals(Material.AIR)) {
							if (MiscUtils.getInstance().checkChance(chance, 500)) {
								new FloristEffect().pickFlower(new Location(loc.getWorld(), i, k + 1, j));

							}
						}
					}
				}
			}
		}
	}

	@SuppressWarnings("deprecation")
	public boolean canInteract(Player p, Block b) {
		PlayerInteractEvent event = new PlayerInteractEvent(p, Action.RIGHT_CLICK_BLOCK,
				new ItemBuilder(Material.BONE_MEAL).setAmount(1).toItemStack(p), b, BlockFace.UP);
		MCPerksMain.plugin.getEffectHandler().getPlayerInteractEvents().add(event);
		Bukkit.getPluginManager().callEvent(event);
		if (!event.isCancelled()) {
			return true;
		}
		return false;
	}

}
