package com.github.serezhka.airplay.app.gstreamer;

import com.github.serezhka.airplay.app.common.hls.HlsPlaybackMath;
import com.github.serezhka.airplay.app.common.support.NativeProcessLog;
import com.github.serezhka.airplay.app.gstreamer.display.GstFullscreenWindow;
import com.github.serezhka.airplay.app.gstreamer.display.GstVideoSinkFactory;
import com.github.serezhka.airplay.app.gstreamer.hls.GstHlsPipeline;
import com.github.serezhka.airplay.app.gstreamer.util.GstPluginFeatureProbe;
import com.github.serezhka.airplay.app.gstreamer.util.GstPlayerUtils;
import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.protocol.media.EndListDuration;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import com.github.serezhka.airplay.server.Playback;
import com.sun.jna.Native;
import lombok.extern.slf4j.Slf4j;
import org.freedesktop.gstreamer.Buffer;
import org.freedesktop.gstreamer.BusSyncReply;
import org.freedesktop.gstreamer.Caps;
import org.freedesktop.gstreamer.Element;
import org.freedesktop.gstreamer.ElementFactory;
import org.freedesktop.gstreamer.Format;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;
import org.freedesktop.gstreamer.Version;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.elements.AppSrc;
import org.freedesktop.gstreamer.glib.GLib;
import org.freedesktop.gstreamer.interfaces.VideoOverlay;
import org.freedesktop.gstreamer.swing.GstVideoComponent;

import javax.swing.JFrame;
import java.awt.Canvas;
import java.awt.Color;

@Slf4j
public class GstPlayer implements Playback {

    static {
        GstPlayerUtils.configurePaths();
        GLib.setEnv("GST_DEBUG_NO_COLOR", "1", true);
        GLib.setEnv("GST_DEBUG_FILE", NativeProcessLog.playerLogFile("gstreamer").toString(), true);
        GLib.setEnv("GST_DEBUG", System.getProperty("airplay.gst.debug", "3"), true);
        Gst.init(Version.of(1, 10), "java-airplay");
        // Ranks after init: probe curl/NV instead of blind demote (Windows curl hang, CUDA).
        // Do not demote hlsdemux2 — playbin3 needs the streams-aware demuxer.
        GstPluginFeatureProbe.tuneAfterInit();
    }

    private final Pipeline h264Pipeline;
    private final Pipeline alacPipeline;
    private final Pipeline aacEldPipeline;
    private final Pipeline aacLcPipeline;
    private final JFrame window;
    private final Runnable attachWindow;
    private final boolean nativeFullscreen;
    private final boolean useD3d11;

    private final AppSrc h264Src;
    private final AppSrc alacSrc;
    private final AppSrc aacEldSrc;
    private final AppSrc aacLcSrc;

    private final GstHlsPipeline hls = new GstHlsPipeline();
    private volatile Playback.Observer observer = Playback.Observer.NONE;
    private volatile double volumeLinear = 1.0;

    private AudioStreamInfo.CompressionType audioCompressionType;

    public GstPlayer() {
        this(60);
    }

