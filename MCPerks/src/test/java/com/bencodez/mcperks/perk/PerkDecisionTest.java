package com.bencodez.mcperks.perk;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.userapi.MCPerksUser;
import com.bencodez.mcperks.data.LocalStatistics;
import com.bencodez.mcperks.configs.Config;
import com.bencodez.advancedcore.api.rewards.RewardBuilder;
class PerkDecisionTest {
    private Perk perk(Effect effect, String owner, List<String> targets) {
        Perk perk = mock(Perk.class); when(perk.getEffects()).thenReturn(new ArrayList<>(List.of(effect)));
        when(perk.getEffectedPlayers()).thenReturn(new ArrayList<>(List.of(owner)));
        when(perk.isNotInDisabledWorld(anyString())).thenReturn(true);
        when(perk.getInteractionOptions()).thenReturn(new InteractionOptions(false, false, 16, 8, List.of(), List.of(), List.of(), targets)); return perk;
    }
    @Test void persistedRestoreUsesSavedExpirationAndSuppressesActivationHooks() {
        UUID owner = UUID.randomUUID(); MCPerksMain plugin = mock(MCPerksMain.class);
        MCPerksUser user = mock(MCPerksUser.class); when(user.getUUID()).thenReturn(owner.toString());
        when(plugin.getLocalStatistics()).thenReturn(new LocalStatistics());
        EffectHandler effects = mock(EffectHandler.class); when(plugin.getEffectHandler()).thenReturn(effects);
        Config config = mock(Config.class); when(plugin.getConfigFile()).thenReturn(config);
        when(config.getBossBarEnabled()).thenReturn(false); when(config.getLogActivation()).thenReturn(false);
        var serverData = mock(com.bencodez.mcperks.data.ServerData.class); when(plugin.getMcperksServerData()).thenReturn(serverData);
        var users = mock(com.bencodez.advancedcore.api.user.UserManager.class); when(plugin.getUserManager()).thenReturn(users);
        when(users.getUser(owner)).thenReturn(mock(com.bencodez.advancedcore.api.user.AdvancedCoreUser.class));
        var rewardHandler = mock(com.bencodez.advancedcore.api.rewards.RewardHandler.class); when(plugin.getRewardHandler()).thenReturn(rewardHandler);
        when(rewardHandler.hasRewards(any(), anyString())).thenReturn(false);
        Perk definition = mock(Perk.class), clone = mock(Perk.class);
        when(definition.clone()).thenReturn(clone); when(definition.getPerk()).thenReturn("restore");
        when(definition.getPerkType()).thenReturn(PerkSystemType.PLAYER); when(definition.getTime()).thenReturn(30);
        when(definition.isTimed()).thenReturn(true); when(definition.isLastForever()).thenReturn(false);
        when(definition.getEffects()).thenReturn(new ArrayList<>());
        var audience = new ArrayList<String>();
        when(clone.getEffectedPlayers()).thenReturn(audience); doAnswer(i -> { audience.add(i.getArgument(0)); return null; }).when(clone).addEffectedPlayer(anyString());
        when(clone.getPerk()).thenReturn("restore");
        when(clone.getPerkType()).thenReturn(PerkSystemType.PLAYER); when(clone.getTime()).thenReturn(30);
        when(clone.isTimed()).thenReturn(true); when(clone.isLastForever()).thenReturn(false);
        when(clone.getEffects()).thenReturn(new ArrayList<>());
        PerkHandler handler = new PerkHandler(plugin, List.of());
        long expiration = System.currentTimeMillis() + 20_000L;
        var language = mock(com.bencodez.mcperks.configs.Lang.class); when(language.getCountDownTimer()).thenReturn("");
        try (var langs = mockStatic(com.bencodez.mcperks.configs.Lang.class);
             var configs = mockStatic(com.bencodez.mcperks.configs.ConfigPerks.class);
             var rewards = mockConstruction(RewardBuilder.class, (mock, context) -> {
                 when(mock.setGiveOffline(false)).thenReturn(mock);
             })) {
            var perkConfig = mock(com.bencodez.mcperks.configs.ConfigPerks.class);
            configs.when(com.bencodez.mcperks.configs.ConfigPerks::getInstance).thenReturn(perkConfig);
            when(perkConfig.getData("restore")).thenReturn(new org.bukkit.configuration.file.YamlConfiguration());
            when(perkConfig.getActivationEffectPath()).thenReturn("ActivationEffect");
            langs.when(com.bencodez.mcperks.configs.Lang::getInstance).thenReturn(language);
            handler.restoreActivePerk(definition, user, new java.sql.Timestamp(expiration));
        verify(effects).activate(any(Perk.class), eq(user), eq(false));
        assertEquals(1, handler.getActivePerks().size());
        verify(clone).setExperation(eq(user), longThat(value -> value == expiration));
        assertTrue(rewards.constructed().isEmpty()); verify(serverData, never()).addPerkHistory(anyString());
        verify(clone, never()).announcePerk(any(), anyInt());
            reset(effects); handler.activePerk(definition, user, 30, null);
            verify(effects).activate(any(Perk.class), eq(user), eq(true));
            assertEquals(1, rewards.constructed().size());
            verify(rewards.constructed().get(0)).send(users.getUser(owner));
            verify(clone).announcePerk(any(), eq(30));
            verify(serverData).addPerkHistory(anyString());
        }
    }

