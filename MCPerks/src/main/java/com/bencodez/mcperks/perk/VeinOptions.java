package com.bencodez.mcperks.perk;

import java.util.List;
import org.bukkit.configuration.file.FileConfiguration;

/** Immutable VeinMining settings captured when a perk is loaded. */
public record VeinOptions(int blocks, int distance, List<String> materials, List<String> tools) {
    public VeinOptions {
        materials = List.copyOf(materials);
        tools = List.copyOf(tools);
    }

    public static VeinOptions read(FileConfiguration config) {
        return new VeinOptions(Math.max(1, Math.min(64, config.getInt("VeinMining.MaxBlocks", 16))),
                Math.max(1, Math.min(16, config.getInt("VeinMining.MaxDistance", 8))),
                config.getStringList("VeinMining.Materials"), config.getStringList("VeinMining.Tools"));
    }
}