    public GstPlayer(int fps) {
        log.info("GStreamer debug log: {}", NativeProcessLog.playerLogFile("gstreamer"));
        hls.setOnEnded(() -> observer.onEnded());
        hls.setOnPresented(() -> observer.onPresented());
        hls.setOnSeekDisplayed(() -> observer.onSeekDisplayed());
        useD3d11 = GstVideoSinkFactory.hasD3d11();
        boolean useXimage = GstVideoSinkFactory.hasXimage();
        boolean forceAppsink = Boolean.parseBoolean(System.getProperty("airplay.gst.appsink", "false"))
                || Boolean.parseBoolean(System.getenv().getOrDefault("AIRPLAY_GST_APPSINK", "false"));
        nativeFullscreen = useD3d11 && !forceAppsink;
        String sinkLaunch;
        if (forceAppsink) {
            sinkLaunch = " ! fakesink name=sink sync=false async=false";
        } else if (useD3d11) {
            sinkLaunch = " ! d3d11upload ! d3d11convert"
                    + " ! d3d11videosink name=sink sync=false force-aspect-ratio=true";
        } else if (useXimage) {
            sinkLaunch = " ! videoscale add-borders=true ! video/x-raw,format=BGRx"
                    + " ! ximagesink name=sink sync=false force-aspect-ratio=true";
        } else {
            sinkLaunch = " ! appsink name=sink sync=false";
        }
        // Headless CI (appsink/fakesink video) has no usable audio device either.
        // Music RTP arrives in WiFi bursts; a tiny leaky queue + live prebuf=0 made
        // pulsesink underflow then play the next burst too fast ("holes + catch-up").
        // Keep ~2 s of encoded audio (AirPlay latency budget), never drop, and give
        // Pulse a real buffer-time so jitter does not underrun.
        String audioSink;
        if (forceAppsink) {
            audioSink = "fakesink sync=false async=false";
        } else if (pulseSinkAvailable()) {
            // Explicit pulsesink so we can set buffer-time (autoaudiosink hides it).
            audioSink = "pulsesink sync=false buffer-time=200000 latency-time=20000";
        } else {
            audioSink = "autoaudiosink sync=false";
        }
        log.info("GStreamer video sink: {}; audio sink: {}",
                forceAppsink ? "fakesink(forced)" : useD3d11 ? "d3d11videosink" : useXimage ? "ximagesink" : "appsink",
                forceAppsink ? "fakesink(forced)" : audioSink.startsWith("pulsesink") ? "pulsesink" : "autoaudiosink");
        // Never leaky before avdec — mid-GOP drops freeze / ghost the picture.
        // Cap display latency after decode; videoflip kept for orientation.
        h264Pipeline = (Pipeline) Gst.parseLaunch(
                "appsrc name=h264-src ! h264parse config-interval=-1 ! avdec_h264"
                        + " ! videoflip video-direction=auto ! videoconvert"
                        + " ! queue max-size-buffers=2 max-size-time=0 max-size-bytes=0"
                        + " leaky=downstream"
                        + sinkLaunch);

        h264Src = (AppSrc) h264Pipeline.getElementByName("h264-src");
        h264Src.setStreamType(AppSrc.StreamType.STREAM);
        h264Src.setCaps(Caps.fromString(
                "video/x-h264,colorimetry=bt709,stream-format=(string)byte-stream,alignment=(string)nal,framerate="
                        + fps + "/1"));
        h264Src.set("is-live", true);
        h264Src.set("format", Format.TIME);
        h264Src.set("do-timestamp", true);
        h264Src.set("emit-signals", true);
        h264Src.set("block", false);
        h264Src.set("max-bytes", 2L * 1024 * 1024);

        // Music ALAC: ~2 s non-leaky queue (AirPlay latency budget) — WiFi bursts must not drop AUs.
        // Mirror AAC-ELD: short live queue — a Music-sized buffer makes mirror audio sound ~0.25×.
        final String musicQueue = "queue max-size-buffers=0 max-size-bytes=0 max-size-time=2000000000";
        final String mirrorQueue = "queue max-size-buffers=0 max-size-bytes=0 max-size-time=100000000";
        alacPipeline = (Pipeline) Gst.parseLaunch(
                "appsrc name=alac-src ! " + musicQueue
                        + " ! avdec_alac ! audioconvert ! audioresample"
                        + " ! volume name=alac-vol ! " + audioSink);
        alacSrc = (AppSrc) alacPipeline.getElementByName("alac-src");
        alacSrc.setStreamType(AppSrc.StreamType.STREAM);
        alacSrc.setCaps(Caps.fromString("audio/x-alac,mpegversion=(int)4,channels=(int)2,rate=(int)44100,stream-format=raw,codec_data=(buffer)00000024616c616300000000000001600010280a0e0200ff00000000000000000000ac44"));
        configureMusicAudioAppSrc(alacSrc);

        aacEldPipeline = (Pipeline) Gst.parseLaunch(
                "appsrc name=aac-eld-src ! " + mirrorQueue
                        + " ! avdec_aac ! audioconvert ! audioresample"
                        + " ! volume name=aac-eld-vol ! " + audioSink);
        aacEldSrc = (AppSrc) aacEldPipeline.getElementByName("aac-eld-src");
        aacEldSrc.setStreamType(AppSrc.StreamType.STREAM);
        // AirPlay mirror AAC-ELD ASC used by iOS senders (do not "fix" for CAF aace fixtures).
        aacEldSrc.setCaps(Caps.fromString("audio/mpeg,mpegversion=(int)4,channels=(int)2,rate=(int)44100,stream-format=raw,codec_data=(buffer)f8e85000"));
        configureMirrorAudioAppSrc(aacEldSrc);

        aacLcPipeline = (Pipeline) Gst.parseLaunch(
                "appsrc name=aac-lc-src ! " + musicQueue
                        + " ! aacparse ! avdec_aac ! audioconvert ! audioresample"
                        + " ! volume name=aac-lc-vol ! " + audioSink);
        aacLcSrc = (AppSrc) aacLcPipeline.getElementByName("aac-lc-src");
        aacLcSrc.setStreamType(AppSrc.StreamType.STREAM);
        aacLcSrc.setCaps(Caps.fromString("audio/mpeg,mpegversion=(int)4,stream-format=adts,channels=(int)2,rate=(int)44100"));
        configureMusicAudioAppSrc(aacLcSrc);

        Element sink = h264Pipeline.getElementByName("sink");
        if (forceAppsink) {
            window = null;
            attachWindow = () -> {
            };
        } else if (useD3d11 || useXimage) {
            Canvas canvas = new Canvas();
            canvas.setBackground(Color.BLACK);
            window = GstFullscreenWindow.create(canvas);
            VideoOverlay overlay = VideoOverlay.wrap(sink);
            attachWindow = () -> overlay.setWindowHandle(Native.getComponentID(canvas));
            h264Pipeline.getBus().setSyncHandler(message -> {
                if (!VideoOverlay.isPrepareWindowHandleMessage(message)) {
                    return BusSyncReply.PASS;
                }
                attachWindow.run();
                return BusSyncReply.DROP;
            });
        } else {
            GstVideoComponent video = new GstVideoComponent((AppSink) sink);
            video.setBackground(Color.BLACK);
            window = GstFullscreenWindow.create(video);
            attachWindow = () -> {
            };
        }
    }

