package com.bencodez.mcperks.commands;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.command.CommandSender;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.perk.*;
import com.bencodez.mcperks.effects.AutoPlant;
/** Uses the same decisions and static policy helpers as the production paths. */
public final class PerkDiagnostics {
    private final MCPerksMain plugin;
    public PerkDiagnostics(MCPerksMain plugin) { this.plugin = plugin; }
    private record Stored(Perk perk, long cooldown, int used, int personalCredits, boolean retained, Perk activation, boolean alreadyActive) {}
    public void explain(CommandSender sender, Player player) {
        UUID owner = player.getUniqueId();
        // Reload preserves active clones and their immutable gameplay options. Diagnose those
        // grants rather than a replacement definition that no longer governs the action.
        var retained = plugin.getPerkHandler().getActivePerks().stream()
                .filter(perk -> perk.getEffectedPlayers().contains(owner.toString())).limit(32).toList();
        Set<String> retainedNames = new HashSet<>();
        retained.forEach(perk -> retainedNames.add(perk.getPerk()));
        var perks = java.util.stream.Stream.concat(retained.stream(),
                plugin.getPerkHandler().getLoadedPerks().values().stream()
                        .filter(perk -> !retainedNames.contains(perk.getPerk())))
                .distinct().limit(32).toList();
        Map<String, Perk> definitions = new HashMap<>();
        for (Perk perk : perks) {
            Perk configured = plugin.getPerkHandler().getLoadedPerks().get(perk.getPerk());
            if (configured != null) definitions.put(perk.getPerk(), configured);
        }
        plugin.getFeatureState().storage(() -> {
            var user = plugin.getMcperksUserManager().getMCPerksUser(owner);
            List<Stored> stored = new ArrayList<>();
            for (Perk perk : perks) {
                Perk activation = definitions.getOrDefault(perk.getPerk(), perk);
                stored.add(new Stored(perk, Math.max(user.getPerkCoolDown(perk), plugin.getMcperksServerData().getData().getLong(perk.getPerk()+".CoolDown", 0)), user.getPerkTimesUsed(perk), user.getActivations(perk.getPerk()), retained.contains(perk), activation, activation.isPerkActive(user)));
            }
            return new Object[] { stored, user.getActivations(), user.isIgnorePerkEffects() };
        }).whenComplete((result, error) -> {
            if (!plugin.isEnabled()) return;
            plugin.getBukkitScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) { deliver(sender, List.of("Player is offline; banked time is paused.")); return; }
                if (error != null) { deliver(sender, List.of("Stored perk state unavailable; no diagnosis inferred.")); return; }
                @SuppressWarnings("unchecked") List<Stored> stored = (List<Stored>) result[0];
                List<String> lines = new ArrayList<>(); lines.add("Perk decisions for " + player.getName() + "; world=" + player.getWorld().getName() + "; ignored=" + result[2]);
                int permissionLimit = -1;
                for (int i = plugin.getPerkHandler().getLoadedPerks().size(); i > 0; i--) if (player.hasPermission("MCPerks.Limit." + i)) { permissionLimit = i; break; }
                boolean bypass = player.hasPermission("MCPerks.Perks.BypassCoolDown");
                for (Stored data : stored) {
                    Perk perk = data.perk(); boolean permission = player.hasPermission("MCPerks." + perk.getPerk()) || player.hasPermission("MCPerks.AllPerks");
                    int personal = data.personalCredits() - plugin.getFeatureState().reserved(owner, "PerkActivations_" + perk.getPerk());
                    int global = (Integer) result[1] - plugin.getFeatureState().reserved(owner, "Activations");
                    // Activation can target a new definition while gameplay still uses its
                    // retained clone. Match runPerk's actual duplicate predicates for that request.
                    var bank = plugin.getFeatureState().banks().get(new com.bencodez.mcperks.data.FeatureState.BankKey(owner, perk.getPerk()));
                    boolean duplicate = !plugin.getConfigFile().getAllowDuplicatePerksActive()
                            && (data.alreadyActive() || bank != null && bank.milliseconds() > 0
                                && data.activation().getInteractionOptions().banked());
                    String reason = !permission ? "missing activation permission" : !perk.isNotInDisabledWorld(player.getWorld().getName()) ? "disabled world"
                            : duplicate ? "already active; duplicate activations disabled"
                            : !bypass && data.cooldown() > System.currentTimeMillis() ? "cooldown " + (data.cooldown()-System.currentTimeMillis())/1000 + "s"
                            : perk.getLimit() > 0 && data.used() >= perk.getLimit() ? "activation limit"
                            : plugin.getConfigFile().getLimitPermission() && permissionLimit >= 0
                                && plugin.getPerkHandler().getActivePerks().stream().filter(p -> p.getEffectedPlayers().contains(owner.toString())).count() >= permissionLimit ? "permission perk limit"
                            : plugin.getConfigFile().getLimitActivations() && personal <= 0 && global <= 0 ? "no unreserved activation" : "activation checks passed";
                    lines.add(perk.getPerk() + (data.retained() ? " (retained active grant)" : " (configured definition)") + ": " + reason);
                    for (Effect effect : perk.getEffects()) {
                        if (lines.size() >= 64) break;
                        var decision = plugin.getPerkHandler().effectDecision(effect, owner.toString(), player.getWorld().getName());
                        String detail = Boolean.TRUE.equals(result[2]) ? "; effects ignored by player" : "";
                        if (effect == Effect.VeinMining) {
                            var options = perk.getInteractionOptions(); String tool = player.getInventory().getItemInMainHand().getType().name();
                            if (options.veinTools().isEmpty() ? !tool.endsWith("_PICKAXE") : options.veinTools().stream().noneMatch(t -> t.equalsIgnoreCase(tool))) detail = "; tool not allowed";
                        } else if (effect == Effect.RightClickHarvest && !AutoPlant.allows(perk, player.getInventory().getItemInMainHand(), org.bukkit.Material.WHEAT)) detail = "; tool/enchantment/crop policy restricts harvest";
                        if (effect == Effect.AutoBlockRefill || effect == Effect.VeinMining || effect == Effect.RightClickHarvest) {
                            var kind = com.bencodez.mcperks.userapi.UsageCharges.Kind.valueOf(effect.name());
                            int charges = plugin.getUsageCharges().get(owner, kind);
                            detail += perk.getInteractionOptions().charged() ? perk.getPerkType() != PerkSystemType.PLAYER ? "; charge mode rejected for shared audience" : "; charges=" + (plugin.getUsageCharges().available() ? charges : "unavailable") : "; unlimited";
                            detail += "; successful units=" + plugin.getUsageCharges().successful(owner, kind);
                        }
                        lines.add("  " + effect + ": " + decision + detail);
                    }
                    if (bank != null) lines.add("  banked=" + bank.milliseconds()/1000 + "s; manual pause=" + bank.paused());
                    if (lines.size() >= 64) { lines.add("Further perk details omitted."); break; }
                }
                var recent = plugin.getUsageEffects().recentSkip(owner);
                if (recent != null) lines.add("Latest recorded MCPerks skip: " + recent.effect() + " " + recent.reason() + "; " + Math.max(0, System.currentTimeMillis()-recent.time())/1000 + "s ago");
                int index = 1; for (var entry : plugin.getFeatureState().queue()) {
                    if (entry.owner().equals(owner)) lines.add("Queue position=" + index + "; state=" + entry.stage() + "; start=" + plugin.getQueuePlanner().startUnavailable(entry) + "; ETA=unknown"); index++;
                }
                deliver(sender, lines);
            }, player);
        });
    }
    private void deliver(CommandSender sender, List<String> lines) {
        Runnable send = () -> { for (String line : lines) sender.sendMessage(line); };
        if (sender instanceof Player player) plugin.getBukkitScheduler().runTask(plugin, send, player);
        else plugin.getBukkitScheduler().runTask(plugin, send);
    }
}
