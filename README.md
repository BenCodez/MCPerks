# MCPerks

### Premium plugin on spigot, compile yourself and you will get no support.

## License

### Creative Commons Arttribution 3.0 Unported
https://github.com/Ben12345rocks/AdvancedCore/blob/master/VotingPlugin/LICENSE.txt

## Platform support

Use Bukkit/Spigot or Paper. The current plugin descriptor does not declare Folia
support, so Folia refuses to load MCPerks. The conditional Folia scheduling code
described below does not establish whole-plugin support. An actual Folia 26.2
build 7 startup check confirmed this loader rejection; no effect tests ran there.
Do not add `folia-supported` as a workaround without a complete ownership audit
and runtime validation.

## Optional interaction effects

Add any of `AutoBlockRefill`, `VeinMining`, `RightClickHarvest`, or `PrecisionMode`
to a perk's existing `Effects` list. Existing perks keep their established timed,
unlimited behavior when the new options are absent.

- `AutoBlockRefill` moves one matching item from storage inventory into the depleted
  main or off hand after vanilla placement. It preserves metadata and rechecks the
  hand, selected slot, source stack, cancellation, and current perk before moving it.
- `VeinMining` finds six-neighbour connected ore blocks. Its total block limit
  includes the original player break; only additional blocks count as successful
  units. It uses the native player break path for protection events, normal tool
  durability, enchantments, drops, and experience, with a recursion guard. It
  accesses loaded chunks only. Its conditional Folia path stays in the starting
  chunk and checks current region ownership, but MCPerks currently cannot load
  on Folia (see Platform support).
- `RightClickHarvest` accepts the main hand once, mature wheat, carrots, potatoes,
  beetroot and nether wart, and the existing AutoPlant tool, enchantment and block
  policy. The final right-click decision and the native block-break decision must
  both permit it; later interaction denial prevents reservation and gameplay.
  It reserves one inventory seed, uses the native player break path, then
  replants the same crop at age zero. A denied break restores that seed. All native
  drops are retained; seeds are not removed wholesale from the drop collection.
- `PrecisionMode` applies to the perk's affected audience while sneaking. Its
  default targets are the three new automation effects. Set
  `PrecisionMode.TargetEffects` to an explicit list to change the targets. A
  disabled world or another player's precision perk cannot suppress your effect.

Optional keys belong in the perk definition:

```yaml
Effects: [VeinMining, PrecisionMode]
VeinMining:
  MaxBlocks: 16          # total blocks, clamped to 1..64
  MaxDistance: 8         # coordinate distance from original break, clamped to 1..16
  Materials: [COAL_ORE, IRON_ORE, DIAMOND_ORE]  # empty: native ore materials
  Tools: [IRON_PICKAXE, DIAMOND_PICKAXE]       # empty: pickaxes
RightClickHarvest:
  Crops: [WHEAT, CARROTS, POTATOES, BEETROOTS, NETHER_WART]
PrecisionMode:
  TargetEffects: [VeinMining, RightClickHarvest, AutoBlockRefill]
UsageCharges:
  Enabled: false
BankedTime:
  Enabled: false
```

## Successful-use charges

Enable `UsageCharges.Enabled` separately on each **PLAYER** perk that needs charges.
Shared/global audiences have no stable personal charge owner: charged automation
for those scopes is rejected with a load warning. Leave charge mode disabled to
retain unlimited shared perks.

Balances are personal UUID plus one of the fixed effect names `AutoBlockRefill`,
`VeinMining`, or `RightClickHarvest`. They are independent of the timed perk grant:
activation, expiry, reconnect, reload, and restart never refill a balance.
Administrators explicitly grant or set them:

```text
/mcperks SetUsageCharges <player> <effect> <nonnegative amount>
/mcperks AddUsageCharges <player> <effect> <nonnegative amount>
/mcperks GetUsageCharges <effect>
```

The matching permissions are `MCPerks.SetUsageCharges`, `MCPerks.AddUsageCharges`
and `MCPerks.GetUsageCharges`; the read command is player-only. Each successful
refill, harvest, or additional mined block spends one unit. Batches reserve up to
available charges before gameplay. Protected, suppressed and proven no-op units
are not charged. An exception after a possible effect remains spent.

