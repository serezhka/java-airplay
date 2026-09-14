package com.github.serezhka.airplay.player.harness;

import com.github.serezhka.airplay.lib.AppLogs;
import com.github.serezhka.airplay.lib.AudioStreamInfo;
import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.server.AirPlayConsumer;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Headless GStreamer consumer for CI/bench: pipes Annex-B H264 into {@code gst-launch-1.0}.
 * Avoids gst1-java / JNI which SIGSEGV under long xvfb runs on GitHub Actions.
 */
public final class GstLaunchPlayer implements AirPlayConsumer {

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
            ProcessBuilder pb = new ProcessBuilder(
                    "gst-launch-1.0", "-q",
                    "fdsrc", "fd=0", "do-timestamp=true",
                    "!", "h264parse",
                    "!", "avdec_h264",
                    "!", "fakesink", "sync=false"
            );
            AppLogs.configureProcessLogging(pb, "gstreamer");
            process = pb.start();
            stdin = process.getOutputStream();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start gst-launch-1.0. Is GStreamer on PATH?", e);
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
            throw new IllegalStateException("Failed to write H264 to gst-launch-1.0", e);
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
}
