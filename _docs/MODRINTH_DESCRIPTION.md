# Modrinth Project Description Draft

Copy-paste-ready. Modrinth's markdown renderer supports headings, lists,
tables, code blocks, and links. Keep the first paragraph punchy — it's
the "above the fold" preview on search results.

---

# Free Server Saver

**Lag reducer for low-RAM Minecraft servers (Falixnodes, Minehut, self-hosted), self-hosted, anything stuck on 2-4 GB of RAM.**

If you've ever seen any of this on your server, it's the same root cause:

- 🟦 Mobs teleporting 5–10 blocks instead of moving smoothly
- 🟦 You and a friend get disconnected even though only 2 people are online
- 🟦 The server freezes for half a second every few minutes
- 🟦 `Server crashed because it ran out of memory` after a fine hour of play
- 🟦 `OutOfMemoryError: Metaspace` even though RAM Boost is active

All of those are **garbage collection pauses**. Your host gives you a small heap, modpacks fill it, the JVM stops the world to clean up, and to players that looks identical to network lag.

**Free Server Saver polls heap pressure every 2 seconds and gradually scales back AI ticks, mob spawns, view distance, and chunk loads _before_ the heap fills.** No GC pause, no rubber-banding, no disconnects.

## How it works

Five tiers. Static thresholds, hysteresis on recovery:

| Heap | Tier | What changes |
|---|---|---|
| < 60% | NORMAL | Vanilla behavior |
| 60–70% | L1 | Far mobs tick every other tick; 50% of natural spawns rejected |
| 70–80% | L2 | Distant mobs at 1/8 tick rate; all natural spawns rejected |
| 80–85% | L3 | View distance compressed to 6 chunks |
| ≥ 85% | L4 EMERGENCY | Distant mobs force-discarded; server tick rate halved |

When the heap recovers, every change reverses automatically.

## Protections — what stays normal even at L4

- **Bosses** (EnderDragon, Wither, Warden, Elder Guardian, Ravager, Hoglin, or anything with > 80 max HP)
- **Villagers, wandering traders, golems, allays** — your iron farm and trading hall keep working
- **Named, leashed, tamed, persistent** mobs
- **Combat-engaged mobs** (`getTarget() != null`) — keep ticking at full rate
- **Newly spawned (< 10 s)** mobs — grace period
- **Crops, leaves, snow, fire** — random ticks are NEVER touched (Lithium handles those better)

## Also addresses

- **4 GB world cap** — `/freeserversaver storage` and `/freeserversaver prune` for the host's hard ceiling
- **10-minute startup limit** — boot-time history + early warning if you're approaching it
- **Metaspace OOM** — RAM Boost extends heap, not Metaspace. We watch Metaspace separately and warn before the crash
- **Chunk-generation lag** — `/freeserversaver pregen <radius>` for spawn-area pre-gen (yields to Chunky if installed)
- **Mob-farm detection** — opt-in scan that flags chunks with 30+ same-type mobs

## Plays well with everything

**Free Server Saver detects competing mods and yields** rather than fighting them. Install side-by-side with any of:

- Lithium / FerriteCore / ModernFix — these handle static optimization; we handle adaptive throttle
- Adaptive Performance Tweaks / Tick Dynamic / Tick Tweaks — we yield to their tick-time logic
- Where's my Brain / DAB / OptimizeMod / Immersive Optimization / No See No Tick — we yield to their distance throttle
- Chunky — we yield our pregen command to its better one

The startup log shows which modules yielded and why. No double-throttling, no `/tick rate` fights, no surprises.

## Commands (`/freeserversaver`, server-op only)

| Command | What it shows |
|---|---|
| `help` | All subcommands with descriptions |
| `status` | Current tier + heap percentage |
| `metrics` | Heap, tier, players, chunks, view distance |
| `history` | Last 20 tier transitions, color-coded |
| `env` | JVM heap/CPU snapshot from server start, with low-RAM hosts-tier interpretation |
| `lagspikes` | Recent 100ms+ ticks with heap state at the time |
| `top entities` | Top-10 entity types by count |
| `inspect chunks` | Per-dimension loaded / forced / player counts |
| `prune` | Identify unreachable loaded chunks (flood-fill) |
| `storage` | World directory size vs the host's 4 GB cap |
| `pregen <radius>` | Pre-generate chunks around you |

## Languages

Output translated to **9 languages** — English, 日本語, Deutsch, Português (BR), Español, 简体中文, 한국어, Français, Русский. A Japanese client sees Japanese on a server in any locale.

The translations are machine-generated as a starting point. Native-speaker corrections are very welcome — open a PR or issue.

## What it does NOT do

- ❌ Network ping issues — that's geographical, no mod can help
- ❌ Bypass the host's idle-shutdown timer with fake players — that's exactly what many free hosts ban (Carpet). Free Server Saver works within the host's rules

## Requirements

- Minecraft 1.21.1
- NeoForge
- JDK 21

(1.21.2+ port pending — vanilla's random tick logic changed in 1.21.2 and we want to validate first.)

## Status

**v0.1.0** — code complete, in-game verification pending. Once that's done, full release.

## Companion mods (strongly recommended)

For a complete low-RAM stack, install alongside:
- **Lithium** — allocation-cheap optimizations across the engine
- **FerriteCore** — 40–50 % block-state memory reduction
- **ModernFix** — mod-loading speedup; helps with that 10-minute free-host boot limit

Free Server Saver intentionally stays out of their lane. They handle static optimization, we handle adaptive response.

## License & attribution

MIT. See `NOTICE.md` in the GitHub repo for full attribution to the MIT-licensed mods whose design patterns we adapted (Adaptive Performance Tweaks, ChunkPurge, Server Stasis) and the LGPL/GPL/ARR mods we studied for design context (Lithium, ModernFix, ServerCore, Where's my Brain, Immersive Optimization, DynView, OptimizeMod, Mobtimizations, Hibernateforge).

**Unofficial community mod.** Not affiliated with any hosting provider.

---

## Modrinth meta (tags, slug, etc.)

- **Slug**: `free-server-saver`
- **Categories**: Optimization, Utility, Management
- **Loaders**: NeoForge
- **Game versions**: 1.21.1
- **Environments**: Server-side (client can have it too but it does nothing client-side)
- **License**: MIT
- **Source URL**: https://github.com/KURONAMI333/free-server-saver
- **Issue tracker**: https://github.com/KURONAMI333/free-server-saver/issues

### Suggested tags (Modrinth's actual tag taxonomy is limited; pick the closest existing ones)
- `optimization`
- `lag-fix` (if it exists; otherwise just "optimization")
- `server`
- `utility`
- `low-ram` (if it exists)

In the description body (above), free-text mentions of "free-host / Falixnodes / Minehut" are picked up by Modrinth's full-text search.
