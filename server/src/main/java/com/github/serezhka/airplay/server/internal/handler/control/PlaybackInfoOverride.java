package com.github.serezhka.airplay.server.internal.handler.control;

/**
 * Optional live override for {@code GET /playback-info} while debugging YouTube ad EOS hangs.
 * Written by {@link HlsFcupService} signal file / auto-nudge; consumed by {@code ControlHandler}.
 */
public final class PlaybackInfoOverride {

    private static volatile Snapshot snapshot;

    private PlaybackInfoOverride() {
    }

    public static void clear() {
        snapshot = null;
    }

    public static void set(double duration, double position, double rate) {
        snapshot = new Snapshot(duration, position, rate, System.nanoTime());
    }

    public static Snapshot get() {
        return snapshot;
    }

    public record Snapshot(double duration, double position, double rate, long setAtNanos) {
    }
}
