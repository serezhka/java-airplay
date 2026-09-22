package com.github.serezhka.airplay.player.ffmpeg;

import com.github.serezhka.airplay.lib.AudioStreamInfo;
import com.github.serezhka.airplay.lib.AppLogs;
import com.github.serezhka.airplay.lib.HlsEndListDuration;
import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.server.AirPlayConsumer;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

@Slf4j
public class FFmpegPlayer implements AirPlayConsumer {

    private final int fps;
    private final FfmpegHlsPipeline hls = new FfmpegHlsPipeline();
    private Process h264Process;
    private LibavAlacDecoder alacDecoder;
    private LibavAacDecoder aacDecoder;
    private FfplayPcmSink pcmSink;
    private AudioStreamInfo.CompressionType audioCompressionType;
    private volatile double volumeLinear = 1.0;
    private volatile Double pendingStartSeekSeconds;

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
            FfplayPcmSink.forcePulseAudioEnv(pb);
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
        switch (audioCompressionType) {
            case ALAC -> startAlacProcess(audioStreamInfo);
            case AAC -> startAacLcProcess(audioStreamInfo);
            case AAC_ELD -> startAacEldProcess(audioStreamInfo);
            default -> log.warn("Unsupported audio compression {}", audioCompressionType);
        }
    }

    @Override
    public synchronized void onAudio(byte[] bytes) {
        if (pcmSink == null) {
            return;
        }
        try {
            if (alacDecoder != null) {
                alacDecoder.decode(bytes, pcmSink);
            } else if (aacDecoder != null) {
                aacDecoder.decode(bytes, pcmSink);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode/play audio", e);
        }
    }

    @Override
    public synchronized void onAudioSrcDisconnect() {
        stopAudioProcess();
        audioCompressionType = null;
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

    boolean isHlsActive() {
        return hls.isActive();
    }

    boolean isVideoProcessAlive() {
        return h264Process != null && h264Process.isAlive();
    }

    /** PID of the ffplay video process, or {@code -1} if not running. */
    public long videoProcessPid() {
        return h264Process != null && h264Process.isAlive() ? h264Process.pid() : -1L;
    }

    private void startAlacProcess(AudioStreamInfo audioStreamInfo) {
        try {
            alacDecoder = new LibavAlacDecoder(audioStreamInfo);
            pcmSink = FfplayPcmSink.start(alacDecoder.sampleRate(), alacDecoder.channels());
        } catch (Exception e) {
            closeAudioDecoders();
            throw new IllegalStateException("Failed to start libav ALAC → ffplay PCM sink", e);
        }
    }

    private void startAacLcProcess(AudioStreamInfo audioStreamInfo) {
        try {
            aacDecoder = new LibavAacDecoder(audioStreamInfo);
            pcmSink = FfplayPcmSink.start(aacDecoder.sampleRate(), aacDecoder.channels());
            log.info("AAC-LC: using libav → ffplay PCM sink");
        } catch (Exception e) {
            closeAudioDecoders();
            throw new IllegalStateException("Failed to start libav AAC-LC → ffplay PCM sink", e);
        }
    }

    private void startAacEldProcess(AudioStreamInfo audioStreamInfo) {
        try {
            aacDecoder = new LibavAacDecoder(audioStreamInfo);
            pcmSink = FfplayPcmSink.start(aacDecoder.sampleRate(), aacDecoder.channels());
            log.info("AAC-ELD: using libav → ffplay PCM sink");
        } catch (Exception e) {
            closeAudioDecoders();
            throw new IllegalStateException("Failed to start libav AAC-ELD → ffplay PCM sink", e);
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
        closeAudioDecoders();
    }

    private void closeAudioDecoders() {
        if (pcmSink != null) {
            pcmSink.close();
            pcmSink = null;
        }
        if (alacDecoder != null) {
            alacDecoder.close();
            alacDecoder = null;
        }
        if (aacDecoder != null) {
            aacDecoder.close();
            aacDecoder = null;
        }
    }
}
