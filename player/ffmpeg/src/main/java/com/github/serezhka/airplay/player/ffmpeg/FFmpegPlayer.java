package com.github.serezhka.airplay.player.ffmpeg;

import com.github.serezhka.airplay.lib.AudioStreamInfo;
import com.github.serezhka.airplay.lib.AppLogs;
import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.server.AirPlayConsumer;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

@Slf4j
public class FFmpegPlayer implements AirPlayConsumer {

    private final int fps;
    private Process h264Process;
    private Process alacProcess;
    private Process hlsProcess;
    private AudioStreamInfo.CompressionType audioCompressionType;

    public FFmpegPlayer() {
        this(60);
    }

    public FFmpegPlayer(int fps) {
        this.fps = Math.max(1, fps);
        log.info("FFmpeg debug log: {}", AppLogs.playerLogFile("ffmpeg"));
    }

    @Override
    public synchronized void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        stopVideoProcess();
        try {
            ProcessBuilder pb = new ProcessBuilder("ffplay", "-fs", "-loglevel", "debug",
                    "-f", "h264",
                    "-framerate", String.valueOf(this.fps),
                    "-codec:v", "h264", "-probesize", "32",
                    "-analyzeduration", "0", "-flags", "low_delay", "-");
            AppLogs.configureProcessLogging(pb, "ffmpeg");
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
    public synchronized void onAudioFormat(AudioStreamInfo audioStreamInfo) {
        this.audioCompressionType = audioStreamInfo.getCompressionType();
        stopAudioProcess();
        if (audioCompressionType == AudioStreamInfo.CompressionType.ALAC) {
            startAlacProcess();
        } else if (audioCompressionType == AudioStreamInfo.CompressionType.AAC_ELD) {
            if (!hasLibFdkAacDecoder()) {
                log.warn("AAC-ELD mirroring audio requires ffmpeg with libfdk_aac decoder; audio will be skipped");
                return;
            }
            startAacEldProcess();
        } else {
            log.warn("Unsupported audio compression {}", audioCompressionType);
        }
    }

    @Override
    public synchronized void onAudio(byte[] bytes) {
        Process audioProcess = alacProcess;
        if (audioProcess == null || !audioProcess.isAlive()) {
            return;
        }
        try {
            audioProcess.getOutputStream().write(bytes);
            audioProcess.getOutputStream().flush();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to send audio data to ffplay", e);
        }
    }

    @Override
    public synchronized void onAudioSrcDisconnect() {
        stopAudioProcess();
        audioCompressionType = null;
    }

    @Override
    public synchronized void onMediaPlaylist(String playlistUri) {
        stopHlsProcess();
        try {
            ProcessBuilder pb = new ProcessBuilder("ffplay", "-fs", "-loglevel", "debug", playlistUri);
            AppLogs.configureProcessLogging(pb, "ffmpeg");
            hlsProcess = pb.start();
            log.info("Started ffplay for HLS playlist {}", playlistUri);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start ffplay for HLS playlist " + playlistUri, e);
        }
    }

    @Override
    public synchronized void onMediaPlaylistRemove() {
        stopHlsProcess();
    }

    @Override
    public synchronized void onMediaPlaylistPause() {
        log.debug("ffplay HLS pause is not implemented");
    }

    @Override
    public synchronized void onMediaPlaylistResume() {
        log.debug("ffplay HLS resume is not implemented");
    }

    boolean isVideoProcessAlive() {
        return h264Process != null && h264Process.isAlive();
    }

    boolean isHlsProcessAlive() {
        return hlsProcess != null && hlsProcess.isAlive();
    }

    private void startAlacProcess() {
        try {
            ProcessBuilder pb = new ProcessBuilder("ffplay", "-nodisp", "-loglevel", "debug",
                    "-f", "alac", "-ar", "44100", "-ac", "2", "-i", "pipe:0");
            AppLogs.configureProcessLogging(pb, "ffmpeg");
            alacProcess = pb.start();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start ffplay for ALAC audio", e);
        }
    }

    private void startAacEldProcess() {
        try {
            ProcessBuilder pb = new ProcessBuilder("ffplay", "-nodisp", "-loglevel", "debug",
                    "-f", "aac", "-acodec", "libfdk_aac", "-i", "pipe:0");
            AppLogs.configureProcessLogging(pb, "ffmpeg");
            alacProcess = pb.start();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start ffplay for AAC-ELD audio", e);
        }
    }

    private static boolean hasLibFdkAacDecoder() {
        try {
            Process process = new ProcessBuilder("ffmpeg", "-hide_banner", "-decoders")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes());
            process.waitFor();
            return output.contains("libfdk_aac");
        } catch (Exception e) {
            log.debug("Unable to inspect ffmpeg decoders", e);
            return false;
        }
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

    private void stopAudioProcess() {
        if (alacProcess == null) {
            return;
        }
        try {
            alacProcess.getOutputStream().close();
        } catch (IOException e) {
            log.debug("Failed to close ffplay audio input", e);
        }
        alacProcess.destroy();
        alacProcess = null;
    }

    private void stopHlsProcess() {
        if (hlsProcess == null) {
            return;
        }
        hlsProcess.destroy();
        hlsProcess = null;
    }
}
