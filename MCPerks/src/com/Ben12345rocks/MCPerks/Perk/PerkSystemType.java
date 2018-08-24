package com.Ben12345rocks.MCPerks.Perk;

import com.Ben12345rocks.MCPerks.Main;

public enum PerkSystemType {
	ALL, PLAYER, TOWNY, FACTIONS;

	public static PerkSystemType getType(String str) {
		for (PerkSystemType t : values()) {
			if (t.toString().equalsIgnoreCase(str)) {
				return t;
			}
		}
		if (Main.plugin.getPerkSystemType() != null) {
			return Main.plugin.getPerkSystemType();
		}
		return ALL;
	}
}