`usage-charges.dat` is a bounded, ordered, asynchronously written ledger. Its
atomic publication is confirmed before a charged effect can run. Outstanding
reservations remain spent after a crash and cannot be refunded by an old callback.
Unreadable or uncertain storage fails closed for charged effects; legacy unlimited
perks continue. These are local guarantees, not a transaction spanning Minecraft
world saves and the charge ledger. Limits are 10,000 personal accounts, 256 live
reservations and 256 queued storage requests. Staff command replies confirm saved
updates rather than merely submitting them.

## Queue planner

The existing `PerkQue` switch remains authoritative. The planner reserves an
existing activation while waiting and consumes only one selected personal or
shared activation at start. It never grants another activation during recovery.
Entries have stable IDs and are stored in `perk-features.dat`, with durable pause,
order, cancellation and `WAITING`/`STARTING` fences.

```text
/mcperks Queue
/mcperks CancelQueue <entry-id>
/mcperks QueuePause <true|false>
/mcperks QueueRemove <entry-id>
/mcperks QueueMove <entry-id> <position>
```

`Queue` requires `MCPerks.Queue`; self cancellation requires `MCPerks.CancelQueue`
and checks entry ownership. Staff operations require `MCPerks.Queue.Manage`.
`ClearQueue` retains its existing permission and clears waiting entries.
The view includes active remaining time when known, requester UUID, ordered
position, ID, stage and waiting time, with at most 32 queued entries displayed.
Finite absolute timers and finite queued grants receive an estimated start time
when all preceding requesters are online and their eligibility is known. ETA stays
unknown for offline requesters, uncertain eligibility, manual pause, banked time,
and permanent perks. Estimates are observational and may change.

Limited-activation queued starts require the shaded AdvancedCore shared runtime's
atomic user transaction. The bundled runtime binds this ownership to initialized
`DataStorage: SQLITE` (the default) and `MYSQL` backends. Older or custom runtimes
without a bound shared owner cannot start billed entries. Without that ownership,
entries remain waiting and show
`transactional activation storage unavailable`; no credit is consumed or start
fence claimed. Unlimited legacy queued starts remain supported. Database work
runs on the bounded storage worker, including canonical user-cache publication
after a committed debit before legacy gameplay reads. This does not make the database and queue
file one transaction. Failed or interrupted starts become `UNCERTAIN`,
never replay automatically and never refund a possibly consumed activation.
Queue admission retains the existing command/GUI authorization and granted-credit
model. Starts recheck world, cooldown and usage limits; they do not revoke admitted
entitlement when the requester later loses an activation-command permission.
Staff may explicitly remove an uncertain entry without a database refund after
investigating it. Waiting entries survive reconnect, reload and restart; one entry
per player and at most 256 total are admitted. Direct public mutation of the old
in-memory `que` field is not a durable queue admission API; use `addQue`.

Graceful shutdown seals automation admissions, cancels effects that have not
started, and asynchronously drains their proved-unused reservation releases
before closing charge storage. No server-owner thread waits for that drain;
crash recovery still keeps uncertain reservations spent.

## Personal banked time

`BankedTime.Enabled` is opt-in per **PLAYER** perk. New finite grants add durable
milliseconds to the personal bank. Existing absolute expirations remain separate;
there is no implicit conversion, expiry reset, or time granted at login/reload.
The bank counts monotonic elapsed time at lifecycle checkpoints while the player
is online, in an allowed world, not ignoring effects, and not manually paused.
Read commands and gameplay eligibility queries do not consume time. Disconnect,
world transition, manual pause, reload and normal shutdown checkpoint the final
eligible interval before retiring its clock. Legacy deactivation buttons and
commands pause the bank without changing absolute expirations or replaying hooks.

