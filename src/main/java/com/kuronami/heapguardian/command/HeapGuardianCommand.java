package com.kuronami.heapguardian.command;

import com.kuronami.heapguardian.HeapGuardian;
import com.kuronami.heapguardian.monitor.HeapHistoryTracker;
import com.kuronami.heapguardian.monitor.HeapMonitor;
import com.kuronami.heapguardian.monitor.ThrottleLevel;
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
 * {@code /heapguardian} command tree.
 *
 * <p>Subcommands:
 * <ul>
 *   <li>{@code status} — current tier + heap percentage. Always cheap.</li>
 *   <li>{@code history} — recent throttle-level transitions, newest first.</li>
 *   <li>{@code inspect chunks} — per-dimension loaded/forced/player counts.</li>
 *   <li>{@code metrics} — combined summary: heap, tier, chunks, players.</li>
 * </ul>
 *
 * <p>All require permission level 2 (server op). The diagnostic data
 * isn't really sensitive, but exposing these to all players invites
 * spammy "/hg status" presses, and {@code inspect chunks} can be
 * mildly expensive on huge worlds.
 *
 * <p>Implementation note: the command instance is stateful — it holds
 * references to the live {@link HeapMonitor} and {@link HeapHistoryTracker}
 * so the subcommands can read fresh data on every invocation. The
 * dispatcher itself only sees the {@link com.mojang.brigadier.Command}
 * lambdas, which close over those references.
 */
public class HeapGuardianCommand {

    private static final SimpleDateFormat HISTORY_TIME =
        new SimpleDateFormat("HH:mm:ss");

    private final HeapMonitor monitor;
    private final HeapHistoryTracker history;

    public HeapGuardianCommand(HeapMonitor monitor, HeapHistoryTracker history) {
        this.monitor = monitor;
        this.history = history;
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands
            .literal(HeapGuardian.MOD_ID)
            .requires(src -> src.hasPermission(2))
            .then(Commands.literal("status").executes(this::status))
            .then(Commands.literal("history").executes(this::history))
            .then(Commands.literal("metrics").executes(this::metrics))
            .then(Commands.literal("inspect")
                .then(Commands.literal("chunks").executes(this::inspectChunks)));

        dispatcher.register(root);
    }

    private int status(CommandContext<CommandSourceStack> ctx) {
        ThrottleLevel level = monitor.currentLevel();
        double pct = monitor.lastHeapPercent();
        ChatFormatting color = colorFor(level);

        ctx.getSource().sendSuccess(() -> Component.literal("Heap Guardian status:")
            .withStyle(ChatFormatting.BOLD), false);
        ctx.getSource().sendSuccess(() -> Component.literal(
            String.format("  Tier: %s  |  Heap: %.1f%%", level.name(), pct))
            .withStyle(color), false);
        return Command.SINGLE_SUCCESS;
    }

    private int history(CommandContext<CommandSourceStack> ctx) {
        List<HeapHistoryTracker.Entry> entries = history.snapshot();
        if (entries.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                "Heap Guardian history: (no transitions recorded yet)"), false);
            return Command.SINGLE_SUCCESS;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
            "Heap Guardian history (" + entries.size() + " entries, newest first):")
            .withStyle(ChatFormatting.BOLD), false);
        // Cap displayed rows so a 1000-entry history doesn't spam chat.
        int shown = Math.min(entries.size(), 20);
        for (int i = 0; i < shown; i++) {
            HeapHistoryTracker.Entry e = entries.get(i);
            String time = HISTORY_TIME.format(new Date(e.timestampMs()));
            String line = String.format("  [%s] %s -> %s (heap %.1f%%)",
                time, e.previous().name(), e.current().name(), e.heapPercent());
            ChatFormatting color = colorFor(e.current());
            ctx.getSource().sendSuccess(
                () -> Component.literal(line).withStyle(color), false);
        }
        if (entries.size() > shown) {
            int more = entries.size() - shown;
            ctx.getSource().sendSuccess(() -> Component.literal(
                "  ... " + more + " older entries not shown").withStyle(ChatFormatting.GRAY),
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

        ctx.getSource().sendSuccess(() -> Component.literal(
            "Heap Guardian metrics:").withStyle(ChatFormatting.BOLD), false);
        ctx.getSource().sendSuccess(() -> Component.literal(
            String.format("  Heap: %.1f%%  |  Tier: %s", pct, level.name()))
            .withStyle(colorFor(level)), false);
        ctx.getSource().sendSuccess(() -> Component.literal(
            String.format("  Players: %d  |  Loaded chunks: %d  |  Force-loaded: %d",
                totalPlayers, totalLoaded, totalForced)), false);
        ctx.getSource().sendSuccess(() -> Component.literal(
            String.format("  View distance: %d  |  Simulation distance: %d",
                server.getPlayerList().getViewDistance(),
                server.getPlayerList().getSimulationDistance())), false);
        return Command.SINGLE_SUCCESS;
    }

    private int inspectChunks(CommandContext<CommandSourceStack> ctx) {
        var server = ctx.getSource().getServer();
        List<ChunkInspectTask.DimensionStats> stats = ChunkInspectTask.run(server);

        ctx.getSource().sendSuccess(() -> Component.literal(
            "Chunk inspection (" + stats.size() + " dimensions):")
            .withStyle(ChatFormatting.BOLD), false);
        for (ChunkInspectTask.DimensionStats s : stats) {
            String line = String.format(
                "  %s: loaded=%d, forced=%d, players=%d",
                s.dimension(), s.loadedChunks(), s.forcedChunks(), s.playerCount());
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
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
