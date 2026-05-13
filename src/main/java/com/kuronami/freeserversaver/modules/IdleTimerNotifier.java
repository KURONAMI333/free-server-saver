package com.kuronami.freeserversaver.modules;

import com.kuronami.freeserversaver.HeapGuardian;
import com.kuronami.freeserversaver.config.HeapGuardianConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * Tracks server-empty transitions for Aternos's idle-shutdown timer.
 *
 * <p>Aternos stops servers automatically when no player has been
 * connected for several minutes. The exact countdown isn't exposed to
 * us — Aternos's panel shows it, but the in-process JVM doesn't see
 * the timer. What we CAN do is log + broadcast around player-count
 * transitions, so:
 * <ul>
 *   <li>The operator's server.log shows exactly when the server emptied,
 *       which makes "why did my server stop while I was gone" obvious
 *       in retrospect.</li>
 *   <li>If a player rejoins later, they see a chat message confirming
 *       the timer reset — useful when multiple friends are coordinating
 *       "stay online so the server doesn't die."</li>
 * </ul>
 *
 * <p>What this module does NOT do: try to keep the server alive by
 * faking activity. That's exactly what Aternos bans (it's why Carpet
 * mod isn't allowed). Heap Guardian is built to live within Aternos's
 * rules; this module is purely informational.
 *
 * <h3>Player-count tracking</h3>
 * <p>{@code PlayerLoggedInEvent} and {@code PlayerLoggedOutEvent} both
 * fire BEFORE the player is added/removed from the {@code PlayerList},
 * so the count read inside the handler is OFF by one. We adjust:
 * <ul>
 *   <li>Login handler: {@code current + 1} after the event</li>
 *   <li>Logout handler: {@code current - 1} after the event</li>
 * </ul>
 */
public class IdleTimerNotifier {

    private MinecraftServer server;

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        server = null;
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (server == null) return;
        if (Boolean.FALSE.equals(HeapGuardianConfig.ENABLE_IDLE_NOTIFIER.get())) {
            return;
        }

        // After this event the count will be N (current N-1 + this one).
        int afterCount = server.getPlayerList().getPlayerCount() + 1;
        if (afterCount == 1) {
            HeapGuardian.LOGGER.info(
                "[IdleTimer] First player joined ('{}'). Aternos idle timer reset.",
                event.getEntity().getName().getString());
            // The player who just joined can see this; broadcast to them
            // (and any others) so they know they bought the server time.
            event.getEntity().sendSystemMessage(Component.translatable(
                "freeserversaver.idle.first_join"));
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (server == null) return;
        if (Boolean.FALSE.equals(HeapGuardianConfig.ENABLE_IDLE_NOTIFIER.get())) {
            return;
        }

        // After this event the count will be N-1 (current N includes the leaver).
        int afterCount = server.getPlayerList().getPlayerCount() - 1;
        if (afterCount == 0) {
            HeapGuardian.LOGGER.info(
                "[IdleTimer] Last player left ('{}'). Aternos idle countdown "
                + "starts — server will stop after several minutes of no activity.",
                event.getEntity().getName().getString());
        }
    }
}
