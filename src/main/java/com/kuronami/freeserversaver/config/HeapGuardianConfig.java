package com.kuronami.freeserversaver.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-side config. Each intervention module can be toggled independently
 * so users can adopt Heap Guardian incrementally — start with just the
 * monitor logging, enable random-tick throttling once they trust it,
 * then enable spawn throttling, etc.
 *
 * <p>Defaults are aimed at a 2-4 GB Aternos-grade server. The thresholds
 * themselves live on {@link com.kuronami.freeserversaver.monitor.ThrottleLevel}
 * — we intentionally don't expose them as config in v0.1 because tuning
 * them well requires understanding G1GC's allocation rate behavior, and a
 * misconfigured threshold (e.g. 95% trigger) defeats the whole point of
 * acting before the GC pause.
 *
 * <p>Phase 2 may expose threshold tuning once we have real-world data on
 * how the defaults perform across modpack RAM profiles.
 */
public final class HeapGuardianConfig {

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ENABLE_ENTITY_TICK_THROTTLE;
    public static final ModConfigSpec.BooleanValue ENABLE_SPAWN_THROTTLE;
    public static final ModConfigSpec.BooleanValue ENABLE_CHUNK_UNLOAD;
    public static final ModConfigSpec.BooleanValue ENABLE_DESPAWN_SWEEP;
    public static final ModConfigSpec.BooleanValue ENABLE_TICK_RATE_THROTTLE;
    public static final ModConfigSpec.BooleanValue ENABLE_ITEM_THROTTLE;
    public static final ModConfigSpec.BooleanValue ENABLE_MOB_DENSITY_DETECTION;
    public static final ModConfigSpec.BooleanValue ENABLE_AUTO_TUNING;
    public static final ModConfigSpec.BooleanValue ENABLE_CHUNK_PRUNING;
    public static final ModConfigSpec.BooleanValue ENABLE_STORAGE_MONITOR;
    public static final ModConfigSpec.BooleanValue ENABLE_IDLE_NOTIFIER;
    public static final ModConfigSpec.BooleanValue ENABLE_BOOT_TIME_TRACKER;
    public static final ModConfigSpec.BooleanValue ENABLE_CHUNK_PREGEN;
    public static final ModConfigSpec.BooleanValue ENABLE_METASPACE_WATCHER;
    public static final ModConfigSpec.BooleanValue ENABLE_MOD_COMPAT_WARNINGS;
    public static final ModConfigSpec.BooleanValue VERBOSE_LOGGING;

    // ─── Discord webhook ──────────────────────────────────────────
    public static final ModConfigSpec.BooleanValue ENABLE_DISCORD_WEBHOOK;
    public static final ModConfigSpec.ConfigValue<String> DISCORD_WEBHOOK_URL;
    public static final ModConfigSpec.BooleanValue WEBHOOK_NOTIFY_RECOVERY;

    // ─── History / observability ──────────────────────────────────
    public static final ModConfigSpec.IntValue HISTORY_SIZE;
    public static final ModConfigSpec.BooleanValue ENABLE_LAG_SPIKE_DETECTION;
    public static final ModConfigSpec.IntValue LAG_SPIKE_HISTORY_SIZE;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("modules");

        ENABLE_ENTITY_TICK_THROTTLE = b
            .comment(
                "Throttle far-away mob AI ticks based on player distance.",
                "L1+: skip a fraction of ticks for FAR/DISTANT mobs.",
                "L4: aggressive interval (32x) at DISTANT bucket.",
                "Bosses, named mobs, leashed/tamed/persistent mobs always",
                "run full ticks. Starvation floor of every 20 ticks ensures",
                "no mob is fully frozen.",
                "",
                "This replaces the older RandomTickModule (removed in 0.1.1)",
                "because vanilla random ticks are allocation-cheap; the real",
                "heap pressure comes from per-entity AI ticks. Install",
                "Lithium for the random-tick allocation optimization."
            )
            .define("enableEntityTickThrottle", true);

