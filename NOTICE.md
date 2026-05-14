# Third-Party Notices

Free Server Saver builds on patterns established by an excellent
ecosystem of free, open-source performance mods. This document records
both **direct attribution** (where copyright applies because we adapted
identifiable patterns at the same level of detail) and **study
references** (mods we read for design context but didn't borrow code
from). Neither is required by law for clean-room work, but transparent
attribution is good neighborly behavior in the mod ecosystem.

## Direct attribution (MIT-licensed, patterns adapted)

These mods' copyright notices are reproduced verbatim because we
adapted identifiable algorithmic patterns from them:

### Adaptive Performance Tweaks (Markus Bordihn)

Source: https://github.com/MarkusBordihn/BOs-Adaptive-Performance-Tweaks
License: MIT (Copyright 2021 Markus Bordihn)

Patterns adapted: polling + hysteresis loop, throttle-level event-bus
broadcast, server-start warm-up delay, mob-spawn cancel via the
`FinalizeSpawnEvent` + `EntityJoinLevelEvent` double-cancel idiom,
last-allowed/last-blocked entity cache, mod-compat warning catalog.

```
Permission is hereby granted, free of charge, to any person obtaining a
copy of this software and associated documentation files (the "Software"),
to deal in the Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, sublicense,
and/or sell copies of the Software, and to permit persons to whom the
Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included
in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND.
```

### ChunkPurge (Francis / Android25)

Source: https://github.com/Android25/ChunkPurge
License: MIT (Copyright 2014 Francis)

Patterns adapted (planned for a later phase, not in v0.1.0): flood-fill
identification of chunks isolated from any chunk watchers (players,
force-load tickets, spawn area).

```
Permission is hereby granted, free of charge, to any person obtaining a
copy of this software and associated documentation files (the "Software"),
to deal in the Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, sublicense,
and/or sell copies of the Software, and to permit persons to whom the
Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included
in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND.
```

### Server Stasis (Robert Sundström / Hrodebert81)

Source: https://github.com/Hrodebert81/Server_stasis
License: MIT (Copyright 2025 Robert Sundström)

Patterns adapted: invoking the vanilla `/tick freeze` and `/tick rate <n>`
commands via `MinecraftServer#getCommands()` rather than mixing into the
tick loop directly — keeps the mod free of mixins and broadens
compatibility.

```
Permission is hereby granted, free of charge, to any person obtaining a
copy of this software and associated documentation files (the "Software"),
to deal in the Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, sublicense,
and/or sell copies of the Software, and to permit persons to whom the
Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included
in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND.
```

### Neruina — Ticking Entity Fixer (Bawnorton)

Source: https://github.com/Bawnorton/Neruina
License: MIT (Copyright 2023 Bawnorton)

Patterns adapted: Mixin target sites for `Level#guardEntityTick` and
`LevelChunk$BoundTickingBlockEntity#tick`, and the use of MixinExtras
`@WrapOperation` to safely wrap the tick call site without conflicting
with other mods. ExceptionGuard's implementation is independent and
deliberately simpler (no per-entity NBT persistence, no interactive UI)
because Neruina remains the canonical solution; Free Server Saver yields
to it when both are installed.

```
Permission is hereby granted, free of charge, to any person obtaining a
copy of this software and associated documentation files (the "Software"),
to deal in the Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, sublicense,
and/or sell copies of the Software, and to permit persons to whom the
Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included
in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND.
```

## Study references (read for design context, no code adapted)

We read and learned from these LGPL/GPL-licensed mods. No code is taken
from them — the design is independent. Listed here because honest
attribution helps the next person who comes along trying to build a
similar mod.

| Mod | License | What we learned |
|---|---|---|
| Lithium (CaffeineMC) | LGPL-3.0 | The taxonomy of "internal optimization vs adaptive control" — Lithium is the gold standard for non-behavior-changing optimization, so Free Server Saver intentionally stays out of that lane (recommended as a companion in `ModCompatWarnings`). |
| ModernFix (embeddedt) | LGPL-3.0 | Same lane as Lithium — companion mod, not competitor. The launch-time speedup is exactly what helps the host's 10-minute boot limit. |
| ServerCore (Wesley1808) | GPL-3.0 (port of Paper's Aikar) | The transient-state safety-gate catalog (`SafetyGate.java`). Paper's Entity Activation Range encodes ~20 rules for "this mob is in a critical state right now, don't throttle it" — those rules are universal to the problem space, and reading their list informed ours. |
| Where's my Brain (DAB-style) | ARR (Modrinth public docs) | Distance-bucket model: NEAR/MID/FAR/DISTANT with per-bucket tick interval multiplier. Proximity snapshot. Hysteresis deadband. The conservative "AI Culling 3-gate" (requireNoTarget / requireNoPath / requireLowMotion). |
| Immersive Optimization (Luke100000) | GPL-3.0 | Tick scheduler model — per-mob priority field, mod `(gameTime + entityId)` to distribute work, frustum-aware throttling on single player. |
| OptimizeMod | MIT | Public docs only. Same distance-bucket family. |
| Mobtimizations (Corosauce) | LGPL-3.0 | Mixin-based AI goal tweaks. Out of our scope for v0.1, but informed Phase 5+ planning. |
| DynView (LDTteam) | GPL-3.0 | Server-side dynamic view distance via PlayerList — same approach Free Server Saver's ChunkUnloadModule uses, just TPS-triggered instead of heap-triggered. |
| Hibernateforge (Thadah) | EUPL v1.2 | Server-empty hibernation. Not used (EUPL's copyleft scope is awkward to mix), but the pattern of letting vanilla commands do the heavy lifting was confirmed. |

The clean-room rule we followed: each pattern was either described in
the source mod's public documentation, identifiable by reading its
overall architecture, or so universal to the problem space (e.g.
hysteresis on a threshold) that it isn't owned by any single mod. The
actual code in `free-server-saver/` is original.
