package com.kuronami.freeserversaver.command;

import com.kuronami.freeserversaver.HeapGuardian;
import com.kuronami.freeserversaver.environment.EnvironmentInspector;
import com.kuronami.freeserversaver.modules.ChunkPreGenModule;
import com.kuronami.freeserversaver.modules.ChunkPruningModule;
import com.kuronami.freeserversaver.modules.StorageMonitor;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.kuronami.freeserversaver.monitor.HeapHistoryTracker;
import com.kuronami.freeserversaver.monitor.HeapMonitor;
import com.kuronami.freeserversaver.monitor.LagSpikeDetector;
import com.kuronami.freeserversaver.monitor.ThrottleLevel;
import com.kuronami.freeserversaver.tuning.AutoTuner;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /freeserversaver} command tree.
 *
 * <p>All player-visible chat output goes through {@link Component#translatable}.
 * The keys are defined in {@code assets/freeserversaver/lang/en_us.json}
 * (and the 8 other locales we ship). The client resolves the translation
 * based on the player's selected language — a Japanese client sees
 * Japanese, a German client sees German, etc., even though the server
 * is identical for all.
 *
 * <p>Server-console output (admin running commands from the console)
 * falls back to en_us since the console has no locale.
 *
 * <p>All require permission level 2 (server op). The diagnostic data
 * isn't really sensitive, but exposing these to all players invites
 * spammy "/hg status" presses, and {@code inspect chunks} can be
 * mildly expensive on huge worlds.
 *
 * <p>Implementation note: the command instance is stateful — it holds
 * references to the live {@link HeapMonitor} and {@link HeapHistoryTracker}
 * so the subcommands can read fresh data on every invocation.
 */
public class HeapGuardianCommand {

    private static final SimpleDateFormat HISTORY_TIME =
        new SimpleDateFormat("HH:mm:ss");

    private final HeapMonitor monitor;
    private final HeapHistoryTracker history;
    private final LagSpikeDetector lagSpikes;
    private final AutoTuner autoTuner;
    private final ChunkPruningModule chunkPruning;
    private final StorageMonitor storage;
    private final ChunkPreGenModule chunkPregen;

    public HeapGuardianCommand(HeapMonitor monitor, HeapHistoryTracker history,
                               LagSpikeDetector lagSpikes, AutoTuner autoTuner,
                               ChunkPruningModule chunkPruning, StorageMonitor storage,
                               ChunkPreGenModule chunkPregen) {
        this.monitor = monitor;
        this.history = history;
        this.lagSpikes = lagSpikes;
        this.autoTuner = autoTuner;
        this.chunkPruning = chunkPruning;
        this.storage = storage;
        this.chunkPregen = chunkPregen;
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands
            .literal(HeapGuardian.MOD_ID)
            .requires(src -> src.hasPermission(2))
            .then(Commands.literal("help").executes(this::help))
            .then(Commands.literal("tuning").executes(this::tuning))
            .then(Commands.literal("status").executes(this::status))
            .then(Commands.literal("history").executes(this::history))
            .then(Commands.literal("metrics").executes(this::metrics))
            .then(Commands.literal("env").executes(this::env))
            .then(Commands.literal("lagspikes").executes(this::lagspikes))
            .then(Commands.literal("top")
                .then(Commands.literal("entities").executes(this::topEntities)))
            .then(Commands.literal("prune").executes(this::prune))
            .then(Commands.literal("storage").executes(this::storageCmd))
            .then(Commands.literal("pregen")
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, ChunkPreGenModule.MAX_RADIUS_CHUNKS))
                    .executes(this::pregen)))
            .then(Commands.literal("inspect")
                .then(Commands.literal("chunks").executes(this::inspectChunks)));

        dispatcher.register(root);
    }

    /** Shorthand to format a double to 1 decimal place — the value all our percentages use. */
    private static String fmt1(double d) {
        return String.format("%.1f", d);
    }

    private int help(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(
            () -> Component.translatable("freeserversaver.help.title").withStyle(ChatFormatting.BOLD),
            false);
        // Each entry is its own translation key — translators can adjust
        // descriptions per language without needing to know all of them.
        String[] keys = {
            "freeserversaver.help.entry.status",
            "freeserversaver.help.entry.history",
            "freeserversaver.help.entry.metrics",
            "freeserversaver.help.entry.env",
            "freeserversaver.help.entry.lagspikes",
            "freeserversaver.help.entry.topentities",
            "freeserversaver.help.entry.inspect",
            "freeserversaver.help.entry.tuning",
            "freeserversaver.help.entry.prune",
            "freeserversaver.help.entry.storage",
            "freeserversaver.help.entry.pregen",
        };
        for (String key : keys) {
            ctx.getSource().sendSuccess(() -> Component.translatable(key), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int lagspikes(CommandContext<CommandSourceStack> ctx) {
        List<LagSpikeDetector.Entry> spikes = lagSpikes.snapshot();
        if (spikes.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable(
                "freeserversaver.lagspikes.empty").withStyle(ChatFormatting.GREEN), false);
            return Command.SINGLE_SUCCESS;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable(
            "freeserversaver.lagspikes.title", spikes.size()).withStyle(ChatFormatting.BOLD), false);
        int shown = Math.min(spikes.size(), 15);
        for (int i = 0; i < shown; i++) {
            LagSpikeDetector.Entry e = spikes.get(i);
            String time = HISTORY_TIME.format(new Date(e.timestampMs()));
            // Component.translatable takes Object[] args; we pass String
            // representations of the numbers because the translation
            // template uses %s placeholders (consistent across locales).
            ChatFormatting color = colorFor(e.throttleAtSpike());
            ctx.getSource().sendSuccess(
                () -> Component.translatable("freeserversaver.lagspikes.entry",
                    time,
                    e.msptObserved(),
                    fmt1(e.heapPercent()),
                    e.throttleAtSpike().name(),
                    e.playerCount()).withStyle(color),
                false);
        }
        if (spikes.size() > shown) {
            int more = spikes.size() - shown;
            ctx.getSource().sendSuccess(() -> Component.translatable(
                "freeserversaver.lagspikes.more", more).withStyle(ChatFormatting.GRAY),
                false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int tuning(CommandContext<CommandSourceStack> ctx) {
        // Show the current AutoTuner state. There's no "/freeserversaver
        // config set <key> <value>" because runtime mutation of config
        // values is supported by NeoForge's ModConfigSpec but the API
        // surface is fragile across loader versions. Read-only inspection
        // via this command, persistent edits via the toml file + reload.
        double offset = autoTuner.currentOffset();
        ChatFormatting color = offset < 0 ? ChatFormatting.RED
            : offset > 0 ? ChatFormatting.GREEN
            : ChatFormatting.YELLOW;
        ctx.getSource().sendSuccess(() -> Component.translatable(
            "freeserversaver.tuning.title").withStyle(ChatFormatting.BOLD), false);
        ctx.getSource().sendSuccess(() -> Component.translatable(
            "freeserversaver.tuning.offset",
            String.format("%+.1f", offset)).withStyle(color),
            false);
        return Command.SINGLE_SUCCESS;
    }

    private int status(CommandContext<CommandSourceStack> ctx) {
        ThrottleLevel level = monitor.currentLevel();
        double pct = monitor.lastHeapPercent();
        ChatFormatting color = colorFor(level);

        ctx.getSource().sendSuccess(() -> Component.translatable(
            "freeserversaver.status.title").withStyle(ChatFormatting.BOLD), false);
        ctx.getSource().sendSuccess(() -> Component.translatable(
            "freeserversaver.status.line", level.name(), fmt1(pct)).withStyle(color), false);
        return Command.SINGLE_SUCCESS;
    }

    private int history(CommandContext<CommandSourceStack> ctx) {
        List<HeapHistoryTracker.Entry> entries = history.snapshot();
        if (entries.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable(
                "freeserversaver.history.empty"), false);
            return Command.SINGLE_SUCCESS;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable(
            "freeserversaver.history.title", entries.size()).withStyle(ChatFormatting.BOLD), false);
        int shown = Math.min(entries.size(), 20);
        for (int i = 0; i < shown; i++) {
            HeapHistoryTracker.Entry e = entries.get(i);
            String time = HISTORY_TIME.format(new Date(e.timestampMs()));
            ChatFormatting color = colorFor(e.current());
            ctx.getSource().sendSuccess(
                () -> Component.translatable("freeserversaver.history.entry",
                    time, e.previous().name(), e.current().name(), fmt1(e.heapPercent()))
                    .withStyle(color),
                false);
        }
        if (entries.size() > shown) {
            int more = entries.size() - shown;
            ctx.getSource().sendSuccess(() -> Component.translatable(
                "freeserversaver.history.more", more).withStyle(ChatFormatting.GRAY),
                false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int metrics(CommandContext<CommandSourceStack> ctx) {
        ThrottleLevel level = monitor.currentLevel();
        double pct = monitor.lastHeapPercent();
        var server = ctx.getSource().getServer();
        List<ChunkInspectTask.DimensionStats> chunkStats = ChunkInspectTask.run(server);
        int totalPlayers = server.getPlayerList().getPlayerCount();
        int totalLoaded = chunkStats.stream().mapToInt(ChunkInspectTask.DimensionStats::loadedChunks).sum();
        int totalForced = chunkStats.stream().mapToInt(ChunkInspectTask.DimensionStats::forcedChunks).sum();

        ctx.getSource().sendSuccess(() -> Component.translatable(
            "freeserversaver.metrics.title").withStyle(ChatFormatting.BOLD), false);
        ctx.getSource().sendSuccess(() -> Component.translatable(
            "freeserversaver.metrics.line.heap", fmt1(pct), level.name()).withStyle(colorFor(level)),
            false);
        ctx.getSource().sendSuccess(() -> Component.translatable(
            "freeserversaver.metrics.line.players", totalPlayers, totalLoaded, totalForced),
            false);
        ctx.getSource().sendSuccess(() -> Component.translatable(
            "freeserversaver.metrics.line.distance",
            server.getPlayerList().getViewDistance(),
            server.getPlayerList().getSimulationDistance()),
            false);
        return Command.SINGLE_SUCCESS;
    }

    private int env(CommandContext<CommandSourceStack> ctx) {
        EnvironmentInspector.EnvironmentSnapshot snap = EnvironmentInspector.lastSnapshot();
        if (snap == null) {
            ctx.getSource().sendSuccess(() -> Component.translatable(
                "freeserversaver.env.notyet"), false);
            return Command.SINGLE_SUCCESS;
        }

        ctx.getSource().sendSuccess(() -> Component.translatable(
            "freeserversaver.env.title").withStyle(ChatFormatting.BOLD), false);
        ctx.getSource().sendSuccess(() -> Component.translatable(
            "freeserversaver.env.line.heap", snap.heapMaxMB(), snap.heapUsedMB()), false);
        ctx.getSource().sendSuccess(() -> Component.translatable(
            "freeserversaver.env.line.cpu",
            snap.availableProcessors(), snap.jvmVersion(), snap.javaSpecVersion()),
            false);
        ctx.getSource().sendSuccess(() -> Component.translatable(
            "freeserversaver.env.line.captured", snap.startedAt()), false);

        // Heap-tier hint: same logic as the boot-time log, but the
        // translation key gives translators control over phrasing.
        long heapMB = snap.heapMaxMB();
        final String hintKey;
        final ChatFormatting hintColor;
        if (heapMB < 2_000) {
            hintKey = "freeserversaver.env.hint.low";
            hintColor = ChatFormatting.RED;
        } else if (heapMB > 3_500) {
            hintKey = "freeserversaver.env.hint.boost";
            hintColor = ChatFormatting.GREEN;
        } else {
            hintKey = "freeserversaver.env.hint.standard";
            hintColor = ChatFormatting.YELLOW;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable(hintKey).withStyle(hintColor), false);
        return Command.SINGLE_SUCCESS;
    }

    private int topEntities(CommandContext<CommandSourceStack> ctx) {
        var server = ctx.getSource().getServer();
        List<EntityCountTask.TypeCount> top = EntityCountTask.run(server, 10);
        if (top.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable(
                "freeserversaver.topentities.empty").withStyle(ChatFormatting.GRAY), false);
            return Command.SINGLE_SUCCESS;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable(
            "freeserversaver.topentities.title").withStyle(ChatFormatting.BOLD), false);
        for (EntityCountTask.TypeCount tc : top) {
            ctx.getSource().sendSuccess(() -> Component.translatable(
                "freeserversaver.topentities.entry",
                // Type IDs aren't localized — they're internal ids — but
                // the surrounding label IS. Padding moves into translation
                // so each locale can decide alignment.
                tc.typeId(), tc.count(), tc.dimensionId()), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int prune(CommandContext<CommandSourceStack> ctx) {
        int released = chunkPruning.runPruneNow();
        ctx.getSource().sendSuccess(() -> Component.translatable(
            "freeserversaver.prune.result", released).withStyle(ChatFormatting.BOLD),
            false);
        return Command.SINGLE_SUCCESS;
    }

    private int storageCmd(CommandContext<CommandSourceStack> ctx) {
        long bytes = storage.runScan();
        if (bytes < 0) {
            ctx.getSource().sendSuccess(() -> Component.translatable(
                "freeserversaver.storage.error").withStyle(ChatFormatting.RED), false);
            return Command.SINGLE_SUCCESS;
        }
        long mb = bytes / 1_048_576L;
        // Color-code by Aternos's 4GB cap proximity.
        ChatFormatting color = mb >= 4000 ? ChatFormatting.DARK_RED
            : mb >= 3500 ? ChatFormatting.RED
            : mb >= 3000 ? ChatFormatting.YELLOW
            : ChatFormatting.GREEN;
        ctx.getSource().sendSuccess(() -> Component.translatable(
            "freeserversaver.storage.result", mb).withStyle(color), false);
        return Command.SINGLE_SUCCESS;
    }

    private int pregen(CommandContext<CommandSourceStack> ctx) {
        int radius = IntegerArgumentType.getInteger(ctx, "radius");
        var src = ctx.getSource();
        var level = src.getLevel();
        // Center at command source's current chunk. Players run from their
        // position; rcon / console runs from world spawn.
        var centerPos = src.getPosition();
        var centerChunk = new net.minecraft.world.level.ChunkPos(
            ((int) centerPos.x) >> 4, ((int) centerPos.z) >> 4);

        int generated = chunkPregen.pregen(level, centerChunk, radius);
        if (generated < 0) {
            // Yielded to a competitor mod (Chunky) — message already
            // logged by the module.
            ctx.getSource().sendSuccess(() -> Component.translatable(
                "freeserversaver.pregen.yielded").withStyle(ChatFormatting.YELLOW), false);
            return Command.SINGLE_SUCCESS;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable(
            "freeserversaver.pregen.result", generated, radius)
            .withStyle(ChatFormatting.GREEN), false);
        return Command.SINGLE_SUCCESS;
    }

    private int inspectChunks(CommandContext<CommandSourceStack> ctx) {
        var server = ctx.getSource().getServer();
        List<ChunkInspectTask.DimensionStats> stats = ChunkInspectTask.run(server);

        ctx.getSource().sendSuccess(() -> Component.translatable(
            "freeserversaver.inspect.chunks.title", stats.size()).withStyle(ChatFormatting.BOLD),
            false);
        for (ChunkInspectTask.DimensionStats s : stats) {
            ctx.getSource().sendSuccess(() -> Component.translatable(
                "freeserversaver.inspect.chunks.entry",
                s.dimension(), s.loadedChunks(), s.forcedChunks(), s.playerCount()),
                false);
        }
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Map a tier to a chat color. Higher tiers get warmer colors —
     * matches the visual language of severity dashboards.
     */
    private static ChatFormatting colorFor(ThrottleLevel level) {
        return switch (level) {
            case NORMAL -> ChatFormatting.GREEN;
            case L1_MILD -> ChatFormatting.YELLOW;
            case L2_HEAVY -> ChatFormatting.GOLD;
            case L3_AGGRESSIVE -> ChatFormatting.RED;
            case L4_EMERGENCY -> ChatFormatting.DARK_RED;
        };
    }
}
