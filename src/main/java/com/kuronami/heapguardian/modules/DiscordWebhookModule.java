package com.kuronami.heapguardian.modules;

import com.kuronami.heapguardian.HeapGuardian;
import com.kuronami.heapguardian.config.HeapGuardianConfig;
import com.kuronami.heapguardian.monitor.ThrottleLevel;
import com.kuronami.heapguardian.monitor.ThrottleLevelChangedEvent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import net.neoforged.bus.api.SubscribeEvent;

/**
 * Sends throttle-level transitions to a Discord webhook.
 *
 * <p>The Aternos use case here is "I'm not watching the console, but I
 * want a Discord ping when my server is in trouble." Vanilla and the
 * existing perf-mod ecosystem give the operator zero visibility once
 * the world is running — Heap Guardian's whole point is to act early,
 * but if it never tells anyone, the operator only finds out after
 * players complain.
 *
 * <p>Design constraints:
 * <ul>
 *   <li><strong>Server-side only.</strong> Webhook URL is config, not
 *       per-player — Discord webhooks are URL-as-credential and we
 *       can't expose that to clients.</li>
 *   <li><strong>Async + non-blocking.</strong> Sent via
 *       {@link HttpClient#sendAsync}. A slow Discord response must not
 *       hold the server tick thread.</li>
 *   <li><strong>Rate-limited.</strong> Heap can oscillate around a
 *       boundary for short bursts (despite hysteresis); we don't want
 *       to spam the channel with 30 "L1→NORMAL" messages a minute.</li>
 *   <li><strong>Recovery suppressed by default.</strong> The operator
 *       cares about escalations ("server in trouble") far more than
 *       recoveries ("server fine again"). Recovery notifications stay
 *       behind a separate config flag.</li>
 *   <li><strong>Webhook URL not logged.</strong> Webhooks are credentials.
 *       If we log a failure, the URL stays out of the message; only
 *       the failure code is recorded.</li>
 * </ul>
 *
 * <p>The HTTP client is built lazily on first use — most users will
 * never set a webhook URL, and constructing an HttpClient costs a few
 * threads' worth of overhead that's pointless to pay if it's never used.
 */
public class DiscordWebhookModule {

    /** Discord allows 30 webhook calls / 60 s per webhook. We're vastly under that. */
    private static final long MIN_INTERVAL_MS = 60_000L; // one notification per minute, max

    /** HTTP timeout. Discord usually responds in <500 ms; 10 s is generous. */
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    /** Built on first successful send. {@code null} means "not yet initialized". */
    private volatile HttpClient httpClient;

    /** Timestamp of the most recent successful POST, for rate-limiting. */
    private final AtomicLong lastSentMs = new AtomicLong(0L);

    @SubscribeEvent
    public void onThrottleChanged(ThrottleLevelChangedEvent event) {
        if (Boolean.FALSE.equals(HeapGuardianConfig.ENABLE_DISCORD_WEBHOOK.get())) {
            return;
        }
        String url = HeapGuardianConfig.DISCORD_WEBHOOK_URL.get();
        if (url == null || url.isBlank() || !url.startsWith("https://")) {
            return;
        }
        // Discord webhooks always live on discord.com or canary.discord.com.
        // Refuse arbitrary URLs as a defense against an admin pasting a
        // malicious link into config; this also catches typos like missing
        // "https://".
        if (!url.contains("discord.com/api/webhooks/")) {
            HeapGuardian.LOGGER.warn(
                "[Webhook] Configured URL is not a Discord webhook (must contain 'discord.com/api/webhooks/'); skipping.");
            return;
        }

        // Recovery suppression: only fire on escalations unless the operator
        // explicitly opts in to recovery notifications.
        if (event.isRecovery()
            && Boolean.FALSE.equals(HeapGuardianConfig.WEBHOOK_NOTIFY_RECOVERY.get())) {
            return;
        }

        long now = System.currentTimeMillis();
        long last = lastSentMs.get();
        if (now - last < MIN_INTERVAL_MS) {
            return;
        }
        // CAS to avoid two concurrent sends within the window. If we lose the
        // race, the other thread already sent or is about to — either way we
        // skip silently.
        if (!lastSentMs.compareAndSet(last, now)) {
            return;
        }

        String payload = buildPayload(event);
        sendAsync(url, payload);
    }

    /**
     * Build the JSON body. Hand-rolled rather than pulling in a JSON lib —
     * the payload shape is fixed, and the only escape hazard is the level
     * name (which is enum-derived, so safe by construction).
     */
    private String buildPayload(ThrottleLevelChangedEvent event) {
        String emoji = event.current() == ThrottleLevel.L4_EMERGENCY ? "🔥" // 🔥
            : event.isEscalation() ? "⚠️"  // ⚠️
            : "✅";                              // ✅

        String content = String.format(
            "%s **Heap Guardian** — `%s → %s` (heap %.1f%%, used %d MB / max %d MB)",
            emoji,
            event.previous().name(),
            event.current().name(),
            event.heapPercent(),
            event.heapUsedBytes() / 1_048_576L,
            event.heapMaxBytes() / 1_048_576L);

        // Embed content in a minimal JSON envelope. Backslash-escape only
        // the characters Discord webhooks actually care about: quote and
        // backslash. Our content is already controlled enum/number text;
        // no shell injection or DOM-style risks.
        String escaped = content.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"content\":\"" + escaped + "\"}";
    }

    /**
     * Fire-and-forget POST. We don't await the response — if Discord
     * returns 4xx/5xx, log it; if the network is down, log that. The
     * server tick is never blocked.
     */
    private void sendAsync(String url, String payload) {
        HttpClient client = ensureClient();
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(HTTP_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("User-Agent", "HeapGuardian/0.1.0")
            .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
            .build();

        client.sendAsync(req, HttpResponse.BodyHandlers.discarding())
            .whenComplete((resp, err) -> {
                if (err != null) {
                    // Don't log the URL — it's a secret. The failure type
                    // is enough for an operator to debug.
                    HeapGuardian.LOGGER.warn(
                        "[Webhook] Discord notification failed: {}", err.getClass().getSimpleName());
                    return;
                }
                int status = resp.statusCode();
                if (status >= 400) {
                    HeapGuardian.LOGGER.warn("[Webhook] Discord responded HTTP {}", status);
                }
            });
    }

    private HttpClient ensureClient() {
        HttpClient c = httpClient;
        if (c != null) {
            return c;
        }
        synchronized (this) {
            if (httpClient == null) {
                httpClient = HttpClient.newBuilder()
                    .connectTimeout(HTTP_TIMEOUT)
                    .build();
            }
            return httpClient;
        }
    }
}
