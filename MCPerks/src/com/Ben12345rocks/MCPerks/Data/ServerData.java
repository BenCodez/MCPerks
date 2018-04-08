/*
 *
 */
package com.Ben12345rocks.MCPerks.Data;

import org.bukkit.configuration.file.FileConfiguration;

import com.Ben12345rocks.AdvancedCore.TimeChecker.TimeChecker;
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
		return com.Ben12345rocks.AdvancedCore.Data.ServerData.getInstance().getData();
	}

	public long getPerkExperation(Perk perk) {
		return getData().getLong("Experation." + perk.getName());
	}

	/**
	 * Reload data.
	 */
	public void reloadData() {
		com.Ben12345rocks.AdvancedCore.Data.ServerData.getInstance().reloadData();
	}

	/**
	 * Save data.
	 */
	public void saveData() {
		com.Ben12345rocks.AdvancedCore.Data.ServerData.getInstance().saveData();
	}

	public void setPerkExperation(Perk perk, long time) {
		getData().set("Experation." + perk.getName(), time);
		saveData();
	}

	public void addPerkHistory(String data) {
		String path = "PerkHistory." + TimeChecker.getInstance().getTime().getMonth().toString();
		getData().set(path, getData().getStringList(path).add(data));
		saveData();
	}

}
