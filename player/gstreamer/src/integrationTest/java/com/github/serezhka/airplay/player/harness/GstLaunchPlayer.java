package com.github.serezhka.airplay.player.harness;

import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import com.github.serezhka.airplay.server.Playback;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Headless GStreamer consumer for CI: pipes Annex-B H264 into {@code gst-launch-1.0}.
 * Parses only (no decoder) so runners need just tools + base plugins — avoids JNI SIGSEGV
 * and missing avdec_* plugin issues on minimal images.
 */
public final class GstLaunchPlayer implements Playback {

    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private Process process;
    private OutputStream stdin;

    @Override
    public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        try {
            // Parse-only pipeline: stable across plugin sets; measures write/backpressure to gst.
            ProcessBuilder pb = new ProcessBuilder(
                    "gst-launch-1.0",
                    "fdsrc", "fd=0",
                    "!", "h264parse",
                    "!", "fakesink", "sync=false"
            );
            pb.redirectErrorStream(true);
            process = pb.start();
            stdin = process.getOutputStream();
            // Fail fast if gst-launch rejected the pipeline.
            Thread.sleep(300);
            if (!process.isAlive()) {
                String err = readAvailable(process.getInputStream());
                throw new IllegalStateException("gst-launch-1.0 exited immediately: " + err.trim());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start gst-launch-1.0. Is GStreamer on PATH?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting gst-launch-1.0", e);
        }
    }

    @Override
    public void onVideo(byte[] bytes) {
        if (closed.get() || bytes == null || bytes.length == 0) {
            return;
        }
        if (!started.get()) {
            onVideoFormat(null);
        }
        try {
            stdin.write(bytes);
            stdin.flush();
        } catch (IOException e) {
            String err = process != null ? readAvailable(process.getErrorStream()) : "";
            throw new IllegalStateException("Failed to write H264 to gst-launch-1.0: " + err.trim(), e);
        }
    }

    @Override
    public void onVideoSrcDisconnect() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            if (stdin != null) {
                stdin.close();
            }
        } catch (IOException ignored) {
            // shutting down
        }
        if (process != null) {
            process.destroy();
            try {
                process.waitFor(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            process.destroyForcibly();
        }
    }

    @Override
    public void onAudioFormat(AudioStreamInfo audioStreamInfo) {
    }

    @Override
    public void onAudio(byte[] bytes) {
    }

    @Override
    public void onAudioSrcDisconnect() {
    }

    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    public long pid() {
        return process != null ? process.pid() : -1L;
    }

    private static String readAvailable(InputStream in) {
        if (in == null) {
            return "";
        }
        try {
            byte[] buf = in.readNBytes(4096);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
