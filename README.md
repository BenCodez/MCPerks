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

The `advancedcore.version` build property preserves the existing `LATEST` default. Override it explicitly when checking a published dependency version; record the resolved artifact hashes separately from the selector.

## Owner-task lifecycle integration

This PR introduces an unused scheduling foundation. Before adopting `OwnerTasks.run` or `OwnerTasks.later`, a consumer must initialize the helper before its first admission and retire it before beginning unload work:

```java
// At the beginning of the consumer's onPostLoad lifecycle callback:
OwnerTasks.initialize(this);
// At the beginning of the matching onUnLoad callback, before other teardown:
OwnerTasks.shutdown(this);
```

The consumer must install both hooks when integrating new callers; this foundation does not change `MCPerksMain` or migrate existing callers. Admission fails closed before initialization and after retirement. Reinitialization during a later startup creates a new epoch, and callbacks retained by an old epoch cannot execute against its replacement.

The helper also observes `PluginDisableEvent`, which Bukkit fires while the plugin still reports enabled, and retires pending admissions before native task cancellation can discard their callbacks. Action and retirement callbacks run outside the admission lock. Retirement callbacks are lifecycle cleanup and must be safe at the shutdown boundary; they must not require another plugin-owned scheduled callback to finish. These guarantees concern admission completion, not persistence or native Folia feature acceptance.
