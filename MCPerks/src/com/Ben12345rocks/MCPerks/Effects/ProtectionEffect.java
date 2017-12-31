/*
 *
 */
package com.Ben12345rocks.MCPerks.Effects;

import java.util.Random;

import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;

import com.Ben12345rocks.MCPerks.Main;

// TODO: Auto-generated Javadoc
/**
 * The Class ProtectionEffect.
 */
@SuppressWarnings("deprecation")
public class ProtectionEffect {

	/** The player. */
	private Player player;

	/** The epf. */
	private double epf = 0.0D;

	/** The event. */
	private EntityDamageEvent event;

	/**
	 * Instantiates a new protection effect.
	 *
	 * @param event2
	 *            the event
	 */
	public ProtectionEffect(EntityDamageEvent event) {
		this.event = event;
		player = (Player) event.getEntity();
	}

	/**
	 * Debug attack.
	 *
	 * @param event
	 *            the event
	 * @param main
	 *            the main
	 */
	public void debugAttack(EntityDamageByEntityEvent event, Main main) {
		Player p = (Player) event.getEntity();
		main.getLogger().info("Original Damage: " + event.getDamage());
		main.getLogger().info("Armour Modifier: " + event.getDamage(DamageModifier.ARMOR));
		main.getLogger().info("Armor Magic Modifier: " + event.getDamage(DamageModifier.MAGIC));
		main.getLogger().info("Final Damage: " + event.getFinalDamage());
		if (p.getInventory().getHelmet() != null) {
			main.getLogger().info("Has a helmet on");
		}

		if (p.getInventory().getBoots() != null) {
			main.getLogger().info("Has boots on");
		}

		if (p.getInventory().getChestplate() != null) {
			main.getLogger().info("Has a chestplate on");
		}

		if (p.getInventory().getLeggings() != null) {
			main.getLogger().info("Has pants on");
		}

		main.getLogger().info("");
	}

	/**
	 * Gets the reduction.
	 *
	 * @return the reduction
	 */
	public double getReduction() {
		Random rand = new Random();
		if (player.getInventory().getHelmet() != null) {
			epf += 5.0D * (0.5D + 0.5D * rand.nextDouble());
		}

		if (player.getInventory().getBoots() != null) {
			epf += 5.0D * (0.5D + 0.5D * rand.nextDouble());
		}

		if (player.getInventory().getChestplate() != null) {
			epf += 5.0D * (0.5D + 0.5D * rand.nextDouble());
		}

		if (player.getInventory().getLeggings() != null) {
			epf += 5.0D * (0.5D + 0.5D * rand.nextDouble());
		}

		double finalDamage = event.getFinalDamage();
		double reduction = 0.0D;

		for (int i = 0; i < epf; ++i) {
			reduction += 0.04D * finalDamage;
			finalDamage -= 0.04D * finalDamage;
		}

		return event.getDamage() - reduction;
	}
}
