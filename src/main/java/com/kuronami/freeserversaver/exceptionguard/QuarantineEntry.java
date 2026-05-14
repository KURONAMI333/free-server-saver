package com.kuronami.freeserversaver.exceptionguard;

/**
 * Immutable record of a single quarantine event. Used by
 * {@code /freeserversaver quarantine} to show the operator what was
 * discarded and when.
 *
 * <p>{@code descriptor} for an entity is "{@code ZOMBIE @ -123,64,42}",
 * for a block entity is "{@code minecraft:hopper @ 100,80,-300}". Free
 * text — the command output is human-only, not machine-parsed.
 *
 * <p>{@code reason} is the top-of-stack summary produced by
 * {@code ExceptionGuard.summarize()}, e.g.
 * "{@code NullPointerException at net.minecraft.world.entity.monster.Zombie.tick:142}".
 */
public final class QuarantineEntry {

    /** Type tag. Used by the command to color-code or label the row. */
    public enum Kind { ENTITY, BLOCK_ENTITY }

    private final long timestampMs;
    private final Kind kind;
    private final String descriptor;
    private final String reason;

    private QuarantineEntry(long timestampMs, Kind kind, String descriptor, String reason) {
        this.timestampMs = timestampMs;
        this.kind = kind;
        this.descriptor = descriptor;
        this.reason = reason;
    }

    public static QuarantineEntry ofEntity(long ts, String descriptor, String reason) {
        return new QuarantineEntry(ts, Kind.ENTITY, descriptor, reason);
    }

    public static QuarantineEntry ofBlockEntity(long ts, String descriptor, String reason) {
        return new QuarantineEntry(ts, Kind.BLOCK_ENTITY, descriptor, reason);
    }

    public long timestampMs() { return timestampMs; }
    public Kind kind()        { return kind; }
    public String descriptor(){ return descriptor; }
    public String reason()    { return reason; }
}
