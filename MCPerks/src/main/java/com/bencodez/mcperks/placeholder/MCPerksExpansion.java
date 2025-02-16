package com.bencodez.mcperks.placeholder;

import org.bukkit.OfflinePlayer;

import com.bencodez.mcperks.MCPerksMain;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

public class MCPerksExpansion extends PlaceholderExpansion {

	private MCPerksMain plugin;

	public MCPerksExpansion(MCPerksMain plugin) {
		this.plugin = plugin;
	}

	@Override
	public boolean persist() {
		return true;
	}

	@Override
	public String getAuthor() {
		return "bencodez";
	}

	@Override
	public String getIdentifier() {
		return "mcperks";
	}

	@Override
	public String getVersion() {
		return "1.4";
	}

	@Override
	public String onRequest(OfflinePlayer p, String identifier) {
		if (p == null) {
			return "";
		}
		return plugin.placeHolder(p, identifier);
	}

}
