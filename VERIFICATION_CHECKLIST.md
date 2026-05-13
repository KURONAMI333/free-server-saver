# In-game Verification Checklist

The mod builds clean (`./gradlew build` is SUCCESSFUL across all phases)
but **has never been run in-game**. This checklist is the first thing
to do when the developer machine is available for a `runClient` /
`runServer` session.

Each entry is a separate, narrow test. If any one fails, fix it before
moving on — issues compound otherwise.

## Tier 1 — does the mod load at all

- [ ] `./gradlew runClient` starts without exception
- [ ] No "Failed to load mod 'freeserversaver'" in the log
- [ ] The boot snapshot from `EnvironmentInspector` appears in the log
      (`=== Free Server Saver: environment snapshot ===`)
- [ ] If a `lithium`, `ferritecore`, or `modernfix` mod is loaded, the
      "consider also installing" hint does NOT fire; if those are
      missing, it does fire
- [ ] No crash on world creation; entering a world succeeds

## Tier 2 — commands work and resolve translations

- [ ] `/freeserversaver help` lists 8 subcommands
- [ ] `/freeserversaver status` shows tier + heap percentage with green
      coloring (NORMAL tier)
- [ ] `/freeserversaver env` shows non-zero heap max + the right
      Aternos-tier interpretation hint
- [ ] `/freeserversaver metrics` shows player count, loaded chunks, view
      distance
- [ ] `/freeserversaver lagspikes` shows "no spikes" message (green)
- [ ] `/freeserversaver top entities` shows the vanilla starter entities
      (zombies, skeletons in overworld)
- [ ] `/freeserversaver inspect chunks` shows per-dimension counts
- [ ] Switch client language to Japanese (`ja_jp`) and re-run commands —
      output is in Japanese
- [ ] Try one more locale (`de_de` or `zh_cn`) to confirm i18n routing

## Tier 3 — throttling actually engages

Hardest to verify naturally. Use one of these approaches:

**Option A — force the issue.** Lower `-Xmx` to 1.5GB on the test
launcher. Generate ~3000 mobs (mob farm or `/summon` in a loop). Watch
for:
- [ ] `[HeapGuardian] Throttle level: NORMAL -> L1_MILD` in log
- [ ] `/freeserversaver status` reports the new tier
- [ ] Mob spawn rate visibly drops (no new mobs entering the loaded
      chunks)
- [ ] At L2_HEAVY: distant mob movement visibly stutters / pauses (the
      far DAB bucket is running at 1/8 tick rate)
- [ ] Heap recovers; tier drops back to NORMAL after 5%+ hysteresis
      drop
- [ ] `/freeserversaver history` shows the transition entries

**Option B — temporarily lower the threshold via reflection or by
patching `ThrottleLevel.java` to use 30/40/50/55% for the test session.**
Same checks as above but easier to trigger.

## Tier 4 — safety gates work

Critical: confirm we don't break gameplay.

- [ ] Force L2+ tier. Stand next to a zombie in combat with you — it
      keeps attacking smoothly (SafetyGate's `getTarget() != null`
      check)
- [ ] A baby villager doesn't pause (`Animal.isBaby()` exemption)
- [ ] A lit creeper still explodes on time (`Creeper.isIgnited()`)
- [ ] An EnderDragon fight runs normally at L4 (BossDetection)
- [ ] Items you drop near you are pickable normally (ItemEntityThrottle
      24-block exclusion)
- [ ] A name-tagged mob never despawns (BossDetection.isPlayerProtected)

## Tier 5 — optional modules

Each of these is `default: false` in v0.1 but should be tested
independently:

- [ ] `enableAutoTuning = true` → after 5 min with intentional spikes,
      `/freeserversaver tuning` shows non-zero offset; log shows
      `[AutoTuner] Threshold offset adjusted` line
- [ ] `enableMobDensityDetection = true` → build a 30+ zombie farm in
      one chunk, after 30s log shows `[MobDensity]` warning
- [ ] `enableDiscordWebhook = true` + valid webhook URL → escalation
      fires a Discord message; URL not visible in log; rate-limit
      enforced for repeated escalations within 60s

## Tier 6 — non-functional checks

- [ ] Server tick budget: `/spark profiler --timeout 60` shows
      Heap Guardian using < 2% CPU at NORMAL tier
- [ ] No allocation spikes on tier transitions (`/spark memory`)
- [ ] WARN log isn't spammy in normal operation (< 10 lines / hour)
- [ ] Config file `serverconfig/freeserversaver-server.toml` is well-
      formed; can edit and `/reload` (or restart) to apply

## Known unknowns

- `EntityTickEvent.Pre` was assumed cancellable. Compile passed, but
  runtime semantics need confirmation: a cancelled `Pre` event should
  skip the mob's `tick()` body. Verify with a debugger / println in
  `EntityTickThrottleModule.onEntityTickPre()` and a separate mixin or
  println in `Mob.tick()`.
- `ServerLevel.getAllEntities()` cost on a populated world is unknown.
  Used by `MobDensityDetector` (30s cadence) and `EntityCountTask`
  (on-demand only). Profile and document the worst-case time.
- `setViewDistance()` / `setSimulationDistance()` mid-session behavior —
  does it cause client packet flood? Spectate the network panel during
  a L3 transition.

## After every test passes

- [ ] Update `HEAP_GUARDIAN_NOTES.md` with anything learned
- [ ] Add a `CHANGELOG.md` entry for any code change made during
      verification
- [ ] Bump `mod_version` to 0.1.0 (currently 0.1.0 in gradle.properties
      but unreleased)
- [ ] Tag the commit `v0.1.0` and create a GitHub release with the
      jar attached
- [ ] Modrinth + CurseForge upload