    @Test void precisionAppliesToAffectedAudienceAndConfiguredTargetsButNotOtherPlayersOrWorlds() {
        UUID target = UUID.randomUUID(), other = UUID.randomUUID(); MCPerksMain plugin = mock(MCPerksMain.class);
        Perk precision = perk(Effect.PrecisionMode, target.toString(), List.of("VeinMining")); Perk vein = perk(Effect.VeinMining, target.toString(), List.of());
        PerkHandler handler = new PerkHandler(plugin, List.of(precision, vein)); handler.setPrecisionSneaking(target, true); handler.setPrecisionSneaking(other, true);
        assertEquals(PerkHandler.Decision.PRECISION_SUPPRESSED, handler.effectDecision(Effect.VeinMining, target.toString(), "world"));
        assertFalse(handler.effectActive(Effect.VeinMining, other.toString(), "world"));
        assertTrue(handler.effectActive(Effect.PrecisionMode, target.toString(), "world"));
        when(precision.isNotInDisabledWorld("disabled")).thenReturn(false);
        assertTrue(handler.effectActive(Effect.VeinMining, target.toString(), "disabled"));
        handler.setPrecisionSneaking(target, false); assertTrue(handler.effectActive(Effect.VeinMining, target.toString(), "world"));
    }
    @Test void depletedAttachedPrecisionDoesNotSuppressValidAutomationAndAudienceIsPerGrant() {
        UUID owner = UUID.randomUUID(), other = UUID.randomUUID(); MCPerksMain plugin = mock(MCPerksMain.class);
        Perk precision = perk(Effect.PrecisionMode, owner.toString(), List.of("VeinMining"));
        Perk vein = perk(Effect.VeinMining, owner.toString(), List.of());
        BankedPerks banks = mock(BankedPerks.class); when(plugin.getBankedPerks()).thenReturn(banks);
        when(banks.available(precision)).thenReturn(false); when(banks.available(vein)).thenReturn(true);
        PerkHandler handler = new PerkHandler(plugin, List.of(precision, vein)); handler.setPrecisionSneaking(owner, true);
        assertTrue(handler.effectActive(Effect.VeinMining, owner.toString(), "world"));
        assertTrue(handler.effectAllowed(vein, Effect.VeinMining, owner.toString(), "world"));
        assertFalse(handler.effectAllowed(vein, Effect.VeinMining, other.toString(), "world"));
        when(banks.available(precision)).thenReturn(true);
        assertFalse(handler.effectAllowed(vein, Effect.VeinMining, owner.toString(), "world"));
        verify(plugin, never()).getFeatureState();
    }
    @Test void absentPrecisionTargetsSuppressOnlyNewAutomationAndDecisionsArePure() {
        UUID target = UUID.randomUUID(); MCPerksMain plugin = mock(MCPerksMain.class);
        Perk precision = perk(Effect.PrecisionMode, target.toString(), List.of()); Perk passive = perk(Effect.NoFallDamage, target.toString(), List.of());
        PerkHandler handler = new PerkHandler(plugin, List.of(precision, passive)); handler.setPrecisionSneaking(target, true);
        for (int i = 0; i < 10; i++) assertTrue(handler.effectActive(Effect.NoFallDamage, target.toString(), "world"));
        verify(plugin, never()).getFeatureState(); verify(plugin, never()).getMcperksUserManager();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = Effect.class, names = {"Farmer", "Fortune"})
    void actualBlockBonusListenerSkipsUnavailableGrantWhileApplyingValidGrant(Effect effect) {
        UUID owner = UUID.randomUUID(); MCPerksMain plugin = mock(MCPerksMain.class);
        Perk expired = perk(effect, owner.toString(), List.of()), valid = perk(effect, owner.toString(), List.of());
        when(expired.getIncreasePercent(effect)).thenReturn(900.0); when(valid.getIncreasePercent(effect)).thenReturn(200.0);
        when(expired.getBlocks()).thenReturn(new ArrayList<>(List.of(org.bukkit.Material.WHEAT)));
        when(valid.getBlocks()).thenReturn(new ArrayList<>(List.of(org.bukkit.Material.WHEAT)));
        BankedPerks banks = mock(BankedPerks.class); when(plugin.getBankedPerks()).thenReturn(banks);
        when(banks.available(expired)).thenReturn(false); when(banks.available(valid)).thenReturn(true);
        PerkHandler handler = new PerkHandler(plugin, List.of(expired, valid)); when(plugin.getPerkHandler()).thenReturn(handler);
        var effects = mock(EffectHandler.class); when(plugin.getEffectHandler()).thenReturn(effects);
        var nativeEffects = mock(com.bencodez.mcperks.listeners.UsageEffectListener.class);
        when(plugin.getUsageEffects()).thenReturn(nativeEffects); when(nativeEffects.isNativeBreak(owner)).thenReturn(true);
        when(effects.getBlockBreakEvents()).thenReturn(new ArrayList<>());
        var player = mock(org.bukkit.entity.Player.class); when(player.getUniqueId()).thenReturn(owner);
        var world = mock(org.bukkit.World.class); when(world.getName()).thenReturn("world"); when(player.getWorld()).thenReturn(world);
        var block = mock(org.bukkit.block.Block.class); when(block.getType()).thenReturn(org.bukkit.Material.WHEAT);
        var location = new org.bukkit.Location(world, 0, 64, 0); when(block.getLocation()).thenReturn(location);
        when(block.getDrops(any())).thenAnswer(i -> List.of(new org.bukkit.inventory.ItemStack(org.bukkit.Material.WHEAT, 1)));
        var event = new org.bukkit.event.block.BlockBreakEvent(block, player);
        new com.bencodez.mcperks.listeners.EffectListeners(plugin).onBlockBreak(event);
        verify(world, times(1)).dropItemNaturally(eq(location), argThat(item -> item.getAmount() == 2));
        verify(expired, never()).getIncreasePercent(effect);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void actualMobListenerRespectsPrecisionTargetsAndRestoresLegacyExperienceWhenUnsneaking(boolean modernDamageSource) {
        UUID owner=UUID.randomUUID(); MCPerksMain plugin=mock(MCPerksMain.class);
        Perk precision=perk(Effect.PrecisionMode,owner.toString(),List.of("DoubleExperience","IncreaseMobDrops","HeadDropper"));
        Perk mob=perk(Effect.DoubleExperience,owner.toString(),List.of()); when(mob.getEffects()).thenReturn(new ArrayList<>(List.of(Effect.DoubleExperience,Effect.IncreaseMobDrops,Effect.HeadDropper)));
        when(mob.getPerkType()).thenReturn(PerkSystemType.PLAYER); when(mob.getBlackedListedMobs()).thenReturn(new ArrayList<>()); when(mob.getIncreasePercent(Effect.DoubleExperience)).thenReturn(200.0);
        PerkHandler handler=new PerkHandler(plugin,List.of(precision,mob)); when(plugin.getPerkHandler()).thenReturn(handler); handler.setPrecisionSneaking(owner,true);
        var version=mock(com.bencodez.mcperks.version.VersionHandle.class); when(plugin.getVersionHandle()).thenReturn(version); when(version.hasDamageSourceMethod()).thenReturn(modernDamageSource);
        var player=mock(org.bukkit.entity.Player.class); when(player.getUniqueId()).thenReturn(owner);
        var event=mock(org.bukkit.event.entity.EntityDeathEvent.class); var entity=mock(org.bukkit.entity.LivingEntity.class); var world=mock(org.bukkit.World.class); when(world.getName()).thenReturn("world"); when(entity.getWorld()).thenReturn(world); when(entity.getType()).thenReturn(org.bukkit.entity.EntityType.ZOMBIE);
        when(entity.getKiller()).thenReturn(player); when(event.getEntity()).thenReturn(entity); when(event.getEntityType()).thenReturn(org.bukkit.entity.EntityType.ZOMBIE); when(event.getDroppedExp()).thenReturn(10); when(version.getCausingEntity(event)).thenReturn(player);
        var listener=new com.bencodez.mcperks.listeners.EffectListeners(plugin); listener.onEntityDeath(event); verify(event,never()).setDroppedExp(anyInt()); verify(event,never()).getDrops();
        when(mob.getEffects()).thenReturn(new ArrayList<>(List.of(Effect.DoubleExperience))); handler.setPrecisionSneaking(owner,false); listener.onEntityDeath(event); verify(event).setDroppedExp(20);
    }

}
