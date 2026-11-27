package com.github.serezhka.airplay.app.ffmpeg.process;

import com.github.serezhka.airplay.app.common.support.NativeProcessLog;
import com.github.serezhka.airplay.app.common.support.PlayerWindowDefaults;
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
        return startH264Pipe(fps, true);
    }

    /** Video-only H.264 Annex-B pipe; {@code fullscreen=false} opens a resizable SDL window. */
    public static Process startH264Pipe(int fps, boolean fullscreen) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("ffplay");
        appendDisplayMode(cmd, false, fullscreen);
        cmd.add("-an");
        cmd.add("-loglevel");
        cmd.add("debug");
        cmd.add("-f");
        cmd.add("h264");
        cmd.add("-framerate");
        cmd.add(String.valueOf(fps));
        cmd.add("-codec:v");
        cmd.add("h264");
        cmd.add("-probesize");
        cmd.add("32");
        cmd.add("-analyzeduration");
        cmd.add("0");
        cmd.add("-flags");
        cmd.add("low_delay");
        cmd.add("-");
        ProcessBuilder pb = new ProcessBuilder(cmd);
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
        return startHls(playlistUri, startAt, volumeLinear, headless, true);
    }

    /**
     * HLS playback. Headless uses {@code -nodisp}; otherwise fullscreen ({@code -fs}) or a
     * fixed-size resizable window when {@code fullscreen=false}.
     */
    public static Process startHls(String playlistUri, double startAt, double volumeLinear,
                                   boolean headless, boolean fullscreen)
            throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("ffplay");
        appendDisplayMode(cmd, headless, fullscreen);
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
        // status line. Linux CI often has no Pulse — SDL dummy. Windows keeps the default
        // device (dummy breaks status-line latching on some BtbN builds).
        if (headless) {
            applyDisplayEnv(pb);
            if (isLinux()) {
                pb.environment().put("SDL_AUDIODRIVER", "dummy");
            }
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
     * Freeze / unfreeze a live ffplay without destroying its window.
     * Unix: {@code SIGSTOP}/{@code SIGCONT}. Windows: {@code NtSuspendProcess}/{@code NtResumeProcess}.
     *
     * @param signal {@code STOP} or {@code CONT}
     * @return true when the process was frozen/resumed; false when unavailable or gone
     */
    public static boolean signal(Process process, String signal) {
        if (process == null || !process.isAlive()) {
            return false;
        }
        String kind = signal == null ? "" : signal.trim().toUpperCase(Locale.ROOT);
        if (isWindows()) {
            if ("STOP".equals(kind)) {
                return WindowsProcessControl.suspend(process.pid());
            }
            if ("CONT".equals(kind)) {
                return WindowsProcessControl.resume(process.pid());
            }
            return false;
        }
        try {
            Process kill = new ProcessBuilder("kill", "-" + kind, Long.toString(process.pid()))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!kill.waitFor(2, TimeUnit.SECONDS) || kill.exitValue() != 0) {
                log.warn("kill -{} pid={} failed", kind, process.pid());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("kill -{} failed: {}", kind, e.toString());
            return false;
        }
    }

    /**
     * Tear down a (possibly frozen) ffplay: unfreeze so destroy can reap, then destroy/forcibly.
     */
    public static void destroy(Process process) {
        if (process == null) {
            return;
        }
        if (process.isAlive()) {
            // Suspended processes often ignore SIGTERM until CONT / NtResume.
            signal(process, "CONT");
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
     * Live per-process gain without relaunching ffplay.
     * Linux: Pulse/PipeWire via {@code pactl}. Windows: Core Audio session volume.
     *
     * @return true when applied or soft-deferred (no session yet); false on hard failure
     */
    public static boolean setLiveVolume(long pid, double volumeLinear) {
        if (isWindows()) {
            return WindowsAudioSessionVolume.setByPid(pid, volumeLinear);
        }
        return PulseSinkInputVolume.setByPid(pid, volumeLinear);
    }

    /** @deprecated use {@link #setLiveVolume(long, double)} */
    @Deprecated
    public static boolean setPulseVolume(long pid, double volumeLinear) {
        return setLiveVolume(pid, volumeLinear);
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

    /**
     * Display flags for headed/headless ffplay. Package-visible for unit tests.
     * Headless → {@code -nodisp}; fullscreen → {@code -fs}; else {@code -x/-y} window size.
     */
    static void appendDisplayMode(List<String> cmd, boolean headless, boolean fullscreen) {
        if (headless) {
            cmd.add("-nodisp");
            return;
        }
        if (fullscreen) {
            cmd.add("-fs");
            return;
        }
        cmd.add("-x");
        cmd.add(String.valueOf(PlayerWindowDefaults.WIDTH));
        cmd.add("-y");
        cmd.add(String.valueOf(PlayerWindowDefaults.HEIGHT));
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
