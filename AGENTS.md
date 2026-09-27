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

AdvancedCore is shaded and relocated. Before changing its version, shading, or API usage, inspect the actual dependency contract and downstream artifact. Avoid unpinned `LATEST` dependencies in reproducible release paths. Check that provided optional dependencies are not accidentally bundled and relocated libraries do not leak original packages or service metadata.

## Change and PR workflow

Keep changes focused. Before any commit, push, PR update, review reply, or other remote change, run focused checks, the full Maven package build, fresh-artifact inspection, and `git diff --check`; then inspect the complete base-to-HEAD diff. Before committing local work, also inspect the staged changes and every relevant intended unstaged or untracked change as one effective final patch.

For substantive work, obtain a fresh source-read-only review. The implementation agent verifies and fixes accepted findings, reruns validation, and obtains a new review of the updated snapshot. Do not reuse an old clean verdict after changes, and do not merge without explicit authorization.
