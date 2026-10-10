# Maintainer and AI-agent guide

MCPerks is a Bukkit/Paper/Folia plugin built on AdvancedCore. It grants time-limited perks and effects at player, server, permission, Towny, or Factions scope and optionally integrates with VotingPlugin, Vault, RoseStacker, mcMMO, PlaceholderAPI, and other plugins.

## Build and verification

Requirements: JDK 21+ and Maven. The Maven project is in `MCPerks/`.

```shell
mvn -B -f MCPerks/pom.xml test
mvn -B -f MCPerks/pom.xml package
```

Use `package`, not `install`, for routine validation: the current install lifecycle copies the JAR into a developer-specific test-server path. Verify the fresh shaded `MCPerks.jar`, actual test discovery, and `git diff --check`. Do not use a live server as an automated build destination.

## Architecture and behavior

- `MCPerksMain` owns lifecycle, command registration, placeholders, integrations, users, and effect handlers.
- `perk/` defines perks, activation scope, expiration, affected players, and effect application.
- `userapi/` owns per-user state and persistence through AdvancedCore.
- `configs/` owns `Config.yml`, perk definitions, language, and migration/default behavior.
- `listeners/` applies and removes effects and contains optional integration listeners.
- `commands/`, GUI/editor, placeholder, and reward-edit packages expose administrator and player behavior.

The supported scope values are `ALL`, `PLAYER`, `TOWNY`, `FACTIONS`, and `PERMISSION`. Preserve their established ownership, membership, expiration, and permission semantics.

## Runtime invariants

1. A perk grant, extension, expiry, removal, reload, disconnect, or restart must not duplicate effects, leave permanent effects behind, or silently discard purchased time.
2. Time calculations must use explicit units and preserve configured expiration semantics across restarts, clock boundaries, and repeated grants.
3. Re-evaluate group/permission membership at the established lifecycle points. Towny, Factions, Vault, RoseStacker, mcMMO, VotingPlugin, and PlaceholderAPI must remain optional and guarded from absent or incompatible classes.
4. Keep Bukkit/world/player access on the appropriate Bukkit or Folia execution context. Database and filesystem work must not block a server or region thread.
5. Reload/disable must unregister listeners and retire tasks, callbacks, effects, and cached state without stale work mutating the new lifecycle.
6. GUI/editor clicks, drags, closes, and saves must recheck authorization and avoid item duplication, lost configuration, or edits applied to the wrong perk/user.
7. Reward or command integration must preserve idempotency where the caller can retry and must not grant before required state is durably recorded.
8. Keep configuration keys, perk files, placeholders, permissions, aliases, and serialized user/server data backward compatible unless a migration is explicit and tested.

9. Optional interaction effects use immutable options captured at load. Charge budgets belong to a player UUID and one
   fixed effect, require durable asynchronous admission before gameplay, and remain spent after an uncertain effect or
   crash. Never create per-reservation SQL keys or fetch perk files from gameplay listeners. Shared charge ownership is
   rejected; legacy unlimited shared perks remain supported.
10. Queue entries reserve existing entitlement without decrementing while waiting. Persist stable IDs and start fences
    before consumption; limited queued activations require shared-runtime atomic user ownership and otherwise remain
    waiting. Use confirmed asynchronous storage completion, consume one selected activation, and never replay
    an uncertain start. Self cancellation checks ownership; pause and reordering are durable. Display unknown ETA when
    eligibility or permanent/banked state prevents a reliable estimate.
11. Banked personal time is an explicit optional mode, separate from legacy absolute expirations. Lifecycle checkpoints
    consume online eligible elapsed time, including final disconnect/world/pause/reload/shutdown intervals; queries remain pure. Unsupported one-shot, periodic and persistent-effect
    activation paths are rejected. Resuming a bank never grants entitlement or replays reward/command hooks. Retired
    player or reload timer callbacks cannot remove effects from a newer session.
    Nonpositive legacy reactivation timers remain disabled and supported. A bank replacement starts its elapsed/debit
    ownership boundary only after durable publication; prior debit reservations cannot consume the replacement balance.
    Grant publication must retain concurrent pause/eligibility transitions. Every acknowledged queue/bank state file
    must remain within the same 3,000,000 serialized-byte recovery bound; capacity rejection preserves usable prior state and must precede irreversible activation consumption.
    A clock first attached during an additive publication can debit only the pre-grant balance for that wait.
12. Retire gameplay admissions and detach owner-thread effects before AdvancedCore storage retirement. Persist legacy
    restart records before clearing native expirations. Keep the ordered feature worker open through both that
    completion stage and AdvancedCore final permission-data cleanup; deferred shutdown may return before storage
    preparation settles. Cleanup failures remain observable and must not prevent other perks from being persisted.

## Drop-in upgrade and compatibility contract

