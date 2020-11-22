package com.bencodez.mcperks.perk;

import com.bencodez.mcperks.MCPerksMain;

public enum PerkSystemType {
	ALL, PLAYER, TOWNY, FACTIONS;

	public static PerkSystemType getType(String str) {
		for (PerkSystemType t : values()) {
			if (t.toString().equalsIgnoreCase(str)) {
				return t;
			}
		}
		if (MCPerksMain.plugin.getPerkSystemType() != null) {
			return MCPerksMain.plugin.getPerkSystemType();
		}
		return ALL;
	}
}
