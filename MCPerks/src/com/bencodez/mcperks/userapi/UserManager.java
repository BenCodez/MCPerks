package com.bencodez.mcperks.userapi;

import java.util.UUID;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import com.bencodez.advancedcore.api.misc.PlayerUtils;

public class UserManager {
	/** The instance. */
	static UserManager instance = new UserManager();

	/**
	 * Gets the single instance of UserManager.
	 *
	 * @return single instance of UserManager
	 */
	public static UserManager getInstance() {
		return instance;
	}

	public UserManager() {
		super();
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
}
