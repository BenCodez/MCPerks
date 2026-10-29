package com.bencodez.mcperks.userapi;

import java.util.UUID;

import org.bukkit.entity.Player;

import com.bencodez.advancedcore.api.user.AdvancedCoreUser;
import com.bencodez.mcperks.MCPerksMain;
import com.bencodez.mcperks.perk.Perk;

// TODO: Auto-generated Javadoc
/**
 * The Class User.
 */
public class MCPerksUser extends com.bencodez.advancedcore.api.user.AdvancedCoreUser {

	@SuppressWarnings("unused")
	private MCPerksMain plugin;

	@Deprecated
	public MCPerksUser(MCPerksMain plugin, Player player) {
		super(plugin, player);
		this.plugin = plugin;
	}

	@Deprecated
	public MCPerksUser(MCPerksMain plugin, String playerName) {
		super(plugin, playerName);
		this.plugin = plugin;
	}

	@Deprecated
	public MCPerksUser(MCPerksMain plugin, UUID uuid) {
		super(plugin, uuid);
		this.plugin = plugin;
	}

	@Deprecated
	public MCPerksUser(MCPerksMain plugin, UUID uuid, boolean loadName) {
		super(plugin, uuid, loadName);
		this.plugin = plugin;
	}

	@Deprecated
	public MCPerksUser(MCPerksMain plugin, UUID uuid, String playerName) {
		super(plugin, uuid, playerName);
		this.plugin = plugin;
	}

	public MCPerksUser(MCPerksMain plugin, AdvancedCoreUser user) {
		super(plugin, user);
		this.plugin = plugin;
	}

	public long getPerkCoolDown(Perk perk) {
		try {
			return Long.valueOf(getUserData().getString(perk.getPerk() + "_CoolDown"));
		} catch (NumberFormatException ex) {
			return 0;
		}
	}

