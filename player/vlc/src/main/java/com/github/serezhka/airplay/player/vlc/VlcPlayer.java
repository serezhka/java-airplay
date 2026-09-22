package com.github.serezhka.airplay.player.vlc;

import com.formdev.flatlaf.FlatDarkLaf;
import com.github.serezhka.airplay.lib.AudioStreamInfo;
import com.github.serezhka.airplay.lib.AppLogs;
import com.github.serezhka.airplay.lib.HlsEndListDuration;
import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.server.AirPlayConsumer;
import lombok.extern.slf4j.Slf4j;
import org.spf4j.io.PipedOutputStream;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.log.LogLevel;
import uk.co.caprica.vlcj.log.NativeLog;
import uk.co.caprica.vlcj.media.callback.nonseekable.NonSeekableInputStreamMedia;
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent;

import javax.swing.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * VLC-backed AirPlay consumer.
 * <p>
 * Mirror uses vlcj embedded UI (lazy-init — native factory can hang on Linux), or
 * {@code cvlc}/{@code vlc} stdin when headless. HLS uses a separate {@link VlcHlsPipeline}.
 */
@Slf4j
public class VlcPlayer implements AirPlayConsumer {

    static {
        if (!headless()) {
            FlatDarkLaf.setup();
        }
    }

    private final boolean headless;
    private final VlcHlsPipeline hls = new VlcHlsPipeline();
    private final PrintStream vlcLog;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile double volumeLinear = 1.0;
    private volatile Double pendingStartSeekSeconds;

    private MediaPlayerFactory mediaPlayerFactory;
    private NativeLog nativeLog;
    private EmbeddedMediaPlayerComponent mediaPlayerComponent;
    private JFrame window;
    private PipedOutputStream output;
    private InputStream input;
    private NonSeekableInputStreamMedia media;

    private Process cliProcess;
    private OutputStream cliStdin;

    public VlcPlayer() {
        this.headless = headless();
        log.info("VLC debug log: {} (headless={})", AppLogs.playerLogFile("vlc"), headless);
        try {
            vlcLog = AppLogs.openAppendPrintStream("vlc");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to open VLC debug log", e);
        }
    }