        ENABLE_SPAWN_THROTTLE = b
            .comment(
                "Cancel mob spawn events when heap pressure is high.",
                "L1 (60%+): randomly reject 50% of natural spawns.",
                "L2+ (70%+): cancel all natural spawns. Spawn eggs and",
                "named (name-tagged) entities are always allowed through."
            )
            .define("enableSpawnThrottle", true);

        ENABLE_CHUNK_UNLOAD = b
            .comment(
                "Lower view-distance and simulation-distance as heap fills.",
                "L3 (80%+): view=6, simulation=4",
                "L4 (85%+): view=4, simulation=3",
                "Restored to server.properties values on recovery / shutdown."
            )
            .define("enableChunkUnload", true);

        ENABLE_DESPAWN_SWEEP = b
            .comment(
                "On entry to L4_EMERGENCY, force-discard mobs that are >64",
                "blocks from any player. Skips named entities, persistent",
                "mobs, villagers, traders, and golems. Fires once per",
                "escalation, not continuously."
            )
            .define("enableDespawnSweep", true);

        ENABLE_TICK_RATE_THROTTLE = b
            .comment(
                "On L4_EMERGENCY, slow the server to 10 TPS via /tick rate.",
                "Half speed — very noticeable to players, used only when",
                "all other interventions have failed to recover the heap."
            )
            .define("enableTickRateThrottle", true);

        ENABLE_ITEM_THROTTLE = b
            .comment(
                "Throttle ticks of ground-resting items and XP orbs that",
                "are far from any player. Mob farms produce huge piles of",
                "these and they're often >20% of entity tick cost. Items",
                "in motion / in the air / near a player are NEVER throttled.",
                "Despawn timer still progresses (every 40-tick starvation",
                "floor) so this doesn't leave trash piling up forever."
            )
            .define("enableItemThrottle", true);

        ENABLE_MOB_DENSITY_DETECTION = b
            .comment(
                "Every 30s, scan loaded chunks and log a warning when a",
                "single chunk contains 30+ mobs of the same type — the",
                "signature of a mob farm. Diagnostic only; doesn't modify",
                "behavior (the existing throttle modules already apply).",
                "Repeat warnings are suppressed for 5 min per (chunk, type).",
                "",
                "DEFAULT: false. Enable after you've validated the rest of",
                "Heap Guardian works on your server — the scan iterates",
                "getAllEntities() and is the only module here that's not",
                "strictly free."
            )
            .define("enableMobDensityDetection", false);

        ENABLE_AUTO_TUNING = b
            .comment(
                "Every 5 minutes, adjust tier thresholds based on observed",
                "lag-spike patterns. If spikes happen at NORMAL/L1 tier,",
                "lower thresholds (start throttling earlier). If heap is",
                "consistently elevated but no spikes, raise thresholds",
                "(throttle was too aggressive). Clamps at ±10 percentage",
                "points from the static defaults. Step size: 2 points/cycle.",
                "",
                "DEFAULT: false. Static thresholds work well for typical",
                "Aternos workloads. Enable only if /freeserversaver status",
                "shows you're consistently in the wrong tier — the",
                "adjustment is slow (5-minute cycle) and most users won't",
                "see the benefit."
            )
            .define("enableAutoTuning", false);

        ENABLE_CHUNK_PRUNING = b
            .comment(
                "Auto-prune unreachable loaded chunks via flood-fill from",
                "player and force-load anchors. Engaged on rising edge into",
                "L3/L4 tier. Manual on-demand: /freeserversaver prune.",
                "Diagnostic-grade in v0.1 — identifies count, vanilla's",
                "ticket lifecycle handles the actual unload."
            )
            .define("enableChunkPruning", true);

        ENABLE_STORAGE_MONITOR = b
            .comment(
                "Every 30 minutes, scan the world directory size against",
                "Aternos's 4GB cap. WARN at 3.5GB; ERROR at 4GB. Initial",
                "scan runs 30 seconds after server start to catch the",
                "'already over the cap' case immediately. No deletes."
            )
            .define("enableStorageMonitor", true);

        ENABLE_IDLE_NOTIFIER = b
            .comment(
                "On player-count transitions to/from zero, log a note about",
                "Aternos's idle-shutdown timer. Server-empty -> 'countdown",
                "starts'. First-rejoin -> 'timer reset'. Purely informational",
                "— does NOT keep the server alive. Doing that would be the",
                "fake-player tactic Aternos bans (Carpet)."
            )
            .define("enableIdleNotifier", true);

