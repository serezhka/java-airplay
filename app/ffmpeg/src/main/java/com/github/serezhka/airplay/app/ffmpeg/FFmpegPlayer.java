package com.github.serezhka.airplay.app.ffmpeg;

import com.github.serezhka.airplay.app.common.support.NativeProcessLog;
import com.github.serezhka.airplay.app.ffmpeg.audio.FfplayPcmSink;
import com.github.serezhka.airplay.app.ffmpeg.decode.LibavAacDecoder;
import com.github.serezhka.airplay.app.ffmpeg.decode.LibavAlacDecoder;
import com.github.serezhka.airplay.app.ffmpeg.hls.FfmpegHlsPipeline;
import com.github.serezhka.airplay.app.ffmpeg.process.FfplayProcess;
import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.protocol.media.EndListDuration;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import com.github.serezhka.airplay.server.Playback;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

@Slf4j
public class FFmpegPlayer implements Playback {

    private static final long VIDEO_FLUSH_INTERVAL_NS = TimeUnit.MILLISECONDS.toNanos(8);

    private final int fps;
    private final FfmpegHlsPipeline hls = new FfmpegHlsPipeline();
    private final Object videoLock = new Object();
    private final Object audioLock = new Object();
    private volatile Playback.Observer observer = Playback.Observer.NONE;
    private Process h264Process;
    private OutputStream h264Out;
    private long lastVideoFlushNanos;
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
        this.fps = fps;
        hls.setOnEnded(() -> observer.onEnded());
        hls.setOnPresented(() -> observer.onPresented());
        hls.setOnSeekDisplayed(() -> observer.onSeekDisplayed());
        log.info("FFmpeg debug log: {}", NativeProcessLog.playerLogFile("ffmpeg"));
    }

    @Override
    public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        synchronized (videoLock) {
            stopVideoProcessLocked();
            try {
                // Video-only: -an + dummy SDL audio (see FfplayProcess.applyDisplayEnv).
                h264Process = FfplayProcess.startH264Pipe(this.fps);
                h264Out = h264Process.getOutputStream();
                lastVideoFlushNanos = 0;
            } catch (IOException e) {
                h264Process = null;
                h264Out = null;
                throw new IllegalStateException("Failed to start ffplay. Make sure it is available on PATH.", e);
            }
        }
    }

    @Override
    public void onVideo(byte[] bytes) {
        synchronized (videoLock) {
            if (h264Process == null || !h264Process.isAlive() || h264Out == null) {
                throw new IllegalStateException("ffplay is not running");
            }
            try {
                h264Out.write(bytes);
                long now = System.nanoTime();
                if (now - lastVideoFlushNanos >= VIDEO_FLUSH_INTERVAL_NS) {
                    h264Out.flush();
                    lastVideoFlushNanos = now;
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to send video data to ffplay", e);
            }
        }
    }

    @Override
    public void onVideoSrcDisconnect() {
        synchronized (videoLock) {
            stopVideoProcessLocked();
        }
    }

    @Override
    public void onAudioFormat(AudioStreamInfo audioStreamInfo) {
        synchronized (audioLock) {
            this.audioCompressionType = audioStreamInfo.getCompressionType();
            stopAudioProcessLocked();
            switch (audioCompressionType) {
                case ALAC -> startAlacProcess(audioStreamInfo);
                case AAC -> startAacLcProcess(audioStreamInfo);
                case AAC_ELD -> startAacEldProcess(audioStreamInfo);
                default -> log.warn("Unsupported audio compression {}", audioCompressionType);
            }
        }
    }

    @Override
    public void onAudio(byte[] bytes) {
        onAudio(bytes, 0, bytes == null ? 0 : bytes.length);
    }

    @Override
    public void onAudio(byte[] bytes, int offset, int length) {
        synchronized (audioLock) {
            if (pcmSink == null || bytes == null || length <= 0) {
                return;
            }
            try {
                byte[] packet = (offset == 0 && length == bytes.length)
                        ? bytes
                        : copyRange(bytes, offset, length);
                if (alacDecoder != null) {
                    alacDecoder.decode(packet, pcmSink);
                } else if (aacDecoder != null) {
                    aacDecoder.decode(packet, pcmSink);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to decode/play audio", e);
            }
        }
    }

    @Override
    public void onAudioSrcDisconnect() {
        synchronized (audioLock) {
            stopAudioProcessLocked();
            audioCompressionType = null;
        }
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
        synchronized (audioLock) {
            if (pcmSink != null) {
                pcmSink.setVolume(this.volumeLinear);
            }
        }
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
        synchronized (videoLock) {
            return h264Process != null && h264Process.isAlive();
        }
    }

    boolean isAudioSinkAlive() {
        synchronized (audioLock) {
            return pcmSink != null && pcmSink.isAlive();
        }
    }

    /** PID of the ffplay video process, or {@code -1} if not running. */
    public long videoProcessPid() {
        synchronized (videoLock) {
            return h264Process != null && h264Process.isAlive() ? h264Process.pid() : -1L;
        }
    }

    private void startAlacProcess(AudioStreamInfo audioStreamInfo) {
        try {
            alacDecoder = new LibavAlacDecoder(audioStreamInfo);
            pcmSink = FfplayPcmSink.start(alacDecoder.sampleRate(), alacDecoder.channels());
            pcmSink.setVolume(volumeLinear);
        } catch (Exception e) {
            closeAudioDecoders();
            throw new IllegalStateException("Failed to start libav ALAC → ffplay PCM sink", e);
        }
    }

    private void startAacLcProcess(AudioStreamInfo audioStreamInfo) {
        try {
            aacDecoder = new LibavAacDecoder(audioStreamInfo);
            pcmSink = FfplayPcmSink.start(aacDecoder.sampleRate(), aacDecoder.channels());
            pcmSink.setVolume(volumeLinear);
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
            pcmSink.setVolume(volumeLinear);
            log.info("AAC-ELD: using libav → ffplay PCM sink");
        } catch (Exception e) {
            closeAudioDecoders();
            throw new IllegalStateException("Failed to start libav AAC-ELD → ffplay PCM sink", e);
        }
    }

    private void stopVideoProcessLocked() {
        if (h264Process == null) {
            return;
        }
        try {
            if (h264Out != null) {
                h264Out.flush();
                h264Out.close();
            }
        } catch (IOException e) {
            log.debug("Failed to close ffplay input", e);
        }
        FfplayProcess.destroy(h264Process);
        h264Process = null;
        h264Out = null;
    }

    private void stopAudioProcessLocked() {
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

    private static byte[] copyRange(byte[] bytes, int offset, int length) {
        byte[] copy = new byte[length];
        System.arraycopy(bytes, offset, copy, 0, length);
        return copy;
    }
}
