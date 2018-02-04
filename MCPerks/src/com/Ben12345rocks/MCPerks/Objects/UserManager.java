package com.Ben12345rocks.MCPerks.Objects;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import com.Ben12345rocks.AdvancedCore.Objects.UUID;
import com.Ben12345rocks.AdvancedCore.Util.Misc.PlayerUtils;

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

	public User getMCPerksUser(java.util.UUID uuid) {
		return getMCPerksUser(new UUID(uuid.toString()));

	}

	public User getMCPerksUser(OfflinePlayer player) {
		return getMCPerksUser(player.getName());
	}

	public User getMCPerksUser(Player player) {
		return getMCPerksUser(player.getName());
	}

	public User getMCPerksUser(String playerName) {
		return getMCPerksUser(new UUID(PlayerUtils.getInstance().getUUID(playerName)));
	}

	@SuppressWarnings("deprecation")
	public User getMCPerksUser(UUID uuid) {
		return new User(uuid);
	}
}