    @Override
    public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        // Release HLS before starting the appsrc mirror pipeline (shared GPU/display).
        hls.stop();
        // Music ALAC must not keep draining into Pulse alongside mirror AAC-ELD.
        stopAudioPipelines();
        // Fresh decoder state — a previous session / mid-GOP drop leaves avdec with
        // bad refs and paints ghosted / duplicated-looking frames.
        if (h264Pipeline.isPlaying()) {
            h264Pipeline.stop();
        }
        if (window != null) {
            GstFullscreenWindow.show(window);
            GstFullscreenWindow.onEdt(attachWindow);
        }
        h264Pipeline.play();
    }

    @Override
    public void onVideo(byte[] bytes) {
        Buffer buf = new Buffer(bytes.length);
        buf.map(true).put(bytes);
        buf.unmap();
        h264Src.pushBuffer(buf);
    }

    @Override
    public void onVideoSrcDisconnect() {
        if (window != null) {
            GstFullscreenWindow.hide(window);
        }
        h264Pipeline.stop();
    }

    @Override
    public void onAudioFormat(AudioStreamInfo audioStreamInfo) {
        this.audioCompressionType = audioStreamInfo.getCompressionType();
        // Only one audio codec pipeline may run — Music→Mirror left ALAC PLAYING and
        // sounded like ~0.25× when AAC-ELD started on top.
        stopAudioPipelines();
        if (audioCompressionType == AudioStreamInfo.CompressionType.ALAC) {
            alacPipeline.play();
        } else if (audioCompressionType == AudioStreamInfo.CompressionType.AAC_ELD) {
            aacEldPipeline.play();
        } else if (audioCompressionType == AudioStreamInfo.CompressionType.AAC) {
            aacLcPipeline.play();
        } else {
            log.warn("Unsupported audio compression {}", audioCompressionType);
        }
    }

    @Override
    public void onAudio(byte[] bytes) {
        onAudio(bytes, 0, bytes == null ? 0 : bytes.length);
    }

    @Override
    public void onAudio(byte[] bytes, int offset, int length) {
        if (bytes == null || length <= 0 || audioCompressionType == null) {
            return;
        }
        Buffer buf = new Buffer(length);
        buf.map(true).put(bytes, offset, length);
        buf.unmap();
        switch (audioCompressionType) {
            case ALAC -> alacSrc.pushBuffer(buf);
            case AAC_ELD -> aacEldSrc.pushBuffer(buf);
            case AAC -> aacLcSrc.pushBuffer(buf);
            default -> {
            }
        }
    }

    @Override
    public void onAudioSrcDisconnect() {
        stopAudioPipelines();
        audioCompressionType = null;
    }

    private void stopAudioPipelines() {
        alacPipeline.stop();
        aacEldPipeline.stop();
        aacLcPipeline.stop();
    }

    @Override
    public void setObserver(Playback.Observer observer) {
        this.observer = observer == null ? Playback.Observer.NONE : observer;
    }

    @Override
    public void onPlaylist(String playlistUri) {
        hls.start(playlistUri, volumeLinear);
    }

    @Override
    public void onPlaylistRemoved() {
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
        hls.seek(positionSeconds);
    }

    @Override
    public void onVolume(double volumeLinear) {
        this.volumeLinear = HlsPlaybackMath.clampVolume(volumeLinear);
        applyVolume(alacPipeline, "alac-vol");
        applyVolume(aacEldPipeline, "aac-eld-vol");
        applyVolume(aacLcPipeline, "aac-lc-vol");
        hls.setVolume(this.volumeLinear);
        log.info("Volume set to {}", this.volumeLinear);
    }

    private void applyVolume(Pipeline pipeline, String elementName) {
        Element vol = pipeline.getElementByName(elementName);
        if (vol != null) {
            vol.set("volume", volumeLinear);
        }
    }

    /**
     * Music RTP is bursty; treat appsrc as buffered (not ultra-low-latency live) so the
     * sink keeps a real pre-roll instead of prebuf=0 underruns.
     */
    private static void configureMusicAudioAppSrc(AppSrc src) {
        src.set("is-live", false);
        src.set("format", Format.TIME);
        src.set("do-timestamp", true);
        src.set("emit-signals", true);
        src.set("block", false);
        src.set("max-bytes", 2L * 1024 * 1024);
    }

    /** Screen-mirror AAC-ELD must stay low-latency live. */
    private static void configureMirrorAudioAppSrc(AppSrc src) {
        src.set("is-live", true);
        src.set("format", Format.TIME);
        src.set("do-timestamp", true);
        src.set("emit-signals", true);
        src.set("block", false);
        src.set("max-bytes", 512L * 1024);
    }

    private static boolean pulseSinkAvailable() {
        try {
            Element probe = ElementFactory.make("pulsesink", "pulse-probe");
            if (probe == null) {
                return false;
            }
            probe.dispose();
            return true;
        } catch (Exception e) {
            return false;
        }
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

    boolean isVideoPipelinePlaying() {
        return h264Pipeline.isPlaying();
    }

    boolean isAudioPipelinePlaying() {
        return alacPipeline.isPlaying() || aacEldPipeline.isPlaying() || aacLcPipeline.isPlaying();
    }

    boolean isHlsActive() {
        return hls.isActive();
    }

    boolean isFullscreenConfigured() {
        return nativeFullscreen || window != null && window.isUndecorated() && !window.isResizable();
    }
}
