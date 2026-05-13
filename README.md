# Heap Guardian

Heap-aware adaptive throttle for low-RAM Minecraft servers (NeoForge 1.21.1).

Polls JVM heap usage every 2 seconds and progressively scales back
random ticks, mob spawns, and chunk loads when memory pressure rises —
preventing the long GC pauses that look like network lag on
Aternos-grade hardware.

> **Status: v0.1.0 PoC** — heap monitor + log output only. No actual
> throttling yet. See `local-workspace/kuronami-mods/knowledge/HEAP_GUARDIAN_NOTES.md`
> for the phased plan.

## Why this exists

Existing performance mods (Lithium, FerriteCore, Adaptive Performance
Tweaks, ModernFix, etc.) cover almost every angle of Minecraft server
optimization — but none of them trigger on the metric that actually
matters on a 2-4GB heap: **the heap percentage itself**.

When a small Aternos server fills its heap, G1GC starts running aggressive
collection cycles that consume tens of percent of available CPU and
produce 100-500ms pauses. Players see "rubber-banding", "mobs teleporting
5-10 blocks", or get disconnected entirely. Tick-time-based adaptive mods
react after the pauses have already happened. Heap Guardian reacts
*before*, by reducing the rate at which the server allocates new objects
the moment heap occupancy crosses tier thresholds.

## Building

```
./gradlew build
```

Requires JDK 21.

## License

MIT — see `LICENSE`.

Implementation patterns adapted from three MIT-licensed projects.
Attribution and original copyright notices: see `NOTICE.md`.
