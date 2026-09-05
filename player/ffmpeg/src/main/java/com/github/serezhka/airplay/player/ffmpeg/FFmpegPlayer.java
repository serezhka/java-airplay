package com.github.serezhka.airplay.player.ffmpeg;

import com.github.serezhka.airplay.lib.AudioStreamInfo;
import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.server.AirPlayConsumer;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

@Slf4j
public class FFmpegPlayer implements AirPlayConsumer {

    private static final String FFPLAY = "ffplay";

    private Process h264Process;

    @Override
    public synchronized void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        stopVideoProcess();
        try {
            ProcessBuilder pb = new ProcessBuilder(FFPLAY, "-fs", "-f", "h264", "-codec:v", "h264", "-probesize", "32",
                    "-analyzeduration", "0", "-vf", "setpts=0", "-flags", "low_delay", "-");
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            h264Process = pb.start();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start ffplay. Make sure it is available on PATH.", e);
        }
    }

    @Override
    public synchronized void onVideo(byte[] bytes) {
        if (h264Process == null || !h264Process.isAlive()) {
            throw new IllegalStateException("ffplay is not running");
        }
        try {
            h264Process.getOutputStream().write(bytes);
            h264Process.getOutputStream().flush();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to send video data to ffplay", e);
        }
    }

    @Override
    public synchronized void onVideoSrcDisconnect() {
        stopVideoProcess();
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

    boolean isVideoProcessAlive() {
        return h264Process != null && h264Process.isAlive();
    }

    private void stopVideoProcess() {
        if (h264Process == null) {
            return;
        }
        try {
            h264Process.getOutputStream().close();
        } catch (IOException e) {
            log.debug("Failed to close ffplay input", e);
        }
        h264Process.destroy();
        h264Process = null;
    }
}
