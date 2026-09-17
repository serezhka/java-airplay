package com.github.serezhka.airplay.player.ffmpeg;

import com.github.serezhka.airplay.lib.AppLogs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plays interleaved little-endian S16 PCM via {@code ffplay} (Pulse/PipeWire-friendly on Linux).
 * Prefer this over {@link javax.sound.sampled.SourceDataLine}, which often binds ALSA HDMI and blocks.
 * <p>
 * FFmpeg 8+ pcm demuxer no longer accepts {@code -ac}; use {@code -ch_layout} instead.
 * Writes go through a bounded queue so a stalled Pulse sink cannot inflate AirPlay latency.
 */
final class FfplayPcmSink implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FfplayPcmSink.class);
    /** Drop when more than ~150ms of PCM is waiting (stereo S16 @ rate). */
    private static final double MAX_QUEUED_SECONDS = 0.15;

    private final Process process;
    private final OutputStream out;
    private final int sampleRate;
    private final int channels;
    private final ArrayBlockingQueue<byte[]> queue;
    private final Thread writer;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile long droppedChunks;

    private FfplayPcmSink(Process process, int sampleRate, int channels) {
        this.process = process;
        this.out = process.getOutputStream();
        this.sampleRate = sampleRate;
        this.channels = channels;
        int bytesPerSec = Math.max(1, sampleRate) * Math.max(1, channels) * 2;
        int maxChunks = Math.max(4, (int) Math.ceil(MAX_QUEUED_SECONDS * bytesPerSec / 2048.0));
        this.queue = new ArrayBlockingQueue<>(maxChunks);
        this.writer = new Thread(this::drainLoop, "ffplay-pcm-writer");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    static FfplayPcmSink start(int sampleRate, int channels) throws IOException {
        int rate = sampleRate > 0 ? sampleRate : 44_100;
        int ch = channels > 0 ? channels : 2;
        String layout = ch <= 1 ? "mono" : "stereo";
        // FFmpeg 7+/8 pcm demuxer: -sample_rate / -ch_layout (not -ar / -ac).
        ProcessBuilder pb = new ProcessBuilder(
                "ffplay", "-nodisp", "-loglevel", "error",
                "-fflags", "nobuffer", "-flags", "low_delay",
                "-f", "s16le", "-sample_rate", String.valueOf(rate), "-ch_layout", layout,
                "-i", "pipe:0");
        forcePulseAudioEnv(pb);
        // Smaller SDL buffer → less path latency on Pulse/xrdp (Linux only).
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
            pb.environment().put("SDL_AUDIOBUFFERSIZE", "1024");
        }
        AppLogs.configureProcessLogging(pb, "ffmpeg");
        Process process = pb.start();
        FfplayPcmSink sink = new FfplayPcmSink(process, rate, ch);
        // Keep the pipe from looking like EOF before the first real samples arrive.
        byte[] priming = new byte[rate / 50 * ch * 2]; // ~20ms silence
        sink.write(priming, 0, priming.length);
        if (!process.isAlive()) {
            sink.close();
            throw new IOException("ffplay PCM sink exited immediately (check -ch_layout / ffmpeg version)");
        }
        log.info("ffplay PCM sink started rate={}Hz ch_layout={} pid={}", rate, layout, process.pid());
        return sink;
    }

    int sampleRate() {
        return sampleRate;
    }

    int channels() {
        return channels;
    }

    boolean isAlive() {
        return !closed.get() && process.isAlive();
    }

    void write(byte[] pcm, int offset, int length) throws IOException {
        if (closed.get() || !process.isAlive()) {
            throw new IOException("ffplay PCM sink dead");
        }
        if (length <= 0) {
            return;
        }
        byte[] chunk = Arrays.copyOfRange(pcm, offset, offset + length);
        while (!queue.offer(chunk)) {
            // Drop oldest queued audio rather than block the decode/display loop.
            if (queue.poll() != null) {
                droppedChunks++;
            } else {
                break;
            }
        }
    }

    private void drainLoop() {
        try {
            while (!closed.get() && process.isAlive()) {
                byte[] chunk = queue.poll(50, TimeUnit.MILLISECONDS);
                if (chunk == null) {
                    continue;
                }
                out.write(chunk);
                // Avoid flush-per-chunk (adds tens of ms of syscall latency on Pulse/xrdp).
                if (queue.isEmpty()) {
                    out.flush();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log.debug("ffplay PCM writer stopped: {}", e.toString());
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        long dropped = droppedChunks;
        if (dropped > 0) {
            log.info("ffplay PCM sink closed after dropping {} queued chunk(s) (latency cap)", dropped);
        }
        queue.clear();
        writer.interrupt();
        try {
            writer.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            out.close();
        } catch (IOException ignored) {
            // ignore
        }
        process.destroy();
        try {
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    /** Prefer Pulse/PipeWire over ALSA HDMI when ffplay is spawned from Java on Linux. */
    static void forcePulseAudioEnv(ProcessBuilder pb) {
        Map<String, String> env = pb.environment();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("linux")) {
            env.putIfAbsent("SDL_AUDIODRIVER", "pulse");
        }
        copyEnv(env, "PULSE_SERVER");
        copyEnv(env, "PULSE_SINK");
        copyEnv(env, "DISPLAY");
    }

    private static void copyEnv(Map<String, String> env, String key) {
        String value = System.getenv(key);
        if (value != null && !value.isBlank()) {
            env.put(key, value);
        }
    }
}
