package com.Ben12345rocks.MCPerks.Objects;

import org.bukkit.entity.Player;

import com.Ben12345rocks.AdvancedCore.Objects.UUID;
import com.Ben12345rocks.MCPerks.Main;

// TODO: Auto-generated Javadoc
/**
 * The Class User.
 */
public class User extends com.Ben12345rocks.AdvancedCore.Objects.User {

	/** The plugin. */
	static Main plugin = Main.plugin;

	/**
	 * Instantiates a new user.
	 *
	 * @param player
	 *            the player
	 */
	@Deprecated
	public User(Player player) {
		super(Main.plugin, player);
	}

	/**
	 * Instantiates a new user.
	 *
	 * @param playerName
	 *            the player name
	 */
	@Deprecated
	public User(String playerName) {
		super(Main.plugin, playerName);

	}

	/**
	 * Instantiates a new user.
	 *
	 * @param uuid
	 *            the uuid
	 */
	@Deprecated
	public User(UUID uuid) {
		super(Main.plugin, uuid);

	}

	/**
	 * Instantiates a new user.
	 *
	 * @param uuid
	 *            the uuid
	 * @param loadName
	 *            the load name
	 */
	@Deprecated
	public User(UUID uuid, boolean loadName) {
		super(Main.plugin, uuid, loadName);
	}

	public long getPerkCoolDown(Perk perk) {
		try {
			return Long.valueOf(getUserData().getString(perk.getPerk() + "_CoolDown"));
		} catch (NumberFormatException ex) {
			return 0;
		}
	}

	public long getPerkExperation(Perk perk) {
		try {
			return Long.parseLong(getData().getString("Experation." + perk.getName()));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	public int getPerkTimesUsed(Perk perk) {
		return getUserData().getInt(perk.getPerk() + "_TimesUsed");
	}

	public void setPerkCoolDown(Perk perk, long value) {
		getUserData().setString(perk.getPerk() + "_CoolDown", "" + value);
	}

	public void setPerkExperation(Perk perk, long time) {
		getData().setString("Experation." + perk.getName(), "" + time);
	}

	public void setPerkTimesUsed(Perk perk, int value) {
		getUserData().setInt(perk.getPerk() + "_TimesUsed", value);
	}

}
