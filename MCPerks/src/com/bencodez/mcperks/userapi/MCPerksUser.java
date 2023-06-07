package com.bencodez.mcperks.userapi;

import java.util.UUID;

import org.bukkit.entity.Player;

import com.bencodez.advancedcore.api.user.AdvancedCoreUser;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.perk.Perk;

// TODO: Auto-generated Javadoc
/**
 * The Class User.
 */
public class MCPerksUser extends com.bencodez.advancedcore.api.user.AdvancedCoreUser {

	@SuppressWarnings("unused")
	private MCPerksMain plugin;

	@Deprecated
	public MCPerksUser(MCPerksMain plugin, Player player) {
		super(plugin, player);
		this.plugin = plugin;
	}

	@Deprecated
	public MCPerksUser(MCPerksMain plugin, String playerName) {
		super(plugin, playerName);
		this.plugin = plugin;
	}

	@Deprecated
	public MCPerksUser(MCPerksMain plugin, UUID uuid) {
		super(plugin, uuid);
		this.plugin = plugin;
	}

	@Deprecated
	public MCPerksUser(MCPerksMain plugin, UUID uuid, boolean loadName) {
		super(plugin, uuid, loadName);
		this.plugin = plugin;
	}

	@Deprecated
	public MCPerksUser(MCPerksMain plugin, UUID uuid, String playerName) {
		super(plugin, uuid, playerName);
		this.plugin = plugin;
	}

	public MCPerksUser(MCPerksMain plugin, AdvancedCoreUser user) {
		super(plugin, user);
		this.plugin = plugin;
	}

	public long getPerkCoolDown(Perk perk) {
		try {
			return Long.valueOf(getUserData().getString(perk.getPerk() + "_CoolDown", isCacheData(), isWaitForCache()));
		} catch (NumberFormatException ex) {
			return 0;
		}
	}

	public long getPerkExperation(Perk perk) {
		try {
			return Long.parseLong(getData().getString("Experation_" + perk.getPerk(), isCacheData(), isWaitForCache()));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	public int getPerkTimesUsed(Perk perk) {
		return getUserData().getInt(perk.getPerk() + "_TimesUsed", isCacheData(), isWaitForCache());
	}

	public boolean isUseBossBar() {
		String str = getData().getString("UseBossBar", isCacheData(), isWaitForCache());
		if (str == null || str.isEmpty()) {
			return true;
		}
		return Boolean.valueOf(str);
	}

	public void setPerkCoolDown(Perk perk, long value) {
		getUserData().setString(perk.getPerk() + "_CoolDown", "" + value);
	}

	public void setPerkExperation(Perk perk, long time) {
		getData().setString("Experation_" + perk.getPerk(), "" + time);
	}

	public void setPerkTimesUsed(Perk perk, int value) {
		getUserData().setInt(perk.getPerk() + "_TimesUsed", value);
	}

	public int getPerkLimit() {
		for (int i = MCPerksMain.plugin.getPerkHandler().getLoadedPerks().size(); i > 0; i--) {
			if (hasPermission("MCPerks.Limit." + i)) {
				return i;
			}
		}
		return -1;
	}

	public int getActivePerks() {
		int perks = 0;
		for (Perk perk : MCPerksMain.plugin.getPerkHandler().getActivePerks()) {
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
		return getData().getInt("Activations", true);
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
		return getData().getInt("PerkActivations_" + perk, true);
	}

	public void setActivations(String perk, int value) {
		getData().setInt("PerkActivations_" + perk, value);
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
