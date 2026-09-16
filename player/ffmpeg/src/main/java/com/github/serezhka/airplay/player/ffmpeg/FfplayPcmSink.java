package com.github.serezhka.airplay.player.ffmpeg;

import com.github.serezhka.airplay.lib.AppLogs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Map;

/**
 * Plays interleaved little-endian S16 PCM via {@code ffplay} (Pulse/PipeWire-friendly on Linux).
 * Prefer this over {@link javax.sound.sampled.SourceDataLine}, which often binds ALSA HDMI and blocks.
 * <p>
 * FFmpeg 8+ pcm demuxer no longer accepts {@code -ac}; use {@code -ch_layout} instead.
 */
final class FfplayPcmSink implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FfplayPcmSink.class);

    private final Process process;
    private final OutputStream out;
    private final int sampleRate;
    private final int channels;
    private int writesSinceFlush;

    private FfplayPcmSink(Process process, int sampleRate, int channels) {
        this.process = process;
        this.out = process.getOutputStream();
        this.sampleRate = sampleRate;
        this.channels = channels;
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
        AppLogs.configureProcessLogging(pb, "ffmpeg");
        Process process = pb.start();
        FfplayPcmSink sink = new FfplayPcmSink(process, rate, ch);
        // Keep the pipe from looking like EOF before the first real samples arrive.
        byte[] priming = new byte[rate / 50 * ch * 2]; // ~20ms silence
        sink.write(priming, 0, priming.length);
        if (!process.isAlive()) {
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
        return process.isAlive();
    }

    void write(byte[] pcm, int offset, int length) throws IOException {
        if (!process.isAlive()) {
            throw new IOException("ffplay PCM sink dead");
        }
        out.write(pcm, offset, length);
        writesSinceFlush++;
        // Flushing every chunk stalls the grab loop; batch a bit.
        if (writesSinceFlush >= 8) {
            out.flush();
            writesSinceFlush = 0;
        }
    }

    @Override
    public void close() {
        try {
            out.flush();
            out.close();
        } catch (IOException ignored) {
            // ignore
        }
        process.destroy();
        try {
            process.waitFor();
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