Banked mode supports passive effects read by gameplay listeners, including the
new interaction effects. It rejects command/reward/cure/repair effects, periodic
reapplication, and fly/double-jump/potion/attribute/temporary-permission effects, whose legacy
activation paths cannot safely promise pause/resume ownership. These restrictions
are reported at load and before consuming an activation. Banked resume attaches
only the existing entitlement and does not replay activation/deactivation reward
hooks, announcements or commands. New grants retain personal/server cooldown
bookkeeping. Unlimited direct grants confirm personal/server cooldown storage before
publishing new bank time. A later bank-publication failure may leave a cooldown
without added time; inspect and reconcile it rather than automatically retrying or
refunding. A successful bank write followed by attachment failure keeps the bank
and its cooldown. Configured zero cooldown and duplicate-active permission can
intentionally allow another grant; they are not delivery deduplication. Limited direct purchases persist a `STARTING` identity in the existing
bounded feature file before consuming credit. With `PerkQue` disabled, personal banks
can start alongside unrelated active or permanent perks, and different players can
start in parallel unless this perk has a shared server cooldown. Direct identities
never enter `WAITING` or run through the ordinary global queue. Known
unsupported/unavailable bank admission is rejected before credit consumption.
If a bank write fails after a confirmed debit, the retained start fence requires
admin review; it is never automatically replayed or refunded. The queue command
shows the last confirmed fences even when storage becomes unavailable.
Direct purchases validate and publish current ServerData and confirm current
caller-owned storage before the atomic credit debit. New expiration and cooldown
fields are published through the normal owner admission only after that debit
succeeds, so a rejected purchase cannot restore as a free permanent perk. A definite failure before debit, or an authoritative false
debit, consumes no credit and removes the direct fence. Once the debit may have
committed, publication or gameplay failure retains `UNCERTAIN` evidence without
automatic replay or refund. A crash or completion-save failure preserves the start
identity for review. Owner, perk and reload admission remain held until the actual
callbacks and completion publication settle. Bank publication uses the existing
capacity-before-debit transaction; attachment does not grant banked time again.
Checkpoints normally occur each second; a
crash can preserve the uncheckpointed final fraction of time.

```text
/mcperks GetBankedTime <perk>
/mcperks PauseBankedTime <perk>
/mcperks ResumeBankedTime <perk>
/mcperks SetBankedTime <player> <perk> <seconds>
```

Self commands require `MCPerks.BankedTime`; setting requires
`MCPerks.SetBankedTime`. Grants are bounded to one year, 64 banks per player and
10,000 banks total, with at most 256 live bank controllers. Players without
positive banked entitlement do not start a controller or poll user storage.
A failed or rejected bank checkpoint detaches the banked effect and stops its
controller, even if the last confirmed balance remains positive. Eligible time
starts when the effect attaches after storage admission; storage delays before
initial attachment or resume consume no bank time. Retired player-session
callbacks cannot stop a reconnecting successor.
Bank pulses use the synchronous Bukkit delay on Spigot/Paper. The conditional
Folia path uses the moving player's entity scheduler; current Folia loading is
unsupported (see Platform support). Delayed scheduler rejection or entity
retirement stops the owning controller and detaches its effect; callbacks from
retired controllers cannot attach it again.

The optional `ReActivateEffectsTimer` may be omitted or set to any nonpositive
value to keep legacy periodic reapplication disabled. `SetBankedTime` replaces
the balance at durable publication: earlier elapsed intervals and pending debit
reservations cannot consume that replacement balance. Pausing during a pending
grant publication remains paused after publication, including for later grants.
Queue and bank publications share the recovery limit of 3,000,000 serialized
bytes; a byte-capacity rejection preserves the previous file and usable state.

## Diagnostics and local statistics

`/mcperks Diagnose` (`MCPerks.Diagnose`) explains the caller's actual permission,
entitlement reservation, cooldown, activation limit, world, tool/enchantment,
ignore, precision, charge, bank and queue state. `/mcperks Diagnose <player>`
requires `MCPerks.Diagnose.Other` and an online target. The bounded latest skip
records only a known MCPerks decision; it does not identify or blame an external
protection plugin. Output is bounded to 32 perk definitions and 64 effect details.

`LocalStatistics.Enabled: true` in `Config.yml` enables local aggregate activation,
queue-wait and fixed skip-reason counters. `/mcperks LocalStatistics` requires
`MCPerks.LocalStatistics`. These counters reset on restart and send nothing to an
external service. Successful-unit totals use the same durable fixed effect ledger
as charge finalization, including unlimited effects. Counter persistence failures
do not block unlimited gameplay.