	public long getPerkExperation(Perk perk) {
		try {
			return Long.parseLong(getData().getString("Experation_" + perk.getPerk()));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	public int getPerkTimesUsed(Perk perk) {
		return getUserData().getInt(perk.getPerk() + "_TimesUsed");
	}

	public boolean isUseBossBar() {
		String str = getData().getString("UseBossBar");
		if (str == null || str.isEmpty()) {
			return true;
		}
		return Boolean.valueOf(str);
	}

	public void setPerkCoolDown(Perk perk, long value) {
		getUserData().setString(perk.getPerk() + "_CoolDown", "" + value);
	}

	public void setPerkExperation(Perk perk, long time) {
		getData().setString("Experation_" + perk.getPerk(), "" + time);
	}

	public void setPerkTimesUsed(Perk perk, int value) {
		getUserData().setInt(perk.getPerk() + "_TimesUsed", value);
	}

	public int getPerkLimit() {
		for (int i = MCPerksMain.plugin.getPerkHandler().getLoadedPerks().size(); i > 0; i--) {
			if (hasPermission("MCPerks.Limit." + i)) {
				return i;
			}
		}
		return -1;
	}

	public int getActivePerks() {
		int perks = 0;
		for (Perk perk : MCPerksMain.plugin.getPerkHandler().getActivePerks()) {
			if (perk.getActivater().getPlayerName().equals(getPlayerName())) {
				perks++;
			} else if (perk.getEffectedPlayers().contains(getUUID())) {
				perks++;
			}
		}
		return perks;
	}

	public void setUseBossBar(boolean b) {
		getData().setString("UseBossBar", "" + b);
	}

	public int getActivations() {
		return getData().getInt("Activations");
	}

	public void setActivations(int value) {
		if (!mutateSharedCredit("Activations", value, com.bencodez.advancedcore.api.user.usercache.UserDataCache.IntegerMutation.SET))
			getData().setInt("Activations", value);
	}

	public void addActivation(int add) {
		if (!mutateSharedCredit("Activations", add, com.bencodez.advancedcore.api.user.usercache.UserDataCache.IntegerMutation.ADD))
			setActivations(getActivations() + add);
	}

	public void addActivation() {
		addActivation(1);
	}

	public int getActivations(String perk) {
		return getData().getInt("PerkActivations_" + perk);
	}

	public void setActivations(String perk, int value) {
		if (!mutateSharedCredit("PerkActivations_" + perk, value, com.bencodez.advancedcore.api.user.usercache.UserDataCache.IntegerMutation.SET))
			getData().setInt("PerkActivations_" + perk, value);
	}

	public void addActivation(String perk, int add) {
		if (!mutateSharedCredit("PerkActivations_" + perk, add, com.bencodez.advancedcore.api.user.usercache.UserDataCache.IntegerMutation.ADD))
			setActivations(perk, getActivations(perk) + add);
	}

	public void addActivation(String perk) {
		addActivation(perk, 1);
	}

	private static final class AwardReceipt extends java.util.concurrent.CompletableFuture<Void> {
		@Override public boolean cancel(boolean interrupt) { return false; }
		@Override public boolean complete(Void value) { return false; }
		@Override public boolean completeExceptionally(Throwable failure) { return false; }
		@Override public void obtrudeValue(Void value) { throw new UnsupportedOperationException("read-only receipt"); }
		@Override public void obtrudeException(Throwable failure) { throw new UnsupportedOperationException("read-only receipt"); }
		@Override public java.util.concurrent.CompletableFuture<Void> completeAsync(java.util.function.Supplier<? extends Void> supplier) { throw new UnsupportedOperationException("read-only receipt"); }
		@Override public java.util.concurrent.CompletableFuture<Void> completeAsync(java.util.function.Supplier<? extends Void> supplier, java.util.concurrent.Executor executor) { throw new UnsupportedOperationException("read-only receipt"); }
		@Override public java.util.concurrent.CompletableFuture<Void> orTimeout(long timeout, java.util.concurrent.TimeUnit unit) { throw new UnsupportedOperationException("read-only receipt"); }
		@Override public java.util.concurrent.CompletableFuture<Void> completeOnTimeout(Void value, long timeout, java.util.concurrent.TimeUnit unit) { throw new UnsupportedOperationException("read-only receipt"); }
		private void succeed() { super.complete(null); }
		private void fail(Throwable failure) { super.completeExceptionally(failure); }
	}
	/** Apply an award on a storage worker. Retain the non-cancellable receipt for its actual outcome.
	 * Custom integer callbacks require an explicit compatible receipt contract. */
	public java.util.concurrent.CompletionStage<Void> addActivationAsync(int amount) {
		return mutateActivationAsyncInternal(null, amount, com.bencodez.advancedcore.api.user.usercache.UserDataCache.IntegerMutation.ADD);
	}

	/** Apply a perk-specific award and expose its completion to asynchronous callers. */
	public java.util.concurrent.CompletionStage<Void> addActivationAsync(String perk, int amount) {
		return mutateActivationAsyncInternal(java.util.Objects.requireNonNull(perk, "perk"), amount, com.bencodez.advancedcore.api.user.usercache.UserDataCache.IntegerMutation.ADD);
	}

	public java.util.concurrent.CompletionStage<Void> setActivationsAsync(int value) {
		return mutateActivationAsyncInternal(null, value, com.bencodez.advancedcore.api.user.usercache.UserDataCache.IntegerMutation.SET);
	}
	public java.util.concurrent.CompletionStage<Void> setActivationsAsync(String perk, int value) {
		return mutateActivationAsyncInternal(java.util.Objects.requireNonNull(perk, "perk"), value, com.bencodez.advancedcore.api.user.usercache.UserDataCache.IntegerMutation.SET);
	}
	public java.util.concurrent.CompletionStage<Void> removeActivationClampedAsync(int amount) {
		try { return mutateActivationAsyncInternal(null, Math.negateExact(amount), com.bencodez.advancedcore.api.user.usercache.UserDataCache.IntegerMutation.ADD_FLOOR_ZERO); }
		catch (ArithmeticException invalid) { return java.util.concurrent.CompletableFuture.failedFuture(invalid); }
	}
	public java.util.concurrent.CompletionStage<Void> removeActivationClampedAsync(String perk, int amount) {
		try { return mutateActivationAsyncInternal(java.util.Objects.requireNonNull(perk, "perk"), Math.negateExact(amount), com.bencodez.advancedcore.api.user.usercache.UserDataCache.IntegerMutation.ADD_FLOOR_ZERO); }
		catch (ArithmeticException invalid) { return java.util.concurrent.CompletableFuture.failedFuture(invalid); }
	}

	/**
	 * Commit an award and its replay identity together when shared SQL owns the user.
	 * The identity is retained until checkpoint cleanup; callers must not replay
	 * that identity after their durable checkpoint has completed.
	 */
	public java.util.concurrent.CompletionStage<Void> addReplayActivationAsync(String perk, int amount, String identity) {
		try {
			var owner = getPlugin(); var users = owner == null ? null : owner.getUserManager();
			var manager = users == null ? null : users.getDataManager();
			if (manager == null || !manager.usesSharedSqlStorage(owner.getStorageType()))
				return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("Replay award transactions require shared SQL"));
			if (!hasCanonicalUserLookup(users))
				return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("Replay awards require a canonical user manager"));
			if (!getData().supportsAtomicReplayTransactions())
				return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("User data does not support replay transactions"));
			String key = perk == null ? "Activations" : "PerkActivations_" + perk;
			var initial = java.util.Map.<String, com.bencodez.simpleapi.sql.data.DataValue>of(
					key, new com.bencodez.simpleapi.sql.data.DataValueInt(0), ReplayAwardLedger.COLUMN,
					new com.bencodez.simpleapi.sql.data.DataValueString(""));
			UUID uuid = UUID.fromString(getUUID()); var receipt = new AwardReceipt();
			manager.acquireSharedReplayLease(uuid, identity);
			try { var operation = manager.submitSharedReplayTransaction(uuid, owner.getStorageType(), initial,
					scope -> { manager.requireSharedReplayAwardActive(uuid, identity); return ReplayAwardLedger.apply(scope, key, amount, identity); });
			var settled = new AwardReceipt();
			operation.whenComplete((applied, failure) -> {
				try { manager.settleSharedReplayLease(uuid, identity, failure == null, false); }
				catch (RuntimeException | Error uncertain) { try { manager.recordSharedStorageFailure(uncertain); } catch (RuntimeException | Error ignored) { } return; }
				if (failure != null) { settled.fail(failure); return; }
				if (Boolean.TRUE.equals(applied)) try {
					manager.dispatchSharedUserDataNotification(() -> users.onChange(this, key));
				} catch (RuntimeException | Error notificationFailure) {
					try { manager.recordSharedStorageFailure(notificationFailure); } catch (RuntimeException | Error ignored) { }
				}
				settled.succeed();
			});
			settled.whenComplete((ignored, failure) -> manager.afterSharedMutationFinalization(operation).whenComplete((done, finalizationFailure) -> {
				if (failure != null) receipt.fail(failure); else if (finalizationFailure != null) receipt.fail(finalizationFailure); else receipt.succeed();
			}));
			} catch (RuntimeException | Error failure) { manager.settleSharedReplayLease(uuid, identity, false, false); throw failure; }
			return receipt;
		} catch (RuntimeException | Error failure) { return java.util.concurrent.CompletableFuture.failedFuture(failure); }
	}

	/** Retire an award identity only after the reward replay checkpoint is durable. */
	public java.util.concurrent.CompletionStage<Void> checkpointReplayActivationAsync(String identity) {
		try {
			var owner = getPlugin(); var users = owner == null ? null : owner.getUserManager();
			var manager = users == null ? null : users.getDataManager();
			if (manager == null || !manager.usesSharedSqlStorage(owner.getStorageType()))
				return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("Replay award transactions require shared SQL"));
			if (!hasCanonicalUserLookup(users))
				return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("Replay checkpoints require a canonical user manager"));
			if (!getData().supportsAtomicReplayTransactions())
				return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("User data does not support replay transactions"));
			UUID uuid = UUID.fromString(getUUID()); var receipt = new AwardReceipt();
			manager.acquireSharedReplayCheckpointLease(uuid, identity);
			try { var operation = manager.submitSharedReplayCheckpoint(uuid, owner.getStorageType(), identity,
					java.util.Map.of(ReplayAwardLedger.COLUMN, new com.bencodez.simpleapi.sql.data.DataValueString("")),
					scope -> { ReplayAwardLedger.retire(scope, identity); return null; });
			var settled = new AwardReceipt();
			operation.whenComplete((ignored, failure) -> {
				try { manager.settleSharedReplayLease(uuid, identity, false, failure == null); }
				catch (RuntimeException | Error uncertain) { try { manager.recordSharedStorageFailure(uncertain); } catch (RuntimeException | Error ignoredFailure) { } return; }
				if (failure == null) settled.succeed(); else settled.fail(failure);
			});
			settled.whenComplete((ignored, failure) -> manager.afterSharedMutationFinalization(operation).whenComplete((done, finalizationFailure) -> {
				if (failure != null) receipt.fail(failure); else if (finalizationFailure != null) receipt.fail(finalizationFailure); else receipt.succeed();
			}));
			} catch (RuntimeException | Error failure) { manager.settleSharedReplayLease(uuid, identity, false, false); throw failure; }
			return receipt;
		} catch (RuntimeException | Error failure) { return java.util.concurrent.CompletableFuture.failedFuture(failure); }
	}

	private java.util.concurrent.CompletionStage<Void> mutateActivationAsyncInternal(String perk, int amount,
			com.bencodez.advancedcore.api.user.usercache.UserDataCache.IntegerMutation operation) {
		try {
			var owner = getPlugin(); var users = owner == null ? null : owner.getUserManager();
			var manager = users == null ? null : users.getDataManager();
			if (manager == null) return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("User storage is unavailable"));
			if (!usesCanonicalCreditCallbacks(users))
				return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("Observable awards require canonical user and integer callbacks"));
			// Strict row reads do not authorize bypassing custom integer callbacks.
			// This new receipt API can reject before admission when that contract is unknown.
			var data = getData();
			if (!hasCanonicalIntegerCallbacks(data))
				return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("Observable awards require canonical integer callbacks"));
			String key = perk == null ? "Activations" : "PerkActivations_" + perk;
			if (manager.usesSharedSqlStorage(owner.getStorageType())) {
				var receipt = new AwardReceipt();
				manager.submitSharedIntegerMutation(UUID.fromString(getUUID()), java.util.Map.of(key, amount),
						operation,
						keys -> users.onChange(this, keys.toArray(String[]::new))).whenComplete((ignored, failure) -> {
					if (failure == null) receipt.succeed(); else receipt.fail(failure);
				});
				return receipt;
			}
			if (!manager.usesSharedSqlStorage(owner.getStorageType()) && getData().supportsStrictCompleteValues()) {
				var receipt = new AwardReceipt();
				manager.submitLegacyIntegerMutation(UUID.fromString(getUUID()), java.util.Map.of(key, amount),
						operation,
						keys -> users.onChange(this, keys.toArray(String[]::new))).whenComplete((ignored, failure) -> {
					if (failure == null) receipt.succeed(); else receipt.fail(failure);
				});
				return receipt;
			}
			// Opaque callbacks may throw after changing data. Do not report a
			// terminal failure for an operation that may already have applied.
			// Existing synchronous calls remain available.
			return java.util.concurrent.CompletableFuture.failedFuture(
					new UnsupportedOperationException("Observable award receipts are unavailable for this storage provider"));
		} catch (RuntimeException | Error failure) { return java.util.concurrent.CompletableFuture.failedFuture(failure); }
	}

	private static boolean hasCanonicalUserLookup(com.bencodez.advancedcore.api.user.UserManager users) {
		try { return users.getClass().getMethod("getUser", UUID.class, boolean.class).getDeclaringClass()
				== com.bencodez.advancedcore.api.user.UserManager.class; }
		catch (NoSuchMethodException | SecurityException unavailable) { return false; }
	}

	private static boolean hasCanonicalIntegerCallbacks(com.bencodez.advancedcore.api.user.UserData data) {
		if (data == null) return false;
		try {
			var type = data.getClass(); var base = com.bencodez.advancedcore.api.user.UserData.class;
			var mode = com.bencodez.advancedcore.api.user.UserDataFetchMode.class;
			var storage = com.bencodez.advancedcore.api.user.UserStorage.class;
			return type.getMethod("getInt", String.class).getDeclaringClass() == base
					&& type.getMethod("getInt", String.class, int.class, mode).getDeclaringClass() == base
					&& type.getMethod("getInt", storage, String.class, int.class, mode).getDeclaringClass() == base
					&& type.getMethod("setInt", String.class, int.class).getDeclaringClass() == base
					&& type.getMethod("setInt", String.class, int.class, boolean.class).getDeclaringClass() == base
					&& type.getMethod("setInt", storage, String.class, int.class, boolean.class).getDeclaringClass() == base
					&& type.getMethod("setInt", storage, String.class, int.class, boolean.class, boolean.class).getDeclaringClass() == base;
		} catch (NoSuchMethodException | SecurityException unavailable) { return false; }
	}

	private boolean usesCanonicalCreditCallbacks(com.bencodez.advancedcore.api.user.UserManager users) {
		try {
			return hasCanonicalUserLookup(users)
					&& getClass().getMethod("getData").getDeclaringClass()
							== com.bencodez.advancedcore.api.user.AdvancedCoreUser.class
					&& hasCanonicalIntegerCallbacks(getData());
		} catch (NoSuchMethodException | SecurityException unavailable) { return false; }
	}

	/** Reserve both positive pools together when the shared cache owns storage. */
	public com.bencodez.advancedcore.api.user.usercache.UserDataCache.IntegerMutationResult reserveSharedActivations(String perk) {
		var owner = getPlugin(); var users = owner == null ? null : owner.getUserManager();
		var manager = users == null ? null : users.getDataManager();
		if (manager == null || !usesCanonicalCreditCallbacks(users)) return null;
		if (!manager.usesSharedSqlStorage(owner.getStorageType())) {
			if (!getData().supportsStrictCompleteValues()) return null;
			UUID id = UUID.fromString(getUUID());
			var values = java.util.Map.of("Activations", -1, "PerkActivations_" + perk, -1);
			java.util.function.Consumer<java.util.Set<String>> notice = keys -> users.onChange(this, keys.toArray(String[]::new));
			var result = manager.tryMutateLegacyIntegers(id, values,
					com.bencodez.advancedcore.api.user.usercache.UserDataCache.IntegerMutation.DEBIT_POSITIVE, notice);
			if (result == null) {
				var population = manager.populateCompleteLegacyCacheIfSupported(id);
				if (population == com.bencodez.advancedcore.api.user.usercache.UserDataManager.LegacyPopulationResult.UNSUPPORTED) return null;
				if (population == com.bencodez.advancedcore.api.user.usercache.UserDataManager.LegacyPopulationResult.PUBLISHED)
					result = manager.tryMutateLegacyIntegers(id, values,
							com.bencodez.advancedcore.api.user.usercache.UserDataCache.IntegerMutation.DEBIT_POSITIVE, notice);
			}
			if (result == null) throw new IllegalStateException("Activation credit balance is unavailable");
			return result;
		}
		if (manager.mustDeferSharedStorageAccess()) throw new IllegalStateException("Activation reservation requires a storage worker");
		UUID id = UUID.fromString(getUUID()); var cache = manager.getPublishedCache(id);
		if (cache == null) { manager.cacheUser(id); cache = manager.getPublishedCache(id); }
		if (cache == null) throw new IllegalStateException("Activation credit cache is unavailable");
		if (cache.getIfPresent("Activations") == null) cache.seedMissingIntegerFromCompleteSnapshot("Activations");
		if (cache.getIfPresent("PerkActivations_" + perk) == null) cache.seedMissingIntegerFromCompleteSnapshot("PerkActivations_" + perk);
		var result = cache.tryMutateIntegers(java.util.Map.of("Activations", -1, "PerkActivations_" + perk, -1),
				com.bencodez.advancedcore.api.user.usercache.UserDataCache.IntegerMutation.DEBIT_POSITIVE,
				keys -> users.onChange(this, keys.toArray(String[]::new)));
		if (result == null) throw new IllegalStateException("Activation credit balance is unavailable");
		return result;
	}

	public void removeActivationClamped(int amount) {
		int delta = Math.negateExact(amount);
		if (!mutateSharedCredit("Activations", delta, com.bencodez.advancedcore.api.user.usercache.UserDataCache.IntegerMutation.ADD_FLOOR_ZERO)) {
			addActivation(delta); if (getActivations() < 0) setActivations(0);
		}
	}

	public void removeActivationClamped(String perk, int amount) {
		int delta = Math.negateExact(amount);
		if (!mutateSharedCredit("PerkActivations_" + perk, delta, com.bencodez.advancedcore.api.user.usercache.UserDataCache.IntegerMutation.ADD_FLOOR_ZERO)) {
			addActivation(perk, delta); if (getActivations(perk) < 0) setActivations(perk, 0);
		}
	}

	/** Apply shared credit arithmetic in the canonical cache, never by reading then writing. */
	private boolean mutateSharedCredit(String key, int amount,
			com.bencodez.advancedcore.api.user.usercache.UserDataCache.IntegerMutation operation) {
		var owner = getPlugin();
		var users = owner == null ? null : owner.getUserManager();
		var manager = users == null ? null : users.getDataManager();
		if (manager == null || !usesCanonicalCreditCallbacks(users)) return false;
		if (!manager.usesSharedSqlStorage(owner.getStorageType())) {
			if (!getData().supportsStrictCompleteValues()) return false;
			UUID id = UUID.fromString(getUUID());
			var result = manager.tryMutateLegacyIntegers(id, java.util.Map.of(key, amount), operation,
					keys -> users.onChange(this, keys.toArray(String[]::new)));
			if (result == null) {
				manager.submitLegacyIntegerMutation(id, java.util.Map.of(key, amount), operation,
						keys -> users.onChange(this, keys.toArray(String[]::new))).whenComplete((ignored, failure) -> {
							if (failure != null) try { manager.recordSharedStorageFailure(failure); }
							catch (RuntimeException | Error ignoredFailure) { }
						});
			}
			return true;
		}
		UUID id = UUID.fromString(getUUID());
		java.util.function.Consumer<java.util.Set<String>> notice = keys -> users.onChange(this, keys.toArray(String[]::new));
		var cache = manager.getPublishedCache(id);
		if (cache != null && cache.getIfPresent(key) == null) cache.seedMissingIntegerFromCompleteSnapshot(key);
		if (cache != null && cache.tryMutateIntegers(java.util.Map.of(key, amount), operation, notice) != null) return true;
		Runnable deferred = () -> {
			manager.cacheUser(id);
			var ready = manager.getPublishedCache(id);
			if (ready != null && ready.getIfPresent(key) == null) ready.seedMissingIntegerFromCompleteSnapshot(key);
			if (ready == null || ready.tryMutateIntegers(java.util.Map.of(key, amount), operation, notice) == null)
				throw new IllegalStateException("Activation credit cache is unavailable for the requested key");
		};
		if (manager.mustDeferSharedStorageAccess()) {
			// The legacy void method acknowledges admission, not durable completion.
			// Keep one producer-owned operation and record failure without resubmission.
			manager.submitSharedIntegerMutation(id, java.util.Map.of(key, amount), operation, notice)
					.whenComplete((ignored, failure) -> {
						if (failure != null) try { manager.recordSharedStorageFailure(failure); }
						catch (RuntimeException | Error ignoredFailure) { }
					});
			return true;
		}
		deferred.run();
		return true;
	}

	public boolean isIgnorePerkEffects() {
		return getData().getBoolean("IgnoreEffects");
	}

	public void setIgnoreEffects(boolean value) {
		getData().setBoolean("IgnoreEffects", value);
	}

}
