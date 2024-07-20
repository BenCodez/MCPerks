package com.bencodez.mcperks.version;

import org.bukkit.attribute.AttributeModifier;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.entity.Entity;
import org.bukkit.event.entity.EntityDeathEvent;

import com.bencodez.mcperks.MCPerksMain;

public interface VersionHandle {
	public AttributeModifier getModifier(double amount, Operation operation);

	public boolean isAttribute(MCPerksMain plugin, AttributeModifier attri);
	
	public boolean hasDamageSourceMethod();
	
	public Entity getCausingEntity(EntityDeathEvent event);
}
