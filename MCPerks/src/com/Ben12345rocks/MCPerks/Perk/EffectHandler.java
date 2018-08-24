package com.Ben12345rocks.MCPerks.Perk;

import java.util.ArrayList;

import org.bukkit.event.Listener;

import com.Ben12345rocks.MCPerks.Main;
import com.Ben12345rocks.MCPerks.UserAPI.User;

public class EffectHandler implements Listener {

	Main plugin = Main.plugin;

	// private HashMap<Effect, ArrayList<String>> effectedPlayers = new
	// HashMap<Effect, ArrayList<String>>();

	/*
	 * public HashMap<Effect, ArrayList<String>> getEffectedPlayers() { return
	 * effectedPlayers; }
	 *
	 * public void setEffectedPlayers(HashMap<Effect, ArrayList<String>>
	 * effectedPlayers) { this.effectedPlayers = effectedPlayers; }
	 */

	public EffectHandler() {
	}

	public void activate(Perk perk, User user) {
		for (Effect effect : perk.getEffects()) {
			effect.runEffect(perk, user, perk.getEffectedPlayers());
		}
		if (!perk.isTimed() && !perk.isLastForever()) {
			// deactivate(perk);
		}
	}

	/**
	 * @return the flyWorlds
	 */
	public ArrayList<String> getFlyWorlds(String uuid) {
		for (Perk perk : plugin.getPerkHandler().getActivePerks()) {
			if (perk.getEffects().contains(Effect.Fly) && perk.getEffectedPlayers().contains(uuid)) {
				return perk.getFlyWorlds();
			}
		}
		return new ArrayList<String>();
	}

}
