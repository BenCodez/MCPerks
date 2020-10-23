package com.Ben12345rocks.MCPerks.UserAPI;

import org.bukkit.entity.Player;

import com.Ben12345rocks.AdvancedCore.UserManager.UUID;
import com.Ben12345rocks.MCPerks.Main;
import com.Ben12345rocks.MCPerks.Perk.Perk;

// TODO: Auto-generated Javadoc
/**
 * The Class User.
 */
public class User extends com.Ben12345rocks.AdvancedCore.UserManager.User {

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
			return Long.parseLong(getData().getString("Experation." + perk.getPerk()));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	public int getPerkTimesUsed(Perk perk) {
		return getUserData().getInt(perk.getPerk() + "_TimesUsed");
	}

	public boolean isUseBossBar() {
		String str = getData().getString("UseBossBar");
		if (str == null || str.isEmpty()) {
			return true;
		}
		return Boolean.valueOf(str);
	}

	public void setPerkCoolDown(Perk perk, long value) {
		getUserData().setString(perk.getPerk() + "_CoolDown", "" + value);
	}

	public void setPerkExperation(Perk perk, long time) {
		getData().setString("Experation." + perk.getPerk(), "" + time);
	}

	public void setPerkTimesUsed(Perk perk, int value) {
		getUserData().setInt(perk.getPerk() + "_TimesUsed", value);
	}

	public int getPerkLimit() {
		for (int i = Main.plugin.getPerkHandler().getLoadedPerks().size(); i > 0; i--) {
			if (hasPermission("MCPerks.Limit." + i)) {
				return i;
			}
		}
		return -1;
	}

	public int getActivePerks() {
		int perks = 0;
		for (Perk perk : Main.plugin.getPerkHandler().getActivePerks()) {
			if (perk.getActivater().getPlayerName().equals(getPlayerName())) {
				perks++;
			} else if (perk.getEffectedPlayers().contains(getUUID())) {
				perks++;
			}
		}
		return perks;
	}

	public void setUseBossBar(boolean b) {
		getData().setString("UseBossBar", "" + b);
	}

	public int getActivations() {
		return getData().getInt("Activations");
	}

	public void setActivations(int value) {
		getData().setInt("Activations", value);
	}

	public void addActivation(int add) {
		setActivations(getActivations() + add);
	}

	public void addActivation() {
		addActivation(1);
	}

	public int getActivations(String perk) {
		return getData().getInt("PerkActivations." + perk);
	}

	public void setActivations(String perk, int value) {
		getData().setInt("PerkActivations." + perk, value);
	}

	public void addActivation(String perk, int add) {
		setActivations(perk, getActivations(perk) + add);
	}

	public void addActivation(String perk) {
		addActivation(perk, 1);
	}

	public boolean isIgnorePerkEffects() {
		return getData().getBoolean("IgnoreEffects");
	}

	public void setIgnoreEffects(boolean value) {
		getData().setBoolean("IgnoreEffects", value);
	}

}
