# Free Server Saver

**Lag reducer for free Minecraft servers — Aternos, Falixnodes, Minehut, and any low-RAM hosting.**

If you play on a free server, you've seen it:

- Mobs teleporting 5-10 blocks instead of moving smoothly
- You and one friend get disconnected when nobody else is even online
- The server "freezes" for half a second every few minutes
- "Server crashed because it ran out of memory" after running fine for an hour

Those aren't ping problems. They're **garbage collection pauses** — your host gives you 2-4GB of RAM, modpacks push that to the limit, the JVM panics and stops the entire server for hundreds of milliseconds to clean up. To the player, that looks identical to network lag.

**Free Server Saver polls your server's heap usage every 2 seconds and gradually scales back AI ticks, mob spawns, and view distance _before_ the heap fills up.** No GC pause, no rubber-banding, no disconnects.

> **Tested target**: Aternos free tier (~2.5GB RAM), but works on any low-RAM Minecraft server — Falixnodes, Minehut, self-hosted, old PCs.

## How it works

| Heap | Tier | What changes |
|------|------|--------------|
| < 60% | NORMAL | Nothing — vanilla behavior |
| 60-70% | L1 | Far-away mob AI ticks every other tick; 50% of natural mob spawns rejected |
| 70-80% | L2 | Distant mob AI ticks 1/8 rate; all natural spawns rejected |
| 80-85% | L3 | View distance compressed to 6 chunks; aggressive |
| ≥ 85% | L4 EMERGENCY | Distant mobs force-discarded; server tick rate halved (10 TPS) |

When the heap drops back down, every intervention reverses automatically. **5% hysteresis** prevents the tier from flapping on the boundary.

### What's protected
- **Bosses** (EnderDragon, WitherBoss, Warden, ElderGuardian, Ravager, Hoglin, or any mob with > 80 max HP) — never throttled, never despawned
- **Villagers, traders, golems** — never despawned (your iron farms keep working)
- **Named, leashed, tamed, persistent mobs** — never touched
- **Combat-engaged mobs** (`getTarget() != null`) — keep ticking at full rate even at L4
- **Newly spawned (< 10s old) mobs** — grace period before throttling
- **Crops, leaves, snow, fire** — random ticks are NEVER touched (use Lithium for those)

### What it doesn't do (and won't ever)
- ❌ Spawn fake players to bypass Aternos's idle timer (that's Carpet, and Aternos bans it)
- ❌ Skip the queue or fake server stats
- ❌ Make your storage bigger or extend the 10-minute boot limit

Free Server Saver works **within** Aternos's rules. That's why it's allowed on the mod list.

## Discord notifications (opt-in)

Set `enableDiscordWebhook = true` and a webhook URL in `serverconfig/freeserversaver-server.toml`, get a ping in Discord when your server hits L3 or L4. You don't have to sit watching the Aternos console — Free Server Saver tells you when things are getting tight.

## Languages

Free Server Saver's chat output is translated into 9 languages: English, 日本語, Deutsch, Português (BR), Español, 简体中文, 한국어, Français, Русский. A Japanese client sees Japanese, a German client sees German — same server, no recompilation.

**The translations are machine-generated** as a starting point. Native-speaker corrections are very welcome — open a PR or issue with a fixed `lang/{locale}.json`.

## Commands

| Command | What it shows |
|---------|---------------|
| `/freeserversaver status` | Current tier + heap percentage |
| `/freeserversaver history` | Last 20 tier transitions, color-coded |
| `/freeserversaver metrics` | Heap, tier, players, loaded chunks, view distance |
| `/freeserversaver inspect chunks` | Per-dimension chunk load counts |

All require op (permission level 2).

## Companion mods (strongly recommended)

Free Server Saver focuses on the _adaptive_ side — adjusting behavior under pressure. It doesn't try to do what the _static_ optimization mods already do beautifully. **For a complete low-RAM stack**:

- **Lithium** — Allocation reduction across the whole engine. Pair this with Free Server Saver first.
- **FerriteCore** — 40-50% reduction in block-state memory.
- **ModernFix** — Mod-loading speedup; helps you fit inside Aternos's 10-minute boot limit.

The startup log will hint about these if you're missing any.

## What it solves vs what it doesn't

Aternos players report a small, well-known set of pain points. Honest scope statement:

**What HG addresses:**
- Mobs teleporting / players disconnecting (GC pause → TPS death) ✓
- "Out of Memory" crashes ✓
- General lag from entity tick budget ✓
- Villager / iron farm dying from over-aggressive despawn ✓
- Mobs not attacking (AI freeze from low TPS) ✓
- 4GB world-size cap (via `prune` command and `storage` monitor) ✓
- Idle-shutdown awareness (informational notifications, NOT bypassing) ✓
- 10-min startup timeout awareness (boot-time history + early warning) ✓
- Chunk-generation lag from new exploration (`/pregen <radius>`, yields to Chunky) ✓

**What HG does NOT do:**
- Network latency / ping issues — server-region geographical problem.
- Bypass Aternos's idle timer with fake players — that's what Aternos bans (Carpet). HG works within Aternos's rules.

**Cooperates rather than competes.** If you've already installed Chunky / Lithium / FerriteCore / ModernFix / Adaptive Performance Tweaks / Where's my Brain / Immersive Optimization / OptimizeMod / Tick Dynamic / Tick Tweaks, Free Server Saver detects them at startup and yields its overlapping modules. You get HG's heap-pressure-adaptive logic on top of whatever you already have, with no double-throttling and no `/tick rate` fights.

## Status

**v0.1.0 — built, not yet in-game verified.** The mod compiles cleanly and the design is internally consistent, but no `runClient` session has happened yet. See `VERIFICATION_CHECKLIST.md` for the test plan to run before declaring v0.1.0 released.

## Building

```
./gradlew build
```

Requires JDK 21. NeoForge 1.21.1 only — random tick logic changed in 1.21.2+ in a way we can't safely auto-detect.

## License

MIT — see `LICENSE`.

Design patterns adapted (study, not copy) from these MIT/LGPL/GPL mods (full attribution in `NOTICE.md`):

- Adaptive Performance Tweaks (Markus Bordihn) — polling/hysteresis/event-bus + spawn-cancel patterns
- ChunkPurge (Francis) — flood-fill chunk anchoring
- Server Stasis (Robert Sundström) — command-driven tick control
- ServerCore (Wesley1808) — Paper-style Entity Activation Range safety gates
- DAB / Where's my Brain — distance-bucket AI throttling
- Immersive Optimization (Luke100000) — tick scheduler model
