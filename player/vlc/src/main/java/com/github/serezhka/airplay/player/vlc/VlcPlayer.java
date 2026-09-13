package com.github.serezhka.airplay.player.vlc;

import com.formdev.flatlaf.FlatDarkLaf;
import com.github.serezhka.airplay.lib.AudioStreamInfo;
import com.github.serezhka.airplay.lib.AppLogs;
import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.server.AirPlayConsumer;
import lombok.extern.slf4j.Slf4j;
import org.spf4j.io.PipedOutputStream;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.log.LogLevel;
import uk.co.caprica.vlcj.log.NativeLog;
import uk.co.caprica.vlcj.media.callback.nonseekable.NonSeekableInputStreamMedia;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent;

import javax.swing.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class VlcPlayer implements AirPlayConsumer {

    static {
        // FlatLaf touches Swing; skip in headless CI to avoid EDT / xvfb stalls on startup.
        if (!headless()) {
            FlatDarkLaf.setup();
        }
    }

    private final boolean headless;
    private final MediaPlayerFactory mediaPlayerFactory;
    private final NativeLog nativeLog;
    private final PrintStream vlcLog;
    private final PipedOutputStream output;
    private final InputStream input;
    private final AtomicBoolean closed = new AtomicBoolean();

    private EmbeddedMediaPlayerComponent mediaPlayerComponent;
    private MediaPlayer headlessPlayer;
    private JFrame window;

    public VlcPlayer() {
        this.headless = headless();
        log.info("VLC debug log: {} (headless={})", AppLogs.playerLogFile("vlc"), headless);
        try {
            vlcLog = AppLogs.openAppendPrintStream("vlc");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to open VLC debug log", e);
        }

        mediaPlayerFactory = new MediaPlayerFactory(factoryArgs(headless).toArray(String[]::new));

        nativeLog = mediaPlayerFactory.application().newLog();
        nativeLog.setLevel(LogLevel.DEBUG);
        nativeLog.addLogListener((level, module, file, line, name, header, id, message) -> {
            vlcLog.printf("[%s] [%s] %s %s%n", level, module, name, message);
            log.debug("[VLCJ] [{}] [{}] {} {}", level, module, name, message);
        });

        output = new PipedOutputStream();
        input = output.getInputStream();

        NonSeekableInputStreamMedia media = new NonSeekableInputStreamMedia() {
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

        if (headless) {
            headlessPlayer = mediaPlayerFactory.mediaPlayers().newMediaPlayer();
            headlessPlayer.media().play(media);
            headlessPlayer.controls().play();
        } else {
            mediaPlayerComponent = new EmbeddedMediaPlayerComponent(mediaPlayerFactory, null, null, null, null);
            window = new JFrame("AirPlay player");
            window.setSize(800, 600);
            window.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            window.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    onVideoSrcDisconnect();
                }
            });
            window.setContentPane(mediaPlayerComponent);
            window.setVisible(true);
            mediaPlayerComponent.mediaPlayer().media().play(media);
            mediaPlayerComponent.mediaPlayer().controls().play();
        }
    }

    static boolean headless() {
        if (Boolean.parseBoolean(System.getProperty("airplay.vlc.headless", "false"))) {
            return true;
        }
        if (Boolean.parseBoolean(System.getenv().getOrDefault("AIRPLAY_VLC_HEADLESS", "false"))) {
            return true;
        }
        // GitHub Actions / generic CI
        if (System.getenv("CI") != null || System.getenv("GITHUB_ACTIONS") != null) {
            return true;
        }
        return Boolean.getBoolean("java.awt.headless");
    }

    private static List<String> factoryArgs(boolean headless) {
        List<String> args = new ArrayList<>();
        args.add("-vv");
        args.add("--demux=h264");
        if (headless) {
            args.add("--intf=dummy");
            args.add("--vout=dummy");
            args.add("--aout=dummy");
            args.add("--no-video-title-show");
            args.add("--no-stats");
            args.add("--no-osd");
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("linux")) {
            // Avoid plugin probe stalls on minimal CI images.
            args.add("--no-xlib");
        }
        return args;
    }

    @Override
    public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
    }

    @Override
    public void onVideo(byte[] bytes) {
        if (closed.get() || bytes == null || bytes.length == 0) {
            return;
        }
        try {
            output.write(bytes);
            output.flush();
        } catch (IOException e) {
            vlcLog.printf("Failed to write video bytes: %s%n", e.getMessage());
            log.debug("Failed to write video bytes to VLC", e);
        }
    }

    @Override
    public void onVideoSrcDisconnect() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            if (headlessPlayer != null) {
                headlessPlayer.controls().stop();
                headlessPlayer.release();
            }
        } catch (Exception ignored) {
            // shutting down
        }
        try {
            if (mediaPlayerComponent != null) {
                mediaPlayerComponent.mediaPlayer().controls().stop();
                mediaPlayerComponent.release();
            }
        } catch (Exception ignored) {
            // shutting down
        }
        try {
            nativeLog.release();
        } catch (Exception ignored) {
            // shutting down
        }
        try {
            mediaPlayerFactory.release();
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
        try {
            output.close();
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
}
