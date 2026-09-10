package com.github.serezhka.airplay.player.gstreamer;

import com.github.serezhka.airplay.lib.AudioStreamInfo;
import com.github.serezhka.airplay.lib.AppLogs;
import com.github.serezhka.airplay.lib.HlsLifecycle;
import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.server.AirPlayConsumer;
import com.sun.jna.Native;
import lombok.extern.slf4j.Slf4j;
import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.elements.AppSrc;
import org.freedesktop.gstreamer.glib.GLib;
import org.freedesktop.gstreamer.interfaces.VideoOverlay;
import org.freedesktop.gstreamer.swing.GstVideoComponent;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.TimeUnit;

@Slf4j
public class GstPlayer implements AirPlayConsumer {

    static {
        GstPlayerUtils.configurePaths();
        GLib.setEnv("GST_DEBUG_NO_COLOR", "1", true);
        GLib.setEnv("GST_DEBUG_FILE", AppLogs.playerLogFile("gstreamer").toString(), true);
        GLib.setEnv("GST_DEBUG", System.getProperty("airplay.gst.debug", "3"), true);
        Gst.init(Version.of(1, 10), "BasicPipeline");
    }

    private final Pipeline h264Pipeline;
    private final Pipeline alacPipeline;
    private final Pipeline aacEldPipeline;
    private final Pipeline aacLcPipeline;
    private final JFrame window;
    private final Runnable attachWindow;
    private final boolean nativeFullscreen;
    private final boolean useD3d11;
    private final boolean useXimage;

    private final AppSrc h264Src;
    private final AppSrc alacSrc;
    private final AppSrc aacEldSrc;
    private final AppSrc aacLcSrc;

    private Pipeline hlsPipeline;
    private String hlsUri;
    private JFrame hlsWindow;
    private Canvas hlsCanvas;

    private AudioStreamInfo.CompressionType audioCompressionType;

    public GstPlayer() {
        this(60);
    }

