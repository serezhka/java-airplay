package com.github.serezhka.airplay.player.vlc;

import com.formdev.flatlaf.FlatDarkLaf;
import com.github.serezhka.airplay.lib.AudioStreamInfo;
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

@Slf4j
public class VlcPlayer implements AirPlayConsumer {

    static {
        FlatDarkLaf.setup();
    }

    private final EmbeddedMediaPlayerComponent mediaPlayerComponent;
    private final MediaPlayerFactory mediaPlayerFactory;
    private final NativeLog nativeLog;

    private final JFrame window;

    private final PipedOutputStream output;
    private final InputStream input;

    public VlcPlayer() {
        mediaPlayerFactory = new MediaPlayerFactory("-vv", "--demux=h264");

        nativeLog = mediaPlayerFactory.application().newLog();
        nativeLog.setLevel(LogLevel.DEBUG);
        nativeLog.addLogListener((level, module, file, line, name, header, id, message) ->
                log.debug("[VLCJ] [{}] [{}] {} {}", level, module, name, message));

        mediaPlayerComponent = new EmbeddedMediaPlayerComponent(mediaPlayerFactory, null, null, null, null);

        window = new JFrame("AirPlay player");
        window.setSize(800, 600);
        window.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        window.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                mediaPlayerComponent.release();
            }
        });
        window.setContentPane(mediaPlayerComponent);
        window.setVisible(true);

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

        mediaPlayerComponent.mediaPlayer().media().play(media);
        mediaPlayerComponent.mediaPlayer().controls().play();
    }

    @Override
    public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
    }

    @Override
    public void onVideo(byte[] bytes) {
        try {
            output.write(bytes);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onVideoSrcDisconnect() {
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
