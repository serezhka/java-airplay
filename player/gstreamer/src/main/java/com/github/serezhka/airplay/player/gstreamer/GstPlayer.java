package com.github.serezhka.airplay.player.gstreamer;

import com.github.serezhka.airplay.lib.AudioStreamInfo;
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
        // GST_DEBUG goes to stderr by default; write it next to the app log.
        GLib.setEnv("GST_DEBUG_NO_COLOR", "1", true);
        String gstLog = System.getProperty("airplay.gst.debug.file");
        if (gstLog == null || gstLog.isBlank()) {
            gstLog = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),
                    "airplay-gst-" + ProcessHandle.current().pid() + ".log").toString();
        }
        GLib.setEnv("GST_DEBUG_FILE", gstLog, true);
        GLib.setEnv("GST_DEBUG", System.getProperty("airplay.gst.debug", "3"), true);
        Gst.init(Version.of(1, 10), "BasicPipeline");
    }

    private final Pipeline h264Pipeline;
    private final Pipeline alacPipeline;
    private final Pipeline aacEldPipeline;
    private final JFrame window;
    private final Runnable attachWindow;
    private final boolean nativeFullscreen;

    private final AppSrc h264Src;
    private final AppSrc alacSrc;
    private final AppSrc aacEldSrc;

    private Pipeline hlsPipeline;

    private AudioStreamInfo.CompressionType audioCompressionType;

    public GstPlayer() {
        boolean d3d11 = Registry.get().lookupFeature("d3d11videosink") != null;
        boolean ximage = Registry.get().lookupFeature("ximagesink") != null;
        nativeFullscreen = d3d11;
        String sinkLaunch;
        if (d3d11) {
            sinkLaunch = " ! d3d11upload ! d3d11convert"
                    + " ! d3d11videosink name=sink sync=false force-aspect-ratio=true"
                    + " fullscreen-toggle-mode=property fullscreen=true";
        } else if (ximage) {
            sinkLaunch = " ! videoscale add-borders=true ! video/x-raw,format=BGRx"
                    + " ! ximagesink name=sink sync=false force-aspect-ratio=true";
        } else {
            sinkLaunch = " ! appsink name=sink sync=false";
        }
        log.info("GStreamer video sink: {}", d3d11 ? "d3d11videosink" : ximage ? "ximagesink" : "appsink");
        h264Pipeline = (Pipeline) Gst.parseLaunch(
                "appsrc name=h264-src ! h264parse config-interval=-1 ! avdec_h264"
                        + " ! videoflip video-direction=auto ! videoconvert"
                        + sinkLaunch);

        h264Src = (AppSrc) h264Pipeline.getElementByName("h264-src");
        h264Src.setStreamType(AppSrc.StreamType.STREAM);
        // AirPlay sends Annex-B NAL units (start codes), not length-prefixed AU.
        h264Src.setCaps(Caps.fromString("video/x-h264,colorimetry=bt709,stream-format=(string)byte-stream,alignment=(string)nal"));
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
        aacEldSrc.setCaps(Caps.fromString("audio/mpeg,mpegversion=(int)4,channnels=(int)2,rate=(int)44100,stream-format=raw,codec_data=(buffer)f8e85000"));
        aacEldSrc.set("is-live", true);
        aacEldSrc.set("format", Format.TIME);
        aacEldSrc.set("emit-signals", true);

        Element sink = h264Pipeline.getElementByName("sink");
        if (d3d11) {
            window = null;
            attachWindow = () -> {
            };
        } else if (ximage) {
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
        alacPipeline.play();
        aacEldPipeline.play();
    }

    @Override
    public void onAudio(byte[] bytes) {
        Buffer buf = new Buffer(bytes.length);
        buf.map(true).put(bytes);
        buf.unmap();
        switch (audioCompressionType) {
            case ALAC -> alacSrc.pushBuffer(buf);
            case AAC_ELD -> aacEldSrc.pushBuffer(buf);
        }
    }

    @Override
    public void onAudioSrcDisconnect() {
        alacPipeline.stop();
        aacEldPipeline.stop();
    }

    @Override
    public void onMediaPlaylist(String playlistUri) {
        hlsPipeline = (Pipeline) Gst.parseLaunch("playbin3 uri=" + playlistUri);
        hlsPipeline.play();
    }

    @Override
    public void onMediaPlaylistRemove() {
        if (hlsPipeline != null) {
            hlsPipeline.stop();
        }
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
