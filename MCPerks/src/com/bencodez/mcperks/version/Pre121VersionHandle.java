package com.bencodez.mcperks.version;

import org.bukkit.attribute.AttributeModifier;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.entity.Entity;
import org.bukkit.event.entity.EntityDeathEvent;

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

	@Override
	public boolean hasDamageSourceMethod() {
		return false;
	}

	@Override
	public Entity getCausingEntity(EntityDeathEvent event) {
		return null;
	}

}
