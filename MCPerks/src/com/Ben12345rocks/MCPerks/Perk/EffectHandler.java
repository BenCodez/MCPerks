package com.Ben12345rocks.MCPerks.Perk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.bukkit.event.Listener;

import com.Ben12345rocks.MCPerks.Main;
import com.Ben12345rocks.MCPerks.UserAPI.User;

import lombok.Getter;

public class EffectHandler implements Listener {

	Main plugin = Main.plugin;

	// private HashMap<Effect, ArrayList<String>> effectedPlayers = new
	// HashMap<Effect, ArrayList<String>>();

	/*
	 * public HashMap<Effect, ArrayList<String>> getEffectedPlayers() { return
	 * effectedPlayers; }
	 * public void setEffectedPlayers(HashMap<Effect, ArrayList<String>>
	 * effectedPlayers) { this.effectedPlayers = effectedPlayers; }
	 */

	public EffectHandler() {
	}

	@Getter
	private List<UUID> activeAttributes = Collections.synchronizedList(new ArrayList<UUID>());

	public void activate(Perk perk, User user) {
		for (Effect effect : perk.getEffects()) {
			effect.runEffect(perk, user, perk.getEffectedPlayers());
		}
		if (!perk.isTimed() && !perk.isLastForever()) {
			// deactivate(perk);
		}
	}

	/**
	 * @param uuid
	 *            uuid
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

	public void add(UUID u) {
		activeAttributes.add(u);
	}

	public boolean isActive(UUID string) {
		for (UUID u : activeAttributes) {
			if (u.compareTo(string) == 0) {
				return true;
			}
		}
		return false;
	}

	public void remove(UUID string) {
		for (int i = 0; i < activeAttributes.size(); i++) {
			if (activeAttributes.get(i).compareTo(string) == 0) {
				activeAttributes.remove(i);
				return;
			}
		}
	}

}
