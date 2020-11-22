/*
 *
 */
package com.bencodez.mcperks.data;

import org.bukkit.configuration.file.FileConfiguration;

import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.perk.Perk;

// TODO: Auto-generated Javadoc
/**
 * The Class ServerData.
 */
public class ServerData {

	private MCPerksMain plugin;

	public ServerData(MCPerksMain plugin) {
		this.plugin = plugin;
	}

	public void addPerkHistory(String data) {
		String path = "PerkHistory." + plugin.getTimeChecker().getTime().getMonth().toString();
		getData().set(path, getData().getStringList(path).add(data));
		saveData();
	}

	/**
	 * Gets the data.
	 *
	 * @return the data
	 */
	public FileConfiguration getData() {
		return plugin.getServerDataFile().getData();
	}

	public long getPerkExperation(Perk perk) {
		return getData().getLong("Experation." + perk.getPerk());
	}

	/**
	 * Reload data.
	 */
	public void reloadData() {
		plugin.getServerDataFile().reloadData();
	}

	/**
	 * Save data.
	 */
	public void saveData() {
		plugin.getServerDataFile().saveData();
	}

	public void setPerkExperation(Perk perk, long time) {
		getData().set("Experation." + perk.getPerk(), time);
		saveData();
	}

}
