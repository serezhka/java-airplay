package com.github.serezhka.airplay.app.ffmpeg.process;

import com.github.serezhka.airplay.app.common.support.NativeProcessLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Facade over system {@code ffplay} child processes: spawn, Unix signals, teardown, and env.
 * Keeps {@link ProcessBuilder} details out of playback / HLS code.
 */
public final class FfplayProcess {

    private static final Logger log = LoggerFactory.getLogger(FfplayProcess.class);

    private FfplayProcess() {
    }

    /** Video-only H.264 Annex-B pipe for screen mirroring ({@code -an}, dummy SDL audio). */
    public static Process startH264Pipe(int fps) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                "ffplay", "-fs", "-an", "-loglevel", "debug",
                "-f", "h264",
                "-framerate", String.valueOf(fps),
                "-codec:v", "h264", "-probesize", "32",
                "-analyzeduration", "0", "-flags", "low_delay", "-");
        applyDisplayEnv(pb);
        NativeProcessLog.configureProcessLogging(pb, "ffmpeg");
        Process process = pb.start();
        if (!process.isAlive()) {
            throw new IOException("ffplay exited immediately after start");
        }
        return process;
    }

    /**
     * HLS (or local playlist) playback. Stdout is a pipe for the caller’s status-line pump;
     * stderr is merged into that pipe.
     */
    public static Process startHls(String playlistUri, double startAt, double volumeLinear, boolean headless)
            throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("ffplay");
        cmd.add(headless ? "-nodisp" : "-fs");
        cmd.add("-autoexit");
        // info (not error) so the status line is visible; the log pump keeps only real messages.
        cmd.add("-loglevel");
        cmd.add("info");
        // FFmpeg 6+/8 default extension_picky rejects HLS segment URLs without .ts/.m4s.
        if (isHlsPlaylistUri(playlistUri)) {
            cmd.add("-extension_picky");
            cmd.add("0");
            cmd.add("-allowed_extensions");
            cmd.add("ALL");
            cmd.add("-allowed_segment_extensions");
            cmd.add("ALL");
        }
        // -fflags nobuffer makes ffplay exit in ~200ms on short VOD ENDLIST items.
        cmd.add("-flags");
        cmd.add("low_delay");
        cmd.add("-framedrop");
        // Sync to the video clock. "audio" holds the first frame until the audio
        // clock exists, which on HLS is the multi-second probe.
        cmd.add("-sync");
        cmd.add("video");
        cmd.add("-volume");
        cmd.add(String.valueOf(Math.max(0, Math.min(100, (int) Math.round(volumeLinear * 100)))));
        if (startAt > 0.05) {
            cmd.add("-ss");
            cmd.add(String.format(Locale.US, "%.3f", startAt));
        }
        cmd.add("-probesize");
        cmd.add("32768");
        cmd.add("-analyzeduration");
        cmd.add("200000");
        cmd.add(playlistUri);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        // Keep an audio graph (do not use -an): ffplay -nodisp needs it for the clock /
        // status line. Headless CI has no device — force SDL dummy instead of Pulse.
        if (headless) {
            applyDisplayEnv(pb);
            pb.environment().put("SDL_AUDIODRIVER", "dummy");
        } else {
            applyPulseAudioEnv(pb);
        }
        if (isHlsPlaylistUri(playlistUri)) {
            Ipv4Getaddrinfo.apply(pb);
        }
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
        return pb.start();
    }

    /** PCM sink: little-endian S16 on stdin, no video window. */
    public static Process startPcmSink(int sampleRate, int channels) throws IOException {
        int rate = sampleRate > 0 ? sampleRate : 44_100;
        int ch = channels > 0 ? channels : 2;
        String layout = ch <= 1 ? "mono" : "stereo";
        ProcessBuilder pb = new ProcessBuilder(
                "ffplay", "-nodisp", "-loglevel", "error",
                "-fflags", "nobuffer", "-flags", "low_delay",
                "-f", "s16le", "-sample_rate", String.valueOf(rate), "-ch_layout", layout,
                "-i", "pipe:0");
        applyPulseAudioEnv(pb);
        if (isLinux()) {
            pb.environment().put("SDL_AUDIOBUFFERSIZE", "1024");
        }
        NativeProcessLog.configureProcessLogging(pb, "ffmpeg");
        return pb.start();
    }

    /**
     * Deliver a Unix signal to a live process ({@code STOP}/{@code CONT}).
     *
     * @return true when the signal was delivered; false on Windows or when the process is gone
     */
    public static boolean signal(Process process, String signal) {
        if (isWindows() || process == null || !process.isAlive()) {
            return false;
        }
        try {
            Process kill = new ProcessBuilder("kill", "-" + signal, Long.toString(process.pid()))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!kill.waitFor(2, TimeUnit.SECONDS) || kill.exitValue() != 0) {
                log.warn("kill -{} pid={} failed", signal, process.pid());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("kill -{} failed: {}", signal, e.toString());
            return false;
        }
    }

    /**
     * Tear down a (possibly SIGSTOP’d) ffplay: CONT so destroy can reap, then destroy/forcibly.
     */
    public static void destroy(Process process) {
        if (process == null) {
            return;
        }
        if (process.isAlive() && !isWindows()) {
            try {
                new ProcessBuilder("kill", "-CONT", Long.toString(process.pid()))
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start()
                        .waitFor(1, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // SIGKILL below still reaps a stopped process.
            }
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

    /**
     * Live Pulse/PipeWire sink-input gain for {@code pid}. Falls back to false when pactl cannot
     * touch the process so callers can reopen ffplay.
     */
    public static boolean setPulseVolume(long pid, double volumeLinear) {
        return PulseSinkInputVolume.setByPid(pid, volumeLinear);
    }

    /**
     * X11 display only — for video-only ffplay (no audio device). Prefer this over
     * {@link #applyPulseAudioEnv} when the process has {@code -an}; Pulse is often
     * missing under CI/xvfb and aborts SDL before the first frame.
     */
    public static void applyDisplayEnv(ProcessBuilder pb) {
        Map<String, String> env = pb.environment();
        if (isLinux()) {
            env.putIfAbsent("SDL_VIDEODRIVER", "x11");
            env.putIfAbsent("SDL_AUDIODRIVER", "dummy");
        }
        copyEnv(env, "DISPLAY");
        copyEnv(env, "XAUTHORITY");
        copyEnv(env, "XDG_RUNTIME_DIR");
    }

    /** Prefer Pulse/PipeWire over ALSA HDMI when ffplay is spawned from Java on Linux. */
    public static void applyPulseAudioEnv(ProcessBuilder pb) {
        applyDisplayEnv(pb);
        Map<String, String> env = pb.environment();
        if (isLinux()) {
            // Override dummy from applyDisplayEnv — this process needs real audio.
            env.put("SDL_AUDIODRIVER", "pulse");
        }
        copyEnv(env, "PULSE_SERVER");
        copyEnv(env, "PULSE_SINK");
    }

    public static boolean isHlsPlaylistUri(String playlistUri) {
        if (playlistUri == null) {
            return false;
        }
        String lower = playlistUri.toLowerCase(Locale.ROOT);
        int q = lower.indexOf('?');
        if (q >= 0) {
            lower = lower.substring(0, q);
        }
        return lower.endsWith(".m3u8") || lower.endsWith(".m3u");
    }

    private static void copyEnv(Map<String, String> env, String key) {
        String value = System.getenv(key);
        if (value != null && !value.isBlank()) {
            env.put(key, value);
        }
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
