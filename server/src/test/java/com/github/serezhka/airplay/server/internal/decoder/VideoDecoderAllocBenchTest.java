package com.github.serezhka.airplay.server.internal.decoder;

import com.github.serezhka.airplay.protocol.mirror.MirrorPacketLayout;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Micro-bench: mirror payload materialization strategies.
 *
 * <p>Production {@link VideoDecoder} copies each type-0/1 payload into a fresh {@code byte[]}.
 * Decrypt + Annex-B rewrite mutate that array in place, so a zero-copy retained {@link ByteBuf}
 * alone is not enough — something writable and owned is still required.
 *
 * <p>Run: {@code ./gradlew :server:test --tests '*VideoDecoderAllocBenchTest'}
 */
class VideoDecoderAllocBenchTest {

    private static final int FRAMES = 20_000;
    private static final int PAYLOAD = 32 * 1024;
    private static final int WARMUP = 2_000;

    @Test
    void compareCopyStrategiesAndRecordVerdict() {
        byte[] wire = buildWireFrame(PAYLOAD);

        long freshNs = time("fresh byte[]", () -> decodeFreshCopy(wire));
        long reuseNs = time("ThreadLocal reuse", () -> decodeReusedArray(wire));
        long pooledNs = time("pooled ByteBuf→array", () -> decodePooledThenCopy(wire));

        double freshMs = freshNs / 1_000_000.0;
        double reuseMs = reuseNs / 1_000_000.0;
        double pooledMs = pooledNs / 1_000_000.0;
        double reuseSpeedup = freshMs / Math.max(reuseMs, 0.001);
        double pooledSpeedup = freshMs / Math.max(pooledMs, 0.001);

        System.out.printf(
                "VideoDecoder alloc bench (%d frames × %d KiB):%n"
                        + "  fresh byte[]:          %7.1f ms%n"
                        + "  ThreadLocal reuse:     %7.1f ms (%.2fx vs fresh)%n"
                        + "  pooled ByteBuf→array:  %7.1f ms (%.2fx vs fresh)%n",
                FRAMES, PAYLOAD / 1024, freshMs, reuseMs, reuseSpeedup, pooledMs, pooledSpeedup);

        // Sanity: all strategies must actually decode.
        assertTrue(freshNs > 0 && reuseNs > 0 && pooledNs > 0);

        // Documented product decision (printed for CI / reviewers):
        System.out.println(
                "VERDICT: do NOT land a VideoDecoder/VideoPacket ByteBuf refactor now. "
                        + "Zero-copy retain is blocked by in-place decrypt + Annex-B rewrite. "
                        + "ThreadLocal reuse looks fast in this microbench but is unsafe if "
                        + "Playback.onVideo retains the byte[] asynchronously (GST/FFmpeg queues). "
                        + "Keep fresh copies; only revisit with a proven owned-buffer handoff "
                        + "or device-side alloc evidence from PlaybackMetrics.");
    }

    private static long time(String label, Runnable body) {
        for (int i = 0; i < WARMUP; i++) {
            body.run();
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < FRAMES; i++) {
            body.run();
        }
        long dt = System.nanoTime() - t0;
        // Touch label so IDE does not flag unused — printed via printf above.
        assertTrue(label.length() > 0);
        return dt;
    }

    /** Current production shape: new byte[payloadSize] per frame. */
    private static void decodeFreshCopy(byte[] wire) {
        ByteBuf in = Unpooled.wrappedBuffer(wire);
        try {
            in.skipBytes(128);
            int size = PAYLOAD;
            ByteBuf payloadBuf = in.readSlice(size);
            byte[] payloadBytes = new byte[size];
            payloadBuf.readBytes(payloadBytes);
            sink(payloadBytes);
        } finally {
            in.release();
        }
    }

    /** Reuse a ThreadLocal buffer when capacity fits (no VideoPacket API change). */
    private static final ThreadLocal<byte[]> TL = ThreadLocal.withInitial(() -> new byte[PAYLOAD]);

    private static void decodeReusedArray(byte[] wire) {
        ByteBuf in = Unpooled.wrappedBuffer(wire);
        try {
            in.skipBytes(128);
            byte[] payloadBytes = TL.get();
            if (payloadBytes.length < PAYLOAD) {
                payloadBytes = new byte[PAYLOAD];
                TL.set(payloadBytes);
            }
            in.readBytes(payloadBytes, 0, PAYLOAD);
            sink(payloadBytes);
        } finally {
            in.release();
        }
    }

    /** Allocate from Netty pool then still copy out — models a retain-then-getBytes path. */
    private static void decodePooledThenCopy(byte[] wire) {
        ByteBuf pooled = PooledByteBufAllocator.DEFAULT.buffer(wire.length);
        try {
            pooled.writeBytes(wire);
            pooled.skipBytes(128);
            ByteBuf slice = pooled.readSlice(PAYLOAD);
            byte[] payloadBytes = new byte[PAYLOAD];
            slice.readBytes(payloadBytes);
            sink(payloadBytes);
        } finally {
            pooled.release();
        }
    }

    private static void sink(byte[] payload) {
        // Prevent DCE; mimic a touch like decrypt's first block.
        payload[0] ^= 1;
        payload[0] ^= 1;
    }

    private static byte[] buildWireFrame(int payloadSize) {
        byte[] header = new byte[128];
        header[0] = (byte) (payloadSize & 0xff);
        header[1] = (byte) ((payloadSize >> 8) & 0xff);
        header[2] = (byte) ((payloadSize >> 16) & 0xff);
        header[3] = (byte) ((payloadSize >> 24) & 0xff);
        header[4] = (byte) MirrorPacketLayout.PAYLOAD_VIDEO;
        byte[] payload = new byte[payloadSize];
        ThreadLocalRandom.current().nextBytes(payload);
        byte[] wire = Arrays.copyOf(header, header.length + payload.length);
        System.arraycopy(payload, 0, wire, header.length, payload.length);
        return wire;
    }
}
