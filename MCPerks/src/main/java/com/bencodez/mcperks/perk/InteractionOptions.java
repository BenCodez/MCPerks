package com.bencodez.mcperks.perk;
import java.util.List;
import org.bukkit.configuration.file.FileConfiguration;
/** Immutable options captured at perk load, never fetched from disk by a gameplay listener. */
public record InteractionOptions(boolean charged, boolean banked, int veinBlocks, int veinDistance, List<String> veinMaterials,
        List<String> veinTools, List<String> crops, List<String> precisionTargets) {
    public InteractionOptions {
        veinMaterials = List.copyOf(veinMaterials); veinTools = List.copyOf(veinTools);
        crops = List.copyOf(crops); precisionTargets = List.copyOf(precisionTargets);
        for (String target : precisionTargets) {
            Effect effect = java.util.Arrays.stream(Effect.values()).filter(candidate -> candidate.name().equalsIgnoreCase(target))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown PrecisionMode target: " + target));
            if (java.util.Set.of(Effect.Fly, Effect.DoubleJump, Effect.DoubleJumpUp, Effect.Potions, Effect.TempPermission,
                    Effect.IncreaseMaxHealth, Effect.IncreaseStrength, Effect.IncreaseLuck, Effect.MoveSpeed,
                    Effect.Commands, Effect.GlobalCommands, Effect.Rewards, Effect.CureHunger, Effect.CureSpells, Effect.HealAll,
                    Effect.RandomToolRepair, Effect.ToolRepair, Effect.ArmorRepair, Effect.RandomArmorRepair, Effect.AllRepair, Effect.RandomAllRepair, Effect.PrecisionMode).contains(effect))
                throw new IllegalArgumentException("PrecisionMode target is not an action effect: " + target);
        }
    }
    public static InteractionOptions read(FileConfiguration config) {
        return new InteractionOptions(config.getBoolean("UsageCharges.Enabled", false), config.getBoolean("BankedTime.Enabled", false),
                Math.max(1, Math.min(64, config.getInt("VeinMining.MaxBlocks", 16))),
                Math.max(1, Math.min(16, config.getInt("VeinMining.MaxDistance", 8))),
                config.getStringList("VeinMining.Materials"), config.getStringList("VeinMining.Tools"),
                config.getStringList("RightClickHarvest.Crops"), config.getStringList("PrecisionMode.TargetEffects"));
    }
}
