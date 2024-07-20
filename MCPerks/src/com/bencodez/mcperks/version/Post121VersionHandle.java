package com.bencodez.mcperks.version;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.entity.Entity;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.EquipmentSlotGroup;

import com.bencodez.mcperks.MCPerksMain;

public class Post121VersionHandle implements VersionHandle {

	@Override
	public AttributeModifier getModifier(double amount, Operation operation) {
		return new AttributeModifier(NamespacedKey.fromString("mcperks", MCPerksMain.getInstance()), amount, operation,
				EquipmentSlotGroup.ANY);
	}

	@Override
	public boolean isAttribute(MCPerksMain plugin, AttributeModifier attri) {
		return attri.getKey().equals(NamespacedKey.fromString("mcperks", plugin));
	}

	@Override
	public boolean hasDamageSourceMethod() {
		return true;
	}

	@Override
	public Entity getCausingEntity(EntityDeathEvent event) {
		return event.getDamageSource().getCausingEntity();
	}

}
