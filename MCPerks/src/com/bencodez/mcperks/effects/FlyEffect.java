package com.bencodez.mcperks.effects;

import java.util.ArrayList;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.bencodez.mcperks.MCPerksMain;

public class FlyEffect {

	public void disableFly(ArrayList<Player> players) {
		for (Player player : players) {
			disableFly(player);
		}

	}

	public void disableFly(Player player) {
		if (player != null && !player.hasPermission("MCPerks.ServerFly.Bypass")) {
			Bukkit.getScheduler().runTask(MCPerksMain.plugin, new Runnable() {

				@Override
				public void run() {
					player.setAllowFlight(false);
					player.setFlying(false);
					player.setFallDistance(-100000);
					MCPerksMain.plugin.debug("Disable fly for " + player.getName());
				}
			});
		}
	}

	public void enableFly(final ArrayList<String> flyWorlds, ArrayList<Player> players) {
		Bukkit.getScheduler().runTask(MCPerksMain.plugin, new Runnable() {

			@Override
			public void run() {
				for (Player player : players) {
					enableFly(flyWorlds, player);

				}
			}
		});

	}

	public void enableFly(ArrayList<String> flyWorlds, Player player) {
		if (flyWorlds.contains(player.getWorld().getName())) {
			player.setAllowFlight(true);
			MCPerksMain.plugin.debug("Enabled fly for " + player.getName());
		} else {
			MCPerksMain.plugin.debug("Player " + player.getName() + " not in the correct world to enable fly");
		}
	}
}
