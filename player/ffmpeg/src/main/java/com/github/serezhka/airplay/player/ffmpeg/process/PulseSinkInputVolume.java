package com.github.serezhka.airplay.player.ffmpeg.process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adjusts a running {@code ffplay}/{@code pulse} sink-input volume without restarting the process.
 * Uses {@code pactl} (PulseAudio / PipeWire compatibility layer). Returns {@code false} when
 * the helper is unavailable so callers can fall back to reopen.
 */
final class PulseSinkInputVolume {

    private static final Logger log = LoggerFactory.getLogger(PulseSinkInputVolume.class);
    private static final Pattern SINK_INPUT = Pattern.compile("^Sink Input #(\\d+)\\s*$");
    private static final Pattern PROCESS_ID = Pattern.compile(
            "application\\.process\\.id\\s*=\\s*\"(\\d+)\"");

    private PulseSinkInputVolume() {
    }

    /**
     * @param pid    target media process (ffplay)
     * @param linear 0.0–1.0
     * @return true when at least one sink-input for {@code pid} was updated, or there was nothing
     *         to update (process not yet audible); false when pactl is missing/failed
     */
    static boolean setByPid(long pid, double linear) {
        if (pid <= 0) {
            return true;
        }
        int percent = Math.max(0, Math.min(100, (int) Math.round(linear * 100.0)));
        List<Integer> indexes;
        try {
            indexes = sinkInputIndexesForPid(pid);
        } catch (Exception e) {
            log.debug("pactl list sink-inputs failed: {}", e.toString());
            return false;
        }
        if (indexes.isEmpty()) {
            // ffplay may not have registered a sink-input yet; volumeLinear is still stored.
            log.debug("No Pulse sink-input for pid={} yet; volume={}%% deferred", pid, percent);
            return true;
        }
        boolean any = false;
        for (int index : indexes) {
            if (setSinkInputVolume(index, percent)) {
                any = true;
            }
        }
        if (any) {
            log.debug("Pulse sink-input volume pid={} -> {}% (inputs={})", pid, percent, indexes);
        }
        return any;
    }

    /** Visible for tests — parse {@code pactl list sink-inputs} stdout. */
    static List<Integer> parseSinkInputIndexesForPid(String pactlListOutput, long pid) {
        List<Integer> indexes = new ArrayList<>();
        if (pactlListOutput == null || pactlListOutput.isBlank() || pid <= 0) {
            return indexes;
        }
        Integer current = null;
        String want = Long.toString(pid);
        for (String raw : pactlListOutput.split("\r?\n")) {
            String line = raw.strip();
            Matcher sink = SINK_INPUT.matcher(line);
            if (sink.matches()) {
                current = Integer.parseInt(sink.group(1));
                continue;
            }
            if (current == null) {
                continue;
            }
            Matcher proc = PROCESS_ID.matcher(line);
            if (proc.find() && want.equals(proc.group(1))) {
                indexes.add(current);
                current = null;
            }
        }
        return indexes;
    }

    private static List<Integer> sinkInputIndexesForPid(long pid) throws Exception {
        Process p = new ProcessBuilder("pactl", "list", "sink-inputs")
                .redirectErrorStream(true)
                .start();
        String out;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            out = sb.toString();
        }
        if (!p.waitFor(3, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("pactl list sink-inputs timed out");
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException("pactl list sink-inputs exit=" + p.exitValue());
        }
        return parseSinkInputIndexesForPid(out, pid);
    }

    private static boolean setSinkInputVolume(int index, int percent) {
        try {
            Process p = new ProcessBuilder(
                    "pactl", "set-sink-input-volume",
                    String.valueOf(index),
                    String.format(Locale.ROOT, "%d%%", percent))
                    .redirectErrorStream(true)
                    .start();
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            log.debug("pactl set-sink-input-volume #{} failed: {}", index, e.toString());
            return false;
        }
    }
}
