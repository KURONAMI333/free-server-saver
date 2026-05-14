# Changelog

All notable changes to Free Server Saver are recorded here.

## [0.1.0]

Initial public release.

### Adaptive throttling
- Polls JVM heap every 2 seconds and classifies into 5 tiers
  (NORMAL / L1 / L2 / L3 / L4 EMERGENCY) at 60 / 70 / 80 / 85 %
  with 5 percentage-point hysteresis on recovery.
- **AI tick throttle**: mobs in NEAR / MID / FAR / DISTANT distance
  buckets get progressively longer tick intervals as the tier rises.
  Player-position snapshot every 5 ticks, 20-tick starvation floor.
- **Spawn throttle**: L1 rejects 50 % of natural spawns, L2+ rejects
  all; vanilla and modded spawners both covered via
  `FinalizeSpawnEvent` + `EntityJoinLevelEvent` double-cancel.
- **View / simulation distance compression**: at L3 the server
  compresses both via `PlayerList.setViewDistance` /
  `setSimulationDistance` and vanilla unloads chunks naturally.
- **Emergency despawn sweep**: at L4 entry, mobs more than 64 blocks
  from any player are discarded in one pass.
- **Tick-rate halving**: at L4, `/tick rate 10.0` runs via
  `MinecraftServer.getCommands()` until the heap recovers.
- **Item-entity throttle**: items and XP orbs not near any player
  tick on up to 32× intervals; despawn timer still progresses via
  the 40-tick starvation floor.

### ExceptionGuard — auto-quarantine
- Wraps `Level.guardEntityTick` and `LevelChunk$BoundTickingBlockEntity#tick`
  via MixinExtras `@WrapOperation`. When an entity or block-entity
  throws unhandled exceptions 3 times within 10 seconds, the entity
  is silently `discard()`ed (block-entity is removed, the block
  itself stays).
- Bosses (per BossDetection) are never discarded — exceptions get
  logged but the entity keeps ticking.
- Recent quarantine events are visible via
  `/freeserversaver quarantine`.

### Safety gates
- **BossDetection**: vanilla bosses (EnderDragon, WitherBoss, Warden,
  ElderGuardian, Ravager, Hoglin) + max-HP > 80 heuristic for modded
  bosses; villagers, traders, golems, allays; named, leashed, tamed,
  persistent mobs.
- **SafetyGate**: mobs in combat (`getTarget() != null`), fall-flying,
  climbing, fire-ticking, in motion, low-HP, in portal cooldown,
  pathfinding, baby/love mode are all excluded from throttling.
- **Random ticks (crops, leaves, snow, fire) are never touched** —
  this is intentionally left to Lithium.

### Observability
- **LagSpikeDetector**: every tick over 100 ms is recorded with its
  heap %, tier, and player count. WARN-logged and surfaced via
  `/freeserversaver lagspikes`.
- **HeapHistoryTracker**: ring buffer of the last 50 tier
  transitions; `/freeserversaver history`.
- **EnvironmentInspector**: captures heap max, heap used, CPU
  count, JVM details at server start. Interprets the heap size
  (`< 2 GB = misconfigured`, `2-3.5 GB = standard low-RAM`,
  `> 3.5 GB = RAM Boost active`); `/freeserversaver env`.
- **MetaspaceWatcher**: tracks the Metaspace pool separately from
  heap because RAM Boost extends heap but not Metaspace — the
  Metaspace OOM crash class is invisible to a heap-only monitor.
- **BootTimeTracker**: persists boot durations to a flat file so an
  approach to the 10-minute startup limit common to free hosts is visible
  before it's hit.

### low-RAM host-specific addresses
- **4 GB world cap**: `StorageMonitor` periodically scans the world
  directory; `/freeserversaver storage` shows current size vs the
  cap.
- **Chunk pruning**: `/freeserversaver prune` runs a flood-fill from
  anchored chunks (players, force-load tickets, spawn area) and
  identifies isolated loaded chunks for vanilla to unload.
- **Chunk pre-generation**: `/freeserversaver pregen <radius>`
  synchronously generates chunks in the radius around the runner.
  Yields to Chunky if installed.
- **Idle-timer notice**: a one-time welcome message on first join
  reminds players that the idle timer is reset only while
  someone is online.
- **Mob-density detector** (opt-in): scans loaded chunks and warns
  about 30+ same-type concentrations (mob-farm signature).

### Commands (`/freeserversaver *`, server-op only)
`help`, `status`, `metrics`, `history`, `env`, `lagspikes`,
`top entities`, `inspect chunks`, `tuning`, `prune`, `storage`,
`pregen <radius>`, `quarantine`.

### Coexistence with other performance mods
At server start, scans loaded mods and yields overlapping modules:
- Lithium / FerriteCore / ModernFix — stay out of their lane;
  recommended as companions
- Adaptive Performance Tweaks / Tick Dynamic / Tick Tweaks — yield
  the corresponding tick / spawn / item module
- Where's my Brain / DAB / OptimizeMod / Immersive Optimization /
  No See No Tick — yield distance-bucket AI throttle
- Chunky / chunkpregen — yield pregen
- Neruina / Bug Fix Mod / Failsafe — yield ExceptionGuard
The startup log shows which modules were yielded and to whom.

### Internationalization
9 locales: English, 日本語, Deutsch, Português (BR), Español,
简体中文, 한국어, Français, Русский. Output uses
`Component.translatable` so a Japanese client sees Japanese on a
server in any locale. Translations are machine-generated as a
starting point; native-speaker corrections via PR/issue welcome.

### Optional modules (default OFF)
- **AutoTuner**: PID-lite threshold adjustment based on observed
  lag-spike-vs-heap patterns, clamped to ±10 pp.
- **DiscordWebhookModule**: sends tier escalations to a Discord
  webhook with a 60-second rate limit; URL validated.

### Requirements
Minecraft 1.21.1, NeoForge, JDK 21. (1.21.2+ port pending — vanilla
random tick logic changed in 1.21.2 and we want to validate first.)

### Limitations
- No in-game verification has been performed against a live server
  yet; see `VERIFICATION_CHECKLIST.md`.
- No JEI / config GUI. Edit
  `serverconfig/freeserversaver-server.toml` directly.
- Translations are machine-translated.

### Attribution
See `NOTICE.md` for direct attribution to the MIT-licensed mods
whose patterns were adapted (Adaptive Performance Tweaks,
ChunkPurge, Server Stasis, Neruina) and the broader LGPL/GPL/ARR
list read for design context.