    public GstPlayer(int fps) {
        log.info("GStreamer debug log: {}", AppLogs.playerLogFile("gstreamer"));
        int framerate = Math.max(1, fps);
        useD3d11 = Registry.get().lookupFeature("d3d11videosink") != null;
        useXimage = Registry.get().lookupFeature("ximagesink") != null;
        nativeFullscreen = useD3d11;
        String sinkLaunch;
        if (useD3d11) {
            sinkLaunch = " ! d3d11upload ! d3d11convert"
                    + " ! d3d11videosink name=sink sync=false force-aspect-ratio=true"
                    + " fullscreen-toggle-mode=property fullscreen=true";
        } else if (useXimage) {
            sinkLaunch = " ! videoscale add-borders=true ! video/x-raw,format=BGRx"
                    + " ! ximagesink name=sink sync=false force-aspect-ratio=true";
        } else {
            sinkLaunch = " ! appsink name=sink sync=false";
        }
        log.info("GStreamer video sink: {}", useD3d11 ? "d3d11videosink" : useXimage ? "ximagesink" : "appsink");
        h264Pipeline = (Pipeline) Gst.parseLaunch(
                "appsrc name=h264-src ! h264parse config-interval=-1 ! avdec_h264"
                        + " ! videoflip video-direction=auto ! videoconvert"
                        + sinkLaunch);

        h264Src = (AppSrc) h264Pipeline.getElementByName("h264-src");
        h264Src.setStreamType(AppSrc.StreamType.STREAM);
        // AirPlay sends Annex-B NAL units (start codes), not length-prefixed AU.
        h264Src.setCaps(Caps.fromString(
                "video/x-h264,colorimetry=bt709,stream-format=(string)byte-stream,alignment=(string)nal,framerate="
                        + framerate + "/1"));
        h264Src.set("is-live", true);
        h264Src.set("format", Format.TIME);
        h264Src.set("do-timestamp", true);
        h264Src.set("emit-signals", true);

        alacPipeline = (Pipeline) Gst.parseLaunch("appsrc name=alac-src ! avdec_alac ! audioconvert ! audioresample ! autoaudiosink sync=false");

        alacSrc = (AppSrc) alacPipeline.getElementByName("alac-src");
        alacSrc.setStreamType(AppSrc.StreamType.STREAM);
        alacSrc.setCaps(Caps.fromString("audio/x-alac,mpegversion=(int)4,channels=(int)2,rate=(int)44100,stream-format=raw,codec_data=(buffer)00000024616c616300000000000001600010280a0e0200ff00000000000000000000ac44"));
        alacSrc.set("is-live", true);
        alacSrc.set("format", Format.TIME);
        alacSrc.set("emit-signals", true);

        aacEldPipeline = (Pipeline) Gst.parseLaunch("appsrc name=aac-eld-src ! avdec_aac ! audioconvert ! audioresample ! autoaudiosink sync=false");

        aacEldSrc = (AppSrc) aacEldPipeline.getElementByName("aac-eld-src");
        aacEldSrc.setStreamType(AppSrc.StreamType.STREAM);
        aacEldSrc.setCaps(Caps.fromString("audio/mpeg,mpegversion=(int)4,channels=(int)2,rate=(int)44100,stream-format=raw,codec_data=(buffer)f8e85000"));
        aacEldSrc.set("is-live", true);
        aacEldSrc.set("format", Format.TIME);
        aacEldSrc.set("emit-signals", true);

        // AAC-LC (ct=4): accept ADTS (test client) or raw AUs with LC ASC.
        aacLcPipeline = (Pipeline) Gst.parseLaunch(
                "appsrc name=aac-lc-src ! aacparse ! avdec_aac ! audioconvert ! audioresample ! autoaudiosink sync=false");
        aacLcSrc = (AppSrc) aacLcPipeline.getElementByName("aac-lc-src");
        aacLcSrc.setStreamType(AppSrc.StreamType.STREAM);
        aacLcSrc.setCaps(Caps.fromString("audio/mpeg,mpegversion=(int)4,stream-format=adts,channels=(int)2,rate=(int)44100"));
        aacLcSrc.set("is-live", true);
        aacLcSrc.set("format", Format.TIME);
        aacLcSrc.set("emit-signals", true);

        Element sink = h264Pipeline.getElementByName("sink");
        if (useD3d11) {
            window = null;
            attachWindow = () -> {
            };
        } else if (useXimage) {
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
        Buffer buf = new Buffer(bytes.length);
        buf.map(true).put(bytes);
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
        alacPipeline.stop();
        aacEldPipeline.stop();
        aacLcPipeline.stop();
    }

    @Override
    public void onMediaPlaylist(String playlistUri) {
        startHlsPipeline(playlistUri);
    }

    private void startHlsPipeline(String playlistUri) {
        stopHlsPipeline();
        hlsUri = playlistUri;
        Element videoSink = createHlsVideoSink();
        hlsPipeline = (Pipeline) Gst.parseLaunch("playbin3 name=hls");
        hlsPipeline.set("uri", playlistUri);
        hlsPipeline.set("video-sink", videoSink);
        if (useXimage && hlsCanvas != null) {
            GstFullscreenWindow.show(hlsWindow);
            VideoOverlay overlay = VideoOverlay.wrap(videoSink);
            Runnable attachHlsWindow = () -> overlay.setWindowHandle(Native.getComponentID(hlsCanvas));
            hlsPipeline.getBus().setSyncHandler(message -> {
                if (!VideoOverlay.isPrepareWindowHandleMessage(message)) {
                    return BusSyncReply.PASS;
                }
                GstFullscreenWindow.onEdt(attachHlsWindow);
                return BusSyncReply.DROP;
            });
        }
        hlsPipeline.getBus().connect((Bus.EOS) source -> {
            if (hlsUri == null) {
                return;
            }
            log.info("HLS ended, requesting playlist refresh for {}", hlsUri);
            HlsLifecycle.notifyEnded();
        });
        hlsPipeline.play();
    }

    private Element createHlsVideoSink() {
        if (useD3d11) {
            Element sink = ElementFactory.make("d3d11videosink", "hls-sink");
            sink.set("sync", false);
            sink.set("force-aspect-ratio", true);
            sink.set("fullscreen", true);
            return sink;
        }
        if (useXimage) {
            hlsCanvas = new Canvas();
            hlsCanvas.setBackground(Color.BLACK);
            hlsWindow = GstFullscreenWindow.create(hlsCanvas);
            Element sink = ElementFactory.make("ximagesink", "hls-sink");
            sink.set("sync", false);
            sink.set("force-aspect-ratio", true);
            return sink;
        }
        Element sink = ElementFactory.make("autovideosink", "hls-sink");
        sink.set("sync", false);
        return sink;
    }

    private void stopHlsPipeline() {
        if (hlsPipeline != null) {
            hlsPipeline.stop();
            hlsPipeline = null;
        }
        if (hlsWindow != null) {
            GstFullscreenWindow.hide(hlsWindow);
            hlsWindow = null;
            hlsCanvas = null;
        }
    }

    @Override
    public void onMediaPlaylistRemove() {
        hlsUri = null;
        stopHlsPipeline();
    }

    @Override
    public void onMediaPlaylistPause() {
        if (hlsPipeline != null && hlsPipeline.isPlaying()) {
            hlsPipeline.pause();
        }
    }

    @Override
    public void onMediaPlaylistResume() {
        if (hlsPipeline != null && !hlsPipeline.isPlaying()) {
            hlsPipeline.play();
        }
    }

    @Override
    public PlaybackInfo playbackInfo() {
        if (hlsPipeline != null) {
            return new PlaybackInfo(
                    hlsPipeline.queryDuration(TimeUnit.SECONDS),
                    hlsPipeline.queryPosition(TimeUnit.SECONDS));
        }
        return AirPlayConsumer.super.playbackInfo();
    }

    boolean isVideoPipelinePlaying() {
        return h264Pipeline.isPlaying();
    }

    boolean isFullscreenConfigured() {
        return nativeFullscreen || window != null && window.isUndecorated() && !window.isResizable();
    }
}
