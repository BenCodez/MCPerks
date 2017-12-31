/*
 *
 */
package com.Ben12345rocks.MCPerks.Data;

import java.io.File;
import java.io.IOException;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import com.Ben12345rocks.MCPerks.Main;
import com.Ben12345rocks.MCPerks.Objects.Perk;

// TODO: Auto-generated Javadoc
/**
 * The Class ServerData.
 */
public class ServerData {

	/** The instance. */
	static ServerData instance = new ServerData();

	/** The plugin. */
	static Main plugin = Main.plugin;

	/**
	 * Gets the single instance of ServerData.
	 *
	 * @return single instance of ServerData
	 */
	public static ServerData getInstance() {
		return instance;
	}

	/** The data. */
	FileConfiguration data;

	/** The d file. */
	File dFile;

	/**
	 * Instantiates a new server data.
	 */
	private ServerData() {
	}

	/**
	 * Instantiates a new server data.
	 *
	 * @param plugin
	 *            the plugin
	 */
	public ServerData(Main plugin) {
		ServerData.plugin = plugin;
	}

	/**
	 * Gets the data.
	 *
	 * @return the data
	 */
	public FileConfiguration getData() {
		return data;
	}

	public long getPerkExperation(Perk perk) {
		return getData().getLong("Experation." + perk.getName());
	}

	/**
	 * Reload data.
	 */
	public void reloadData() {
		data = YamlConfiguration.loadConfiguration(dFile);
	}

	/**
	 * Save data.
	 */
	public void saveData() {
		try {
			data.save(dFile);
		} catch (IOException e) {
			Bukkit.getServer().getLogger().severe(ChatColor.RED + "Could not save ServerData.yml!");
		}
	}

	public void setPerkExperation(Perk perk, long time) {
		getData().set("Experation." + perk.getName(), time);
		saveData();
	}

	/**
	 * Sets the up.
	 *
	 * @param p
	 *            the new up
	 */
	public void setup(Plugin p) {
		if (!p.getDataFolder().exists()) {
			p.getDataFolder().mkdir();
		}

		dFile = new File(p.getDataFolder(), "ServerData.yml");

		if (!dFile.exists()) {
			try {
				dFile.createNewFile();
				plugin.saveResource("ServerData.yml", true);
			} catch (IOException e) {
				Bukkit.getServer().getLogger().severe(ChatColor.RED + "Could not create ServerData.yml!");
			}
		}

		data = YamlConfiguration.loadConfiguration(dFile);
	}

}
