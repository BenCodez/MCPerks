package com.Ben12345rocks.MCPerks.Objects;

public enum PerkSystemType {
	ALL, PLAYER, TOWNY;

	public static PerkSystemType getType(String str) {
		for (PerkSystemType t : values()) {
			if (t.toString().equalsIgnoreCase(str)) {
				return t;
			}
		}
		return ALL;
	}
}
