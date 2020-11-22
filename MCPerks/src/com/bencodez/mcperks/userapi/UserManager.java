package com.bencodez.mcperks.userapi;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import com.bencodez.advancedcore.api.misc.PlayerUtils;
import com.bencodez.advancedcore.api.user.UUID;

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

	public MCPerksUser getMCPerksUser(java.util.UUID uuid) {
		return getMCPerksUser(new UUID(uuid.toString()));

	}

	public MCPerksUser getMCPerksUser(OfflinePlayer player) {
		return getMCPerksUser(player.getName());
	}

	public MCPerksUser getMCPerksUser(Player player) {
		return getMCPerksUser(player.getName());
	}

	public MCPerksUser getMCPerksUser(String playerName) {
		return getMCPerksUser(new UUID(PlayerUtils.getInstance().getUUID(playerName)));
	}

	@SuppressWarnings("deprecation")
	public MCPerksUser getMCPerksUser(UUID uuid) {
		return new MCPerksUser(uuid);
	}
}