    private void initEmbedded() {
        mediaPlayerFactory = new MediaPlayerFactory("-vv", "--demux=h264");
        nativeLog = mediaPlayerFactory.application().newLog();
        nativeLog.setLevel(LogLevel.WARNING);
        nativeLog.addLogListener((level, module, file, line, name, header, id, message) -> {
            vlcLog.printf("[%s] [%s] %s %s%n", level, module, name, message);
            log.debug("[VLCJ] [{}] [{}] {} {}", level, module, name, message);
        });

        openMirrorPipe();
        mediaPlayerComponent = new EmbeddedMediaPlayerComponent(mediaPlayerFactory, null, null, null, null);
        window = new JFrame("AirPlay player");
        window.setSize(800, 600);
        window.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        window.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                releaseAll();
            }
        });
        window.setContentPane(mediaPlayerComponent);
        window.setVisible(true);
    }

    private void openMirrorPipe() {
        output = new PipedOutputStream();
        input = output.getInputStream();
        media = new NonSeekableInputStreamMedia() {
            @Override
            protected long onGetSize() {
                return 0;
            }

            @Override
            protected InputStream onOpenStream() {
                return input;
            }

            @Override
            protected void onCloseStream(InputStream inputStream) throws IOException {
                inputStream.close();
            }
        };
    }

    static boolean headless() {
        if (Boolean.parseBoolean(System.getProperty("airplay.vlc.headless", "false"))) {
            return true;
        }
        if (Boolean.parseBoolean(System.getenv().getOrDefault("AIRPLAY_VLC_HEADLESS", "false"))) {
            return true;
        }
        return System.getenv("CI") != null
                || System.getenv("GITHUB_ACTIONS") != null
                || Boolean.getBoolean("java.awt.headless");
    }

    private static String resolveCliBinary() {
        for (String candidate : List.of("cvlc", "vlc")) {
            try {
                Process p = new ProcessBuilder(candidate, "--version").redirectErrorStream(true).start();
                if (p.waitFor(3, TimeUnit.SECONDS)) {
                    return candidate;
                }
            } catch (Exception ignored) {
                // try next
            }
        }
        throw new IllegalStateException("Neither cvlc nor vlc found on PATH");
    }

    @Override
    public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        if (headless) {
            startCli();
        } else {
            if (mediaPlayerComponent == null) {
                initEmbedded();
            }
            mediaPlayerComponent.mediaPlayer().media().play(media);
            mediaPlayerComponent.mediaPlayer().controls().play();
        }
    }

    private void startCli() {
        String binary = resolveCliBinary();
        List<String> cmd = new ArrayList<>();
        cmd.add(binary);
        cmd.add("--intf");
        cmd.add("dummy");
        cmd.add("--vout");
        cmd.add("dummy");
        cmd.add("--aout");
        cmd.add("dummy");
        cmd.add("--demux");
        cmd.add("h264");
        cmd.add("--play-and-exit");
        cmd.add("--no-video-title-show");
        cmd.add("-");
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            AppLogs.configureProcessLogging(pb, "vlc");
            cliProcess = pb.start();
            cliStdin = cliProcess.getOutputStream();
            log.info("Started headless {} for H264 stdin", binary);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start " + binary, e);
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
            if (headless) {
                cliStdin.write(bytes);
                cliStdin.flush();
            } else {
                output.write(bytes);
                output.flush();
            }
        } catch (IOException e) {
            vlcLog.printf("Failed to write video bytes: %s%n", e.getMessage());
            log.debug("Failed to write video bytes to VLC", e);
        }
    }

    @Override
    public void onVideoSrcDisconnect() {
        stopMirror();
    }

    private void stopMirror() {
        started.set(false);
        if (headless) {
            try {
                if (cliStdin != null) {
                    cliStdin.close();
                }
            } catch (IOException ignored) {
                // shutting down
            }
            if (cliProcess != null) {
                cliProcess.destroy();
                try {
                    cliProcess.waitFor(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                cliProcess.destroyForcibly();
                cliProcess = null;
                cliStdin = null;
            }
            return;
        }

        try {
            if (mediaPlayerComponent != null) {
                mediaPlayerComponent.mediaPlayer().controls().stop();
            }
        } catch (Exception ignored) {
            // shutting down
        }
        try {
            if (output != null) {
                output.close();
            }
        } catch (Exception ignored) {
            // shutting down
        }
        if (!closed.get() && mediaPlayerFactory != null) {
            try {
                openMirrorPipe();
            } catch (Exception e) {
                log.debug("Failed to reset VLC mirror pipe: {}", e.toString());
            }
        }
    }

    private void releaseAll() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        hls.stop();
        stopMirror();
        if (headless) {
            vlcLog.close();
            return;
        }
        try {
            if (mediaPlayerComponent != null) {
                mediaPlayerComponent.release();
            }
        } catch (Exception ignored) {
            // shutting down
        }
        try {
            if (nativeLog != null) {
                nativeLog.release();
            }
        } catch (Exception ignored) {
            // shutting down
        }
        try {
            if (mediaPlayerFactory != null) {
                mediaPlayerFactory.release();
            }
        } catch (Exception ignored) {
            // shutting down
        }
        try {
            if (window != null) {
                window.dispose();
            }
        } catch (Exception ignored) {
            // shutting down
        }
        vlcLog.close();
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

    @Override
    public void onMediaPlaylist(String playlistUri) {
        Double seek = pendingStartSeekSeconds;
        pendingStartSeekSeconds = null;
        hls.start(playlistUri, volumeLinear, seek != null ? seek : 0);
    }

    @Override
    public void onMediaPlaylistRemove() {
        pendingStartSeekSeconds = null;
        hls.stop();
    }

    @Override
    public void onMediaPlaylistContent(String playlistUri, String content) {
        if (playlistUri == null || !playlistUri.contains("mediadata.m3u8") || content == null) {
            return;
        }
        double sum = HlsEndListDuration.sumSeconds(content);
        if (sum > 0) {
            hls.noteMediaDuration(sum);
        }
    }

    @Override
    public void onMediaPlaylistPause() {
        hls.pause();
    }

    @Override
    public void onMediaPlaylistResume() {
        hls.resume();
    }

    @Override
    public void onMediaPlaylistSeek(double positionSeconds) {
        if (!hls.isActive()) {
            pendingStartSeekSeconds = positionSeconds;
            return;
        }
        hls.seek(positionSeconds);
    }

    @Override
    public void onVolume(double volumeLinear) {
        this.volumeLinear = Math.max(0.0, Math.min(1.0, volumeLinear));
        hls.setVolume(this.volumeLinear);
        log.info("Volume set to {}", this.volumeLinear);
    }

    @Override
    public double volume() {
        return volumeLinear;
    }

    @Override
    public PlaybackInfo playbackInfo() {
        if (!hls.isActive()) {
            return AirPlayConsumer.super.playbackInfo();
        }
        double duration = hls.durationSeconds();
        double position = hls.currentPositionSeconds();
        if (duration > 0) {
            position = Math.min(position, duration);
        }
        // VOD EOS is paused locally; report rate=1 (rate=0 looks like user pause).
        double rate = (hls.isPaused() && !hls.isEnded()) ? 0 : 1;
        return new PlaybackInfo(duration, position, rate);
    }

    /** Exposed for harness assertions. */
    public boolean isCliAlive() {
        return cliProcess != null && cliProcess.isAlive();
    }

    boolean isHlsActive() {
        return hls.isActive();
    }
}