## Verification

Use JDK 21 and Maven from this checkout:

```text
mvn -B -f MCPerks/pom.xml test
mvn -B -f MCPerks/pom.xml package
```

Use `package`, not `install`; the install lifecycle copies the artifact to a
maintainer-specific test-server directory. Tests exercise real listener entry
points, inventory revalidation, native-break denial, partial budgets, recursion,
restart/duplicate reservations, queue start fences, ownership, capacity and
publication failures, and banked offline/manual/reload lifecycle behavior.

Banked usable time also rejects `DoubleJump` and `DoubleJumpUp`, whose persistent flight flags cannot safely pause with a passive bank. Vein batches retain their original selected slot and complete item metadata (ignoring only normal durability changes). Unlimited effects release gameplay admission before best-effort statistics publication; charge acknowledgements remain authoritative. Queue estimates become unknown when a reserved activation credit has been removed.

Queued legacy activation captures runtime YAML and optional activation-log messages under the runtime-data owner; only immutable snapshots and captured log writes reach the bounded feature worker. A queue start remains STARTING until publication completes, and a failed publication remains UNCERTAIN. AdvancedCore calendar writes share that ordering. Reload drains accepted snapshots before refreshing runtime files, and normal shutdown admits its final snapshot before sealing the worker. Startup reconciles the prior writer's confirmed data before restoring legacy perks, while preserving calendar fields changed during the drain. Corrupt optional queue/bank data does not disable independent legacy YAML publication. Runtime snapshots are bounded to four MB. History retains at most 256 complete activations per month within an aggregate one-MB encoded YAML budget; recent suffixes of the last retained months take priority when pruning is necessary. History is further reduced when authoritative timers, cooldowns or calendar state need the remaining snapshot capacity. A single oversized history entry is omitted rather than allowed to prevent later runtime saves. The optional activation file log keeps its existing behavior.

Direct perk starts now stage shared cooldown/activation fields in a private snapshot, publish it, then apply personal fields on the player owner and confirm caller-owned storage before running effect, reward or command hooks. Failed or oversized shared publication changes neither personal nor shared live timing fields and prevents its gameplay callback. Ordered later snapshots omit failed admissions and preserve newer explicit writes. A failure after shared publication or during personal storage confirmation is conservative: admitted timing can remain without gameplay, so reconcile it rather than blindly retrying or refunding. A crash after state publication but before gameplay can leave admitted time/cooldown without completed hooks and needs owner reconciliation; publication and arbitrary command/reward effects are not one transaction. The existing legacy restoration semantics remain unchanged, and queue starts retain their separate uncertain-start fences. Concurrent starts are fenced per player for personal perks and per perk audience for shared perks. Reload and shutdown generations invalidate delayed callbacks before they can run under a replacement lifecycle.

Bank grants reject aggregate overflow rather than truncate purchased time. The current bank capacity check, activation debit and bank publication run under one ordered worker turn; a concurrent staff Set/Add cannot occupy the checked capacity between check and debit. The owner callback attaches already published time without granting it again. Harvest requires its captured selected hotbar slot even when another slot holds an equal item.

Queued personal activation now also waits for the canonical AdvancedCore user-cache flush callback before retiring its start fence. Submission alone is not confirmation; a thrown failure or missing completion within 30 seconds leaves the entry `UNCERTAIN` for owner reconciliation. Direct purchases reject unavailable runtime/entitlement storage before debit. Direct bank admission is serialized per perk until bank, personal cooldown and server cooldown publication completes; an existing positive bank counts toward the existing duplicate-activation policy. Calendar transition begin/completion use the same ordered immutable YAML publication and retain AdvancedCore rollback semantics on failure. Additional native vein breaks and harvests retain existing Fortune/Farmer bonus behavior while recursive automation remains suppressed.

Calendar durability waits hold only a private transition view, so an earlier queue eligibility read and owner activation can acquire runtime data while calendar publication is pending. Perk snapshots captured during that wait carry the calendar result: confirmed fields survive later publication, failed begin/completion changes stay out of the live cache and later snapshots, and explicit later marker edits are preserved. Calendar and perk writes still use the same bounded worker and four-MB snapshot limit; reload defers file replacement while either is pending, and shutdown/predecessor draining includes accepted calendar writes.

