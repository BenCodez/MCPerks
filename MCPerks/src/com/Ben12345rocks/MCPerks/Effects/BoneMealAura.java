/*
 *
 */
package com.Ben12345rocks.MCPerks.Effects;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;

import com.Ben12345rocks.AdvancedCore.Util.Misc.MiscUtils;

public class BoneMealAura {

	private int range = 5;

	public void checkPlants(Location loc, int chance) {
		int y = loc.getBlockY();
		int x = loc.getBlockX();
		int z = loc.getBlockZ();

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

}
