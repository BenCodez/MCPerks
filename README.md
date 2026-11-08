# MCPerks

 ### Premium plugin on spigot, compile yourself and you will get no support.

## License
### Creative Commons Arttribution 3.0 Unported
https://github.com/Ben12345rocks/AdvancedCore/blob/master/VotingPlugin/LICENSE.txt

## Build and regression checks

Use JDK 21 or newer and Maven from the repository root:

```shell
mvn -B -f MCPerks/pom.xml test
mvn -B -f MCPerks/pom.xml package
```

Use `package` for verification; the existing `install` lifecycle copies the JAR to a development server directory.
The scheduler regression harness controls the native Bukkit boundary and exercises the bundled Spigot/Paper implementations.
The conditional entity-owner scheduling path is retained; these headless tests do not declare Folia support or replace native server validation.

The `advancedcore.version` build property defaults to `3.8.2-SNAPSHOT`, which supplies the native storage and shutdown hooks used below. Override it explicitly when checking a published dependency version; record the resolved artifact hashes separately from the selector.

## Owner-task lifecycle integration

`MCPerksMain` initializes the owner-task helper before native restoration can admit callbacks and retires it before unload work. Additional consumers must preserve the same ordering:

```java
// At the beginning of the consumer's onPostLoad lifecycle callback:
OwnerTasks.initialize(this);
// At the beginning of the matching onUnLoad callback, before other teardown:
OwnerTasks.shutdown(this);
```

Both hooks are installed in `MCPerksMain`. Keep them when adding owner-task callers. Admission fails closed before initialization and after retirement. Reinitialization during a later startup creates a new epoch, and callbacks retained by an old epoch cannot execute against its replacement.

The helper also observes `PluginDisableEvent`, which Bukkit fires while the plugin still reports enabled, and retires pending admissions before native task cancellation can discard their callbacks. Action and retirement callbacks run outside the admission lock. Retirement callbacks are lifecycle cleanup and must be safe at the shutdown boundary; they must not require another plugin-owned scheduled callback to finish. These guarantees concern admission completion, not persistence or native Folia feature acceptance.

## Confirmed native persistence

Native perk, calendar and final shutdown saves share one bounded ordered storage worker. A successor waits for its predecessor to drain and release file ownership before reconciling `ServerData.yml`. Atomic publication completes before activation effects and hooks run. Failed publication does not report a successful activation or expose a staged calendar transition as saved.

Existing native YAML is reconciled automatically. Purchased expiration times and timed permissions remain authoritative; restoration applies persistent effects without replaying activation rewards, commands or announcements. Missing or disabled perk definitions retain their shutdown records for a later valid restoration. Shutdown captures and removes native effects before retiring user storage, then keeps the file worker alive through final native saves.

The ordered snapshot format bounds encoded files to four MB. Advisory history yields capacity to runtime state; an indispensable payload that cannot fit leaves the original file intact and reports the failure. No administrator deletion or data-reset step is performed. Optional queue/bank payloads are independent of this native storage owner.

`/mcperks Reload` reports completion after the storage barrier and owner callback finish. Concurrent requests join the same pending operation; failed publication or dispatch reports failure instead of an early success message.

## Optional VeinMining

Add `VeinMining` to a perk's `Effects` to break connected eligible ore after the original block is broken. Existing perks do not gain this effect automatically.

```yaml
Effects: [VeinMining]
VeinMining:
  MaxBlocks: 16
  MaxDistance: 8
  Materials: [COAL_ORE, IRON_ORE, DIAMOND_ORE]
  Tools: [IRON_PICKAXE, DIAMOND_PICKAXE, NETHERITE_PICKAXE]
UsageCharges:
  Enabled: false
```

`MaxBlocks` includes the original block and is clamped to 1–64; `MaxDistance` is clamped to 1–16 on each coordinate. Missing limits default to 16 and 8. An empty material list permits ore materials; an empty tool list requires a pickaxe. The bounded search uses loaded chunks only and excludes blocks marked `Placed`.

Additional blocks use the native player break path, retaining protection events, tool wear and normal drop handling. Deferred work rechecks the original event, player session, held slot and tool metadata. Reload, disconnect or replacement of the listener invalidates old work; a synchronous native break remains guarded against recursive automation until it returns.

Optional personal usage charges reserve the available additional-block budget before native work. Successful additional blocks consume units; proved-unused units are refunded. A failed or exceptional native call has an uncertain outcome, so its unit remains spent and the batch stops. Charged shared perks are rejected; unlimited shared perks remain supported. This slice does not add harvesting, PrecisionMode, queues, banking or statistics. Headless tests do not establish native server or Folia acceptance.