Startup queue checks, direct purchases and bank attachment wait for confirmed completion of both the asynchronous all-user legacy restoration scan and shutdown-record restoration, after runtime YAML reconciliation. A failed or rejected restoration leaves queued credit unspent and banks unattached; the startup log identifies the failure. The existing `loadActivePerks()` entry point remains available.

Bank attachment and resume recheck `LimitPermission` on the player owner, including already active perks affecting that player. Each attachment immediately counts toward the next bank's check, so concurrent purchases of different banks cannot attach past the cap. Existing attached banks count once. Banks waiting for a free active-perk slot keep their remaining time, consume no waiting interval, and attach on a later pulse when eligible; resume does not grant time or replay activation hooks. Disabled limits and players without a matching limit permission retain unlimited behavior.

Precision targets must be action effects whose execution consults MCPerks eligibility. Persistent movement/attribute/potion/temporary-permission/fly/jump effects and already-executed commands, rewards, cures and repairs are rejected rather than claiming to suspend them. Unknown targets are invalid perk configuration; other configured perks continue loading.

Charge and feature stores hold an OS lock in an empty sibling `.lock` file until their accepted writes drain. This protects reloads using a fresh plugin classloader as well as normal reloads. Acquisition occurs only on the storage worker, waits up to 30 seconds for an external/classloader owner, and otherwise leaves that store unavailable without reading or publishing stale data. Do not delete lock files while the plugin is running. Deleted or disabled perk definitions in legacy shutdown records are skipped so healthy restoration and queue/bank admissions can finish.

Storage ownership acquisition failure freezes every subsequent queue, bank, charge and statistic write, including the ordered legacy publication lane. An unreadable optional feature file remains distinct from a failed ownership guard. When multiple active grants supply the same automation, a context-matching unlimited grant takes precedence; its crop/material/tool configuration still applies, and an unrelated charged grant does not block it through depletion or unavailable storage. Precision suppression resolves the Bukkit killer on servers without the modern damage-source API.

Banked action eligibility uses monotonic elapsed time and unconfirmed checkpoint debits as well as the persisted balance. Delayed storage cannot extend usable time; inspection never debits or checkpoints it. Automation callbacks capture their reload generation before scheduling, so actions queued before reload cannot mutate gameplay under a replacement generation.

Banked effects recheck every contributing grant, including passive drop/XP bonuses and PrecisionMode suppression. Pause and elapsed-time debit admission share one lifecycle boundary. Queue clearing reports success only after waiting-entry removal is durably published; the existing public void API remains available alongside completion-aware callers.

The ordered file worker remains available until both shutdown preparation and AdvancedCore's actual final-file publication stages settle. The inherited disable hook may return while either is pending; its completion hook receives the final-file stage and closes the captured FeatureState owner only after both settle, including exceptional settlement. Final YAML mutations and serialization use the runtime monitor, while publication stays on the existing ordered worker; no stage is joined under that monitor. The final permission snapshot reconciles timed and pending offline native nodes before publication, retaining renewed grants and omitting revoked nodes. Worker termination is outside these completion stages so closing the owner cannot wait on itself. Queue/bank byte-capacity rejection is checked before activation consumption. A clock attached during a pending top-up can spend only previously published time until the top-up commits; the wait never consumes newly granted time.

Startup keeps AdvancedCore's actual restored/cleared temporary-permission state authoritative, so a delayed version save cannot resurrect a permission later revoked. Bank replacement also fences elapsed debits from clocks removed by disconnect, reload or retirement while publication was pending. New clocks consume the replacement from its commit boundary and keep manual pause semantics.

Confirmed bank balances become visible in the same memory turn as their clock boundary. Clock attachment and checkpoint reservation share that boundary, so disconnect, reload or retirement cannot consume newly topped-up time for an earlier interval. Filesystem publication and staged-file cleanup stay outside the boundary; a clock attached after complete publication starts at attachment and does not charge offline waiting. Diagnose reports the options retained by active grants after reload, including their captured charge policy, rather than the replacement definitions for those grants.

