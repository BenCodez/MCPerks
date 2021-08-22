package com.bencodez.mcperks.userapi;

import java.util.UUID;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import com.bencodez.advancedcore.api.misc.PlayerUtils;
import com.bencodez.advancedcore.api.user.usercache.UserDataManager;
import com.bencodez.advancedcore.api.user.usercache.keys.UserDataKeyInt;
import com.bencodez.advancedcore.api.user.usercache.keys.UserDataKeyString;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.configs.ConfigPerks;

public class UserManager {
	private MCPerksMain plugin;

	public UserManager(MCPerksMain plugin) {
		this.plugin = plugin;
	}

	public MCPerksUser getMCPerksUser(OfflinePlayer player) {
		return getMCPerksUser(player.getName());
	}

	public MCPerksUser getMCPerksUser(Player player) {
		return getMCPerksUser(player.getName());
	}

	public MCPerksUser getMCPerksUser(String playerName) {
		return getMCPerksUser(UUID.fromString(PlayerUtils.getInstance().getUUID(playerName)));
	}

	@SuppressWarnings("deprecation")
	public MCPerksUser getMCPerksUser(UUID uuid) {
		return new MCPerksUser(uuid);
	}

	public void addCachingKeys() {
		UserDataManager manager = plugin.getUserManager().getDataManager();
		manager.addKey(new UserDataKeyString("UseBossBar").setColumnType("VARCHAR(5)"));
		manager.addKey(new UserDataKeyString("IgnoreEffects").setColumnType("VARCHAR(5)"));
		manager.addKey(new UserDataKeyInt("Activations"));
		for (String perk : ConfigPerks.getInstance().getPerksNames()) {
			manager.addKey(new UserDataKeyInt("PerkActivations." + perk));
			manager.addKey(new UserDataKeyInt(perk + "_TimesUsed"));
			manager.addKey(new UserDataKeyString("Experation." + perk));
			manager.addKey(new UserDataKeyString(perk + "_CoolDown"));
		}

	}
}
