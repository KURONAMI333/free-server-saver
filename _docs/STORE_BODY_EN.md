Watch JVM heap pressure and scale back expensive server work in stages before low-memory conditions become an emergency.

Free Server Saver targets slowdowns caused by high heap occupancy on low-memory servers. It does not fix network latency or every source of low TPS; its scope is the work it can reduce when measured heap pressure rises.

## Adaptive throttling

After a 20-second startup warm-up, the mod checks heap use about every two seconds. Its default thresholds are:

| Heap use | Tier | Intervention |
|---|---|---|
| Below 60% | Normal | No intervention |
| 60–70% | L1 | Slower ticks for far mobs; rejects 50% of natural spawns |
| 70–80% | L2 | Stronger distant-mob throttling; rejects natural spawns |
| 80–85% | L3 | Reduces view distance to 6 and simulation distance to 4 |
| 85% or more | L4 | Reduces view distance to 4 and simulation distance to 3, discards eligible distant mobs once, and runs the server at 10 TPS |

Each tier is released after heap use falls five percentage points below its entry threshold, which prevents rapid switching near a boundary. Individual intervention modules can be disabled in `serverconfig/freeserversaver-server.toml`.

## Protected activity

Bosses, named mobs, leashed or tamed mobs, and other persistent entities are excluded from destructive intervention. Combat, pathfinding, projectiles, visible timed states, and moving entities continue at full rate. Newly spawned entities receive a five-second grace period. Random ticks for crops, leaves, snow, and fire are not changed.

## Operator tools

All `/fss` commands require permission level 2. Key commands include:

- **`/fss status` and `/fss metrics`** — show the current tier, heap use, player count, loaded chunks, and view distance.
- **`/fss history` and `/fss lagspikes`** — show recent tier changes and ticks longer than 100 ms.
- **`/fss top entities` and `/fss inspect chunks`** — help locate entity-heavy or heavily loaded areas.
- **`/fss storage` and `/fss prune`** — report world size and identify isolated loaded chunks. They do not delete world files.
- **`/fss pregen [radius]`** — generates chunks around the command source; it yields when Chunky is installed.
- **`/fss quarantine`** — lists entities or block entities removed after repeatedly throwing tick exceptions.

Metaspace monitoring, boot-time history, an idle-timer notice, and Discord alerts are also available. The Discord webhook, automatic threshold tuning, and mob-density scan are disabled by default.

## Compatibility and limits

At startup, Free Server Saver disables specific overlapping modules when it detects supported performance or recovery mods. For example, it yields chunk pre-generation to Chunky and exception quarantine to Neruina, Bug Fix Mod, or Failsafe. The startup log records each disabled module.

The emergency tier can remove eligible distant mobs, and exception quarantine can remove faulty entity data after repeated failures. Back up the world before using these features. The current release has build and automated checks but has not yet been validated on a live production server.

All Rights Reserved. Modpack inclusion is allowed without permission or credit. This is an unofficial community mod and is not affiliated with any hosting provider.

[Source](https://github.com/KURONAMI333/free-server-saver) · [Issues](https://github.com/KURONAMI333/free-server-saver/issues)
