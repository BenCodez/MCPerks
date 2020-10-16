package com.Ben12345rocks.MCPerks.Perk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import com.Ben12345rocks.MCPerks.Main;
import com.Ben12345rocks.MCPerks.Configs.Config;
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
	private List<String> activeAttributes = Collections.synchronizedList(new ArrayList<String>());

	@Getter
	private List<BlockBreakEvent> blockBreakEvents = Collections.synchronizedList(new ArrayList<BlockBreakEvent>());

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
		activeAttributes.add(u.toString());
	}

	public boolean isActive(UUID string) {
		if (Config.getInstance().getForceClearModifiers()) {
			return true;
		}
		if (activeAttributes.contains(string.toString())) {
			return true;
		}
		return false;
	}

	public void remove(UUID string) {
		for (int i = activeAttributes.size() - 1; i >= 0; i--) {
			if (activeAttributes.get(i).equals(string.toString())) {
				activeAttributes.remove(i);
				return;
			}
		}
	}

	@Getter
	private HashMap<String, HashMap<Effect, Perk>> offlineEffects = new HashMap<String, HashMap<Effect, Perk>>();

	public void addOfflineCheck(Perk p, String uuid, Effect effect) {
		if (offlineEffects.containsKey(uuid)) {
			HashMap<Effect, Perk> effects = offlineEffects.get(uuid);
			effects.put(effect, p);
			offlineEffects.put(uuid, effects);
		} else {
			HashMap<Effect, Perk> effects = new HashMap<Effect, Perk>();
			effects.put(effect, p);
			offlineEffects.put(uuid, effects);
		}
	}

}