Treat compatibility as a release invariant for every new feature, refactor, fix, persistence change, configuration change, integration change, and dependency change. Unless the task explicitly says otherwise, an MCPerks upgrade must remain a **drop-in JAR replacement**: administrators replace the JAR and existing perks, timers, user/server state, configuration, commands, permissions, placeholders, and integrations continue working without manual migration steps.

That default contract means:

- Existing configuration and perk definition files must remain valid. New keys must be optional, use safe defaults, and preserve established behavior when absent.
- Existing serialized user/server perk state and expiration data must remain readable. Any required migration must be automatic, idempotent, restart-safe, and preserve remaining purchased/granted time.
- Preserve commands, permissions, placeholders, aliases, public/de-facto APIs, perk scope semantics, reward behavior, and optional integration behavior unless an explicit breaking change is authorized.
- Do not require administrators to regenerate configuration, delete/recreate data, run one-off conversion commands/scripts, manually alter storage, or coordinate dependency upgrades merely to retain existing functionality.
- VotingPlugin, AdvancedCore, Vault, Towny, Factions, PlaceholderAPI, RoseStacker, mcMMO, and other optional integrations must remain optional and must not become hidden synchronized-upgrade requirements.
- Packaging changes must preserve the normal downloadable artifact and startup path without requiring extra runtime libraries unless explicitly requested.
- When a compatibility-preserving implementation is not practical, stop and surface the compatibility impact before implementing a breaking path unless the request explicitly permits it.

For compatibility-sensitive changes, add regression coverage for established configuration/state/integration behavior in addition to tests for the new feature.

## Dependencies and packaging

Startup defers native LastUpdated/version saves until strict ServerData reconciliation after predecessor drain. TimedPermissions become native-owned only after the actual AdvancedCore PermissionHandler restores the reconciled nodes through owner-scheduled native APIs; a generic empty YAML cache cannot clear recovered disk permissions. Compare the early native-handler baseline with current per-node state so successor revocations, renewals and new grants remain authoritative. Read only the bundled synchronized timedPermissionSnapshot/offlinePermissionSnapshot methods with exact schema checks; unsupported contracts fail initialization closed. Preserve pending offline nodes through the native final save without resurrecting revoked nodes. Successful bank replacement advances a FeatureState-owned debit epoch even for disconnected/reloaded/retired clocks; retained ownership is one scalar per bounded bank, never a retained list of session clocks.

Ordered ServerData snapshots include a fixed leading format comment and stay within 4,000,000 encoded bytes including that comment. Unmarked native legacy YAML may migrate once after predecessor drain, with strict parsing and no symlink/unreadable-file bypass; consumed permissions and advisory history can yield the bounded format. An indispensable larger payload cannot migrate losslessly into this bounded file. Still-active oversized permission rows are conservatively checked before publishing a cleared snapshot, the original file stays intact, and initialization/final-save failures remain observable. Offline queued permission nodes have no native public snapshot API; the guarded bundled synchronized snapshot adapter preserves their pending native ownership. Future rows remain indispensable while that owner exists. Resolve excess active permissions on the prior version or reduce other indispensable payload before upgrading; do not add a supplemental persistence engine or silently discard those fields.

AdvancedCore is shaded and relocated. Before changing its version, shading, or API usage, inspect the actual dependency contract and downstream artifact. Avoid unpinned `LATEST` dependencies in reproducible release paths. Check that provided optional dependencies are not accidentally bundled and relocated libraries do not leak original packages or service metadata.

## Change and PR workflow

Keep changes focused. Before any commit, push, PR update, review reply, or other remote change, run focused checks, the full Maven package build, fresh-artifact inspection, and `git diff --check`; then inspect the complete base-to-HEAD diff. Before committing local work, also inspect the staged changes and every relevant intended unstaged or untracked change as one effective final patch.

For substantive work, obtain a fresh source-read-only review. The implementation agent verifies and fixes accepted findings, reruns validation, and obtains a new review of the updated snapshot. Do not reuse an old clean verdict after changes, and do not merge without explicit authorization.

Queue-enabled direct activation retains the queue start fence through asynchronous bank publication, the initial player-owner attachment attempt, and cooldown confirmation. Queued bank starts retain their existing fence through the same attachment handoff. Resumed or reconnected banks wait behind unrelated active perks without consuming waiting time; queue-disabled personal banks retain parallel behavior. The first attachment attempt may finish ineligible or retired while preserving the published bank for a later eligible resume; it does not mint time or replay activation hooks.

Limited direct starts persist an existing-format `STARTING` entry before any debit and never transition to queue `WAITING`. Validate and confirm current ServerData and caller storage before the atomic debit; publish new expiration/cooldown fields only after successful consumption so a rejected start cannot restore as a free permanent grant. Definite pre-debit failure or an authoritative false debit removes that entry with unchanged credit; debit ambiguity and later failure retain uncertainty without replay/refund. Hold owner/perk/lifecycle admission through all accepted callbacks and completion publication. Queue-disabled personal banks remain parallel across owners unless the same perk has a server-wide cooldown; bank attachment must never add the purchased balance again.
