package com.bencodez.mcperks.version;

import org.bukkit.attribute.AttributeModifier;
import org.bukkit.attribute.AttributeModifier.Operation;

import com.bencodez.mcperks.MCPerksMain;

public class Pre121VersionHandle implements VersionHandle {

	@SuppressWarnings("deprecation")
	@Override
	public AttributeModifier getModifier(double amount, Operation operation) {
		return new AttributeModifier("mcperks", amount, operation);
	}

	@Override
	public boolean isAttribute(MCPerksMain plugin, AttributeModifier attri) {
		return false;
	}

}