The four-MB ServerData limit includes its ordered-format comment. A readable, unmarked legacy file larger than this limit can migrate automatically when already expired/revoked permission rows and advisory history allow its indispensable state to fit. New marked files retain the strict recovery limit. If still-active permissions or other indispensable legacy fields alone exceed four MB, migration leaves the original file intact and reports the unsupported payload; it cannot preserve that data inside a bounded file. Before upgrading that server, revoke or expire excess timed permissions using the prior version, or reduce other indispensable legacy payload, then stop normally and retry. Offline queued permissions are conservatively retained while their native pending owner exists. A failed reconciliation also reports a final persistence skip rather than overwriting the original file with a cleared snapshot.

Native startup defers early LastUpdated/version file saves until the original ServerData has been strictly read after predecessor shutdown work drains. Temporary permissions are reconciled against the native handler baseline and restored through server/player ownership before recovered rows are cleared. Successor revocations, renewals and new grants take precedence. The adapter validates the bundled synchronized permission snapshot methods and fails closed if their contract is unavailable; it does not require a dependency upgrade. Pending offline permissions remain represented in the ordered final save.

Queue-enabled direct activation retains the queue start fence through asynchronous bank publication, the initial player-owner attachment attempt, and cooldown confirmation. Queued bank starts retain their existing fence through the same attachment handoff. Resumed or reconnected banks wait behind unrelated active perks without consuming waiting time; queue-disabled personal banks retain parallel behavior. The first attachment attempt may finish ineligible or retired while preserving the published bank for a later eligible resume; it does not mint time or replay activation hooks.

PrecisionMode resamples already-connected players on their entity owner at enable and reload, and samples new connections on join. Sneaking state is tied to that connection and reload generation; old callbacks and quit cleanup cannot change a replacement connection. No extra sneak toggle or automation action is required before configured legacy targets are suppressed.

Shutdown perk records for missing or disabled definitions are retained for later startup restoration. Legacy absolute expiry continues while unavailable; restoration preserves its original deadline and retires an already expired record without activating it. Successfully handled records are removed individually; an activation failure keeps restoration unavailable rather than admitting queued perks over uncertain active state. This does not promise atomic recovery across gameplay effects and file publication.

Persisted timed perks restored after startup/reload keep their saved expiration and reapply persistent effects. Restoration does not replay activation rewards, player/global commands, activation announcements, countdown rewards, history entries or activation statistics. Newly granted perks retain their existing activation hooks; configured periodic reactivation still uses its existing cadence.

Direct activation holds one audience/owner admission before consuming an activation allowance and through asynchronous confirmation. A repeated request while that activation is pending consumes no second credit. Reload retires pending callbacks from the previous configuration generation; an uncertain already-consumed activation is not automatically refunded or replayed. Personal owners keep independent admission fences, while shared audience ownership retains its established scope.


### AdvancedCore shutdown dependency

This candidate pins the bundled AdvancedCore dependency to `3.8.2-SNAPSHOT` instead of resolving `LATEST`. It requires the pre-storage shutdown hook, completion-aware final-file shutdown hook, and additive `ServerData.mutateFinalData` owner contract from [AdvancedCore PR #360](https://github.com/BenCodez/AdvancedCore/pull/360). Merge/build that producer change before building this MCPerks candidate from remote dependencies; an older snapshot with the same version cannot compile these lifecycle overrides. Test builds use the exact locally built producer artifact and record its SHA-256; the version alone is not an immutable artifact pin. MCPerks shades AdvancedCore, so server owners do not install a separate AdvancedCore plugin. No manual player-data migration is required. The PR remains dependent on the producer change and its snapshot availability; a green test build using a local candidate does not prove remote artifact availability.

During disable, MCPerks captures legacy perk state and detaches native effects on the shutdown owner before asynchronous storage retirement. Bank checkpoints and expiration/cooldown confirmation run on the canonical storage worker; the final file owner stays available through inherited permission cleanup. User mutation setters and their flush confirmation use the same ordered AdvancedCore worker, preventing confirmation of an empty cache before queued changes arrive.

After reload, Diagnose uses the current configured definition for new activation world and usage-limit checks, while effect details keep the options captured by the running grant. Inspection shares the production activation-limit predicate and does not debit or update stored state.
