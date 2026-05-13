# Third-Party Notices

Heap Guardian incorporates implementation patterns and (in later phases)
algorithm code from the following MIT-licensed projects. Their copyright
notices are reproduced here in compliance with the MIT license terms.

## Adaptive Performance Tweaks (Markus Bordihn)

Source: https://github.com/MarkusBordihn/BOs-Adaptive-Performance-Tweaks

Patterns adapted: polling + hysteresis loop, throttle-level event-bus
broadcast, server-start delay, mob-spawn cancel via `FinalizeSpawnEvent`
+ `EntityJoinLevelEvent` double-cancel, last-allowed/last-blocked cache,
mod-compat warning catalog.

```
Copyright 2021 Markus Bordihn

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

## ChunkPurge (Francis / Android25 / stevestech)

Source: https://github.com/Android25/ChunkPurge

Patterns adapted (planned for Phase 2): flood-fill identification of
chunks isolated from any chunk watchers (players, force-load tickets,
spawn area), then unloading the disconnected set to avoid tps-spikes
from breaking energy nets and the like.

```
Copyright (c) 2014 Francis

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

## Server Stasis (Robert Sundström / Hrodebert81)

Source: https://github.com/Hrodebert81/Server_stasis

Patterns adapted (planned for Phase 2): invoking the vanilla `/tick freeze`
and `/tick rate <n>` commands via `MinecraftServer#getCommands()` rather
than mixing into the tick loop directly — keeps the mod free of mixins
and broadens compatibility.

```
Copyright (c) 2025 Robert Sundström

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
