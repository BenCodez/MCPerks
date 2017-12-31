package com.Ben12345rocks.MCPerks.Effects;

import java.util.ArrayList;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.Ben12345rocks.MCPerks.Main;

public class FlyEffect {

	public void disableFly(ArrayList<String> arrayList) {
		for (String uuid : arrayList) {
			Player player = Bukkit.getPlayer(UUID.fromString(uuid));
			disableFly(player);
		}

	}

	public void disableFly(Player player) {
		if (player != null && !player.hasPermission("MCPerks.ServerFly.Bypass")) {
			Bukkit.getScheduler().runTask(Main.plugin, new Runnable() {

				@Override
				public void run() {
					player.setAllowFlight(false);
					player.setFlying(false);
					player.setFallDistance(-100000);
					Main.plugin.debug("Disable fly for " + player.getName());
				}
			});
		}
	}

	public void enableFly(ArrayList<Player> players) {
		Bukkit.getScheduler().runTask(Main.plugin, new Runnable() {

			@Override
			public void run() {
				for (Player player : players) {
					enableFly(player);

				}
			}
		});

	}

	public void enableFly(Player player) {
		player.setAllowFlight(true);
		Main.plugin.debug("Enabled fly for " + player.getName());
	}
}
