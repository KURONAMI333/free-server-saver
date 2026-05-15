
# Free Server Saver

**Lag reducer for low-RAM Minecraft servers (Falixnodes, Minehut, self-hosted), self-hosted, anything stuck on 2-4 GB of RAM.**

## The problem

If you've seen any of these on your server, it's the same root cause:

- Mobs teleporting 5-10 blocks instead of moving smoothly
- You and a friend get disconnected even though only 2 people are online
- The server freezes for half a second every few minutes
- "Server crashed because it ran out of memory" after a fine hour of play
- "OutOfMemoryError: Metaspace" even though RAM Boost is active

All of those are GARBAGE COLLECTION PAUSES. Your host gives you a small heap, modpacks fill it, the JVM stops the world to clean up. To players, that looks identical to network lag.

## The fix

Free Server Saver polls heap pressure every 2 seconds and gradually scales back AI ticks, mob spawns, view distance, and chunk loads BEFORE the heap fills. No GC pause, no rubber-banding, no disconnects.

## How it works — 5 tiers, hysteresis on recovery

- BELOW 60% heap — NORMAL — vanilla behavior
- 60-70% — L1 — far mobs tick every other tick; 50% of natural spawns rejected
- 70-80% — L2 — distant mobs at 1/8 tick rate; all natural spawns rejected
- 80-85% — L3 — view distance compressed to 6 chunks
- 85%+ — L4 EMERGENCY — distant mobs force-discarded; server tick rate halved

When the heap recovers, every change reverses automatically.

## Protections — what stays normal even at L4

- Bosses (EnderDragon, Wither, Warden, Elder Guardian, Ravager, Hoglin, or anything with >80 max HP)
- Villagers, wandering traders, golems, allays — your iron farm and trading hall keep working
- Named, leashed, tamed, persistent mobs
- Combat-engaged mobs — keep ticking at full rate
- Newly spawned (< 10s) mobs — grace period
- Crops, leaves, snow, fire — random ticks are NEVER touched (Lithium handles those better)

## Also addresses

- 4 GB world cap (free-host hard ceiling) — `/freeserversaver storage` to watch, `/freeserversaver prune` to identify pruneable chunks
- 10-minute startup limit — boot-time history + early warning
- Metaspace OOM — RAM Boost extends heap, not Metaspace. We monitor it separately
- Chunk-generation lag — `/freeserversaver pregen <radius>` for spawn-area pre-gen
- Mob-farm detection — opt-in scan that flags chunks with 30+ same-type mobs

## Plays well with everything

Free Server Saver detects competing performance mods and yields rather than fighting them. Install side-by-side with:

- Lithium, FerriteCore, ModernFix — these handle static optimization; we handle adaptive
- Adaptive Performance Tweaks, Tick Dynamic, Tick Tweaks — we yield to their tick-time logic
- Where's my Brain, DAB, OptimizeMod, Immersive Optimization, No See No Tick — we yield to their distance throttle
- Chunky — we yield our pregen command to its better one

The startup log shows which modules yielded and why. No double-throttling, no `/tick rate` fights, no surprises.

## Commands

All under `/freeserversaver`, op-only (permission level 2):

- `help` — all subcommands with descriptions
- `status` — current tier + heap percentage
- `metrics` — heap, tier, players, chunks, view distance
- `history` — last 20 tier transitions, color-coded
- `env` — JVM heap/CPU snapshot from server start, with low-RAM hosts-tier interpretation
- `lagspikes` — recent 100ms+ ticks with heap state at the time
- `top entities` — top 10 entity types by count
- `inspect chunks` — per-dimension loaded / forced / player counts
- `prune` — identify unreachable loaded chunks (flood-fill)
- `storage` — world directory size vs the host's 4 GB cap
- `pregen <radius>` — pre-generate chunks around you

## Languages

Output translated to 9 languages: English, 日本語, Deutsch, Português (BR), Español, 简体中文, 한국어, Français, Русский. A Japanese client sees Japanese on a server in any locale.

Translations are machine-generated as a starting point. Native-speaker corrections are very welcome — open a PR or issue on GitHub.

## What it does NOT do

- Network ping issues — geographical, no mod can help
- Bypass the host's idle-shutdown timer with fake players — that's what many free hosts ban (Carpet). Free Server Saver works within the host's rules

## Requirements

- Minecraft 1.21.1
- NeoForge
- JDK 21

(1.21.2+ port pending — vanilla's random tick logic changed in 1.21.2 and we want to validate first.)

## Status

v0.1.0 — code complete, in-game verification pending. Once that's done, full release.

## Companion mods (strongly recommended)

For a complete low-RAM stack, install alongside:

- Lithium — allocation-cheap optimizations across the engine
- FerriteCore — 40-50% block-state memory reduction
- ModernFix — mod-loading speedup; helps with that 10-minute free-host boot limit

Free Server Saver intentionally stays out of their lane. They handle static optimization, we handle adaptive response.

## License & attribution

MIT. See NOTICE.md in the GitHub repo for full attribution.

UNOFFICIAL community mod. Not affiliated with any hosting provider.

