package com.kuronami.heapguardian.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-side config. Each intervention module can be toggled independently
 * so users can adopt Heap Guardian incrementally — start with just the
 * monitor logging, enable random-tick throttling once they trust it,
 * then enable spawn throttling, etc.
 *
 * <p>Defaults are aimed at a 2-4 GB Aternos-grade server. The thresholds
 * themselves live on {@link com.kuronami.heapguardian.monitor.ThrottleLevel}
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
    public static final ModConfigSpec.BooleanValue ENABLE_RANDOM_TICK_THROTTLE;
    public static final ModConfigSpec.BooleanValue ENABLE_SPAWN_THROTTLE;
    public static final ModConfigSpec.BooleanValue ENABLE_CHUNK_UNLOAD;
    public static final ModConfigSpec.BooleanValue ENABLE_DESPAWN_SWEEP;
    public static final ModConfigSpec.BooleanValue ENABLE_TICK_RATE_THROTTLE;
    public static final ModConfigSpec.BooleanValue ENABLE_MOD_COMPAT_WARNINGS;
    public static final ModConfigSpec.BooleanValue VERBOSE_LOGGING;

    // ─── Discord webhook ──────────────────────────────────────────
    public static final ModConfigSpec.BooleanValue ENABLE_DISCORD_WEBHOOK;
    public static final ModConfigSpec.ConfigValue<String> DISCORD_WEBHOOK_URL;
    public static final ModConfigSpec.BooleanValue WEBHOOK_NOTIFY_RECOVERY;

    // ─── History / observability ──────────────────────────────────
    public static final ModConfigSpec.IntValue HISTORY_SIZE;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("modules");

        ENABLE_RANDOM_TICK_THROTTLE = b
            .comment(
                "Reduce randomTickSpeed gamerule as heap pressure rises.",
                "L1 (60%+): 3 -> 1   |   L2+ (70%+): -> 0",
                "Restored to vanilla default (3) when heap recovers."
            )
            .define("enableRandomTickThrottle", true);

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
                "`/heapguardian history`. Bounded ring buffer; oldest evicted."
            )
            .defineInRange("historySize", 50, 1, 1000);

        b.pop(); // observability

        SPEC = b.build();
    }

    private HeapGuardianConfig() {}
}
