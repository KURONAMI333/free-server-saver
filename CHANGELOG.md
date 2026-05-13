# Changelog

All notable changes to Free Server Saver.

## [0.1.0] — Unreleased

First public release. Built, attribution complete, awaiting in-game
verification (see `VERIFICATION_CHECKLIST.md`).

### Added — Core throttling
- **HeapMonitor**: polls JVM heap every 2 seconds via `MemoryMXBean`,
  classifies into 5 tiers (NORMAL / L1_MILD / L2_HEAVY / L3_AGGRESSIVE /
  L4_EMERGENCY) with 5pp hysteresis on falling edge.
- **EntityTickThrottleModule**: distance-bucketed AI tick throttle.
  Mobs are placed in NEAR / MID / FAR / DISTANT buckets relative to
  the nearest player, and have their `EntityTickEvent.Pre` cancelled
  on a tick-interval matrix that scales with heap tier. 5-tick player-
  position snapshot. 20-tick starvation floor.
- **SpawnThrottleModule**: cancels mob spawns via
  `FinalizeSpawnEvent.setSpawnCancelled() + EntityJoinLevelEvent.setCanceled()`
  double-cancel idiom. L1 rejects 50%, L2+ rejects all natural spawns.
- **ChunkUnloadModule**: at L3+, compresses view-distance and
  simulation-distance via `PlayerList.setViewDistance()` /
  `setSimulationDistance()`. Vanilla unloads naturally. No mixin.
- **DespawnModule**: at L4 entry, single-shot sweep that
  `Entity.discard()`s mobs more than 64 blocks from any player.
- **TickRateModule**: at L4, runs `/tick rate 10.0` via
  `MinecraftServer.getCommands()`. Half-speed server during emergency.
- **ItemEntityThrottleModule**: separate from mob throttle, applies
  more aggressive intervals (up to 32×) to ground-resting items and
  XP orbs that are not near any player. Despawn timer still
  progresses via 40-tick starvation floor.

### Added — Safety gates
- **BossDetection** (`util/BossDetection.java`): identity check —
  vanilla bosses (EnderDragon, WitherBoss, Warden, ElderGuardian,
  Ravager, Hoglin) + max-HP > 80 heuristic for modded bosses; plus
  important NPCs (Villager, Trader, Golem, Allay); plus player-
  protected (named, leashed, tamed, persistent).
- **SafetyGate** (`util/SafetyGate.java`): state check — projectiles,
  lightning, TNT, fireworks excluded as classes; mob is in combat,
  fall-flying, climbing, fire-ticking, low-HP, in portal cooldown,
  pathfinding, in motion, baby/love mode, etc.

### Added — Observability
- **LagSpikeDetector**: breadcrumb trail of ticks exceeding 100ms,
  with heap%, tier, and player count captured at the moment of the
  spike. Logged to WARN and accessible via `/freeserversaver lagspikes`.
- **HeapHistoryTracker**: ring buffer (default 50) of tier transitions
  for `/freeserversaver history`.
- **EnvironmentInspector**: at server start, logs and stores a snapshot
  of heap max, heap used, CPU count, JVM details. Interprets the heap
  size: "< 2GB = misconfigured", "2-3.5GB = standard Aternos",
  "> 3.5GB = RAM Boost active". Accessible via `/freeserversaver env`.

### Added — Commands (`/freeserversaver *`, permission level 2)
- `status` — current tier and heap %.
- `help` — full subcommand list.
- `history` — recent tier transitions.
- `metrics` — combined dashboard (heap, tier, players, chunks, view
  distance).
- `env` — JVM environment snapshot.
- `lagspikes` — recent lag spike breadcrumbs.
- `top entities` — top-10 entity types by count, with dimension
  breakdown.
- `inspect chunks` — per-dimension loaded / forced / player counts.
- `tuning` — current AutoTuner threshold offset (only meaningful if
  AutoTuning enabled).

### Added — Mod compatibility
- **ModCompatWarnings**: at server start, scans loaded mods and warns:
  - Heavy mods (Create, Mekanism, AE2, etc.) — their block entities
    can outpace Heap Guardian's recovery; install FerriteCore +
    ModernFix as static-allocation companions
  - Conflicting mods (other adaptive perf mods like APT, DAB,
    Immersive Optimization, OptimizeMod) — these work fine alongside
    Heap Guardian but the warning explains the overlap
  - Recommended mods (Lithium, FerriteCore, ModernFix) — if any are
    missing, an INFO-level hint mentions them. Heap Guardian
    intentionally stays out of their lane (allocation reduction /
    dedup); they're complementary

### Added — Internationalization
- 9 locale files in `assets/freeserversaver/lang/`:
  - `en_us` (baseline), `ja_jp`, `de_de`, `pt_br`, `es_es`,
  - `zh_cn`, `ko_kr`, `fr_fr`, `ru_ru`
- All player-visible chat output goes through `Component.translatable()`,
  so a Japanese client sees Japanese, a German client sees German, on
  the same server.
- **Translations are machine-generated.** Native-speaker review is
  welcome — open a PR or issue with corrections. Server-console output
  falls back to `en_us` since the console has no locale.

### Added — Optional modules (default OFF in v0.1)
- **AutoTuner**: every 5 minutes, adjusts the threshold offset by
  ±2pp based on observed spike-vs-heap patterns. Clamped to ±10pp.
  *Enable via config if /freeserversaver status shows you're
  consistently in the wrong tier.*
- **MobDensityDetector**: every 30s, scans loaded chunks and warns
  about 30+ same-type mob concentrations (mob-farm signature).
  *Diagnostic only; existing throttles already apply.*
- **DiscordWebhookModule**: sends throttle-level escalations to a
  Discord webhook. 60-second rate limit. URL validated as
  `discord.com/api/webhooks/`. *Set `webhookUrl` and
  `enableDiscordWebhook = true` to use.*

### Known limitations (v0.1)
- **No in-game verification has been done.** Every claim in this
  changelog is "the code compiles and the design is consistent." See
  `VERIFICATION_CHECKLIST.md`.
- **NeoForge 1.21.1 only.** Random tick logic changed in 1.21.2; we
  haven't validated against it.
- **No JEI / config GUI.** Edit `serverconfig/freeserversaver-
  server.toml` directly.
- **Translations are machine-translated.** Likely fine for the
  technical core terms but may read unnaturally to native speakers.

### Attribution
See `NOTICE.md` for direct attribution to the MIT-licensed mods whose
patterns we adapted (APT, ChunkPurge, Server Stasis) and the broader
list of LGPL/GPL/ARR mods we read for design context but didn't take
code from (Lithium, ModernFix, ServerCore, WMB, Immersive Optimization,
DynView, Mobtimizations).
