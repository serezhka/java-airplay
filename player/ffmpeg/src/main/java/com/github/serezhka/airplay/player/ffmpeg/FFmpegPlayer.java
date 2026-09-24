package com.github.serezhka.airplay.player.ffmpeg;

import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.player.support.NativeProcessLog;
import com.github.serezhka.airplay.protocol.media.EndListDuration;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import com.github.serezhka.airplay.server.Playback;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

@Slf4j
public class FFmpegPlayer implements Playback {

    private final int fps;
    private final FfmpegHlsPipeline hls = new FfmpegHlsPipeline();
    private volatile Playback.Observer observer = Playback.Observer.NONE;
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
        hls.setOnEnded(() -> observer.onEnded());
        log.info("FFmpeg debug log: {}", NativeProcessLog.playerLogFile("ffmpeg"));
    }

    @Override
    public synchronized void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        stopVideoProcess();
        try {
            // Video-only: -an + dummy SDL audio. Do not force Pulse here — CI/xvfb has no
            // Pulse server, and SDL_AUDIODRIVER=pulse makes ffplay exit before the first NAL.
            ProcessBuilder pb = new ProcessBuilder("ffplay", "-fs", "-an", "-loglevel", "debug",
                    "-f", "h264",
                    "-framerate", String.valueOf(this.fps),
                    "-codec:v", "h264", "-probesize", "32",
                    "-analyzeduration", "0", "-flags", "low_delay", "-");
            FfplayPcmSink.applyDisplayEnv(pb);
            NativeProcessLog.configureProcessLogging(pb, "ffmpeg");
            h264Process = pb.start();
            if (!h264Process.isAlive()) {
                h264Process = null;
                throw new IllegalStateException("ffplay exited immediately after start");
            }
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
    public void setObserver(Playback.Observer observer) {
        this.observer = observer == null ? Playback.Observer.NONE : observer;
    }

    @Override
    public void onPlaylist(String playlistUri) {
        Double seek = pendingStartSeekSeconds;
        pendingStartSeekSeconds = null;
        hls.start(playlistUri, volumeLinear, seek != null ? seek : 0);
    }

    @Override
    public void onPlaylistRemoved() {
        pendingStartSeekSeconds = null;
        hls.stop();
    }

    @Override
    public void onPlaylistContent(String playlistUri, String content) {
        if (playlistUri == null || !playlistUri.contains("mediadata.m3u8") || content == null) {
            return;
        }
        double sum = EndListDuration.sumSeconds(content);
        if (sum > 0) {
            hls.noteMediaDuration(sum);
        }
    }

    @Override
    public void onPause() {
        hls.pause();
    }

    @Override
    public void onResume() {
        hls.resume();
    }

    @Override
    public void onSeek(double positionSeconds) {
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
    public Playback.Info info() {
        if (!hls.isActive()) {
            return Playback.super.info();
        }
        double duration = hls.durationSeconds();
        double position = hls.currentPositionSeconds();
        if (duration > 0) {
            position = Math.min(position, duration);
        }
        // VOD EOS is paused locally; report rate=1 (rate=0 looks like user pause).
        double rate = (hls.isPaused() && !hls.isEnded()) ? 0 : 1;
        return new Playback.Info(duration, position, rate);
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