        ENABLE_BOOT_TIME_TRACKER = b
            .comment(
                "On each server start, log JVM uptime to 'ready for players'",
                "state and persist the last 10 boot times to a flat file in",
                "the world directory. If recent 3-boot average exceeds 8 min,",
                "warn at startup that you're approaching Aternos's 10-min cap.",
                "Pure observation — does not modify mod loading (that's",
                "ModernFix's job, which we recommend in companion warnings)."
            )
            .define("enableBootTimeTracker", true);

        ENABLE_CHUNK_PREGEN = b
            .comment(
                "Enable /freeserversaver pregen <radius> command for synchronous",
                "chunk pre-generation around the command source. Capped at 16",
                "chunks radius (~256 blocks). Yields entirely to Chunky if",
                "Chunky is installed — Chunky's multi-threaded async approach",
                "is the right tool for any non-trivial pregen job."
            )
            .define("enableChunkPregen", true);

        ENABLE_METASPACE_WATCHER = b
            .comment(
                "Watch JVM Metaspace (class metadata) for crashes the heap",
                "monitor can't see. Large modpacks (Craftoria, ATM10) sometimes",
                "exhaust Metaspace BEFORE heap, producing 'OutOfMemoryError:",
                "Metaspace' that spark reports as 'memory empty'. Aternos's",
                "RAM Boost via Medal does NOT help this — it only extends",
                "heap. The watcher WARNs at 80%, ERRORs at 95%, giving the",
                "operator time to trim mods before the OOM crash."
            )
            .define("enableMetaspaceWatcher", true);

        ENABLE_MOD_COMPAT_WARNINGS = b
            .comment(
                "Log a warning when known-heavy mods (Create, Mekanism, etc.)",
                "are loaded, since their persistent block entities can",
                "consume heap faster than Heap Guardian can recover it."
            )
            .define("enableModCompatWarnings", true);

        VERBOSE_LOGGING = b
            .comment(
                "Log every successful spawn cancel / random-tick adjustment.",
                "Useful for debugging; spammy on busy servers."
            )
            .define("verboseLogging", false);

        b.pop(); // modules

        b.push("webhook");

        ENABLE_DISCORD_WEBHOOK = b
            .comment(
                "Send a Discord webhook message on each throttle-level change.",
                "Disabled by default — supply a webhook URL below to use it."
            )
            .define("enableDiscordWebhook", false);

        DISCORD_WEBHOOK_URL = b
            .comment(
                "Discord webhook URL. Treated as a secret — never logged.",
                "Must start with 'https://' and contain 'discord.com/api/webhooks/'.",
                "Get one via Server Settings -> Integrations -> Webhooks -> New Webhook."
            )
            .define("discordWebhookUrl", "");

        WEBHOOK_NOTIFY_RECOVERY = b
            .comment(
                "Also send notifications when the heap recovers (e.g. L2 -> L1).",
                "Off by default to avoid channel spam; escalations always fire."
            )
            .define("webhookNotifyRecovery", false);

        b.pop(); // webhook

        b.push("observability");

        HISTORY_SIZE = b
            .comment(
                "How many throttle-level transitions to keep in memory for",
                "`/freeserversaver history`. Bounded ring buffer; oldest evicted."
            )
            .defineInRange("historySize", 50, 1, 1000);

        ENABLE_LAG_SPIKE_DETECTION = b
            .comment(
                "Capture a breadcrumb (mspt, heap%, tier, players) whenever",
                "a server tick exceeds 100 ms. Visible via /freeserversaver",
                "lagspikes. Adds two nanoTime() calls per tick — negligible."
            )
            .define("enableLagSpikeDetection", true);

        LAG_SPIKE_HISTORY_SIZE = b
            .comment(
                "Maximum lag-spike breadcrumbs kept in memory."
            )
            .defineInRange("lagSpikeHistorySize", 50, 1, 500);

        b.pop(); // observability

        SPEC = b.build();
    }

    private HeapGuardianConfig() {}
}
