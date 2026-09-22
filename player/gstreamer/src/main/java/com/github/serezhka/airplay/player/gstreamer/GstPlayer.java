package com.github.serezhka.airplay.player.gstreamer;

import com.github.serezhka.airplay.lib.AudioStreamInfo;
import com.github.serezhka.airplay.lib.AppLogs;
import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.server.AirPlayConsumer;
import com.sun.jna.Native;
import lombok.extern.slf4j.Slf4j;
import org.freedesktop.gstreamer.Buffer;
import org.freedesktop.gstreamer.BusSyncReply;
import org.freedesktop.gstreamer.Caps;
import org.freedesktop.gstreamer.Element;
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

    private final AppSrc h264Src;
    private final AppSrc alacSrc;
    private final AppSrc aacEldSrc;
    private final AppSrc aacLcSrc;

    private final GstHlsPipeline hls = new GstHlsPipeline();
    private volatile double volumeLinear = 1.0;

    private AudioStreamInfo.CompressionType audioCompressionType;

    public GstPlayer() {
        this(60);
    }

    public GstPlayer(int fps) {
        log.info("GStreamer debug log: {}", AppLogs.playerLogFile("gstreamer"));
        int framerate = Math.max(1, fps);
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
        log.info("GStreamer video sink: {}",
                forceAppsink ? "fakesink(forced)" : useD3d11 ? "d3d11videosink" : useXimage ? "ximagesink" : "appsink");
        h264Pipeline = (Pipeline) Gst.parseLaunch(
                "appsrc name=h264-src ! h264parse config-interval=-1 ! avdec_h264"
                        + " ! videoflip video-direction=auto ! videoconvert"
                        + sinkLaunch);

        h264Src = (AppSrc) h264Pipeline.getElementByName("h264-src");
        h264Src.setStreamType(AppSrc.StreamType.STREAM);
        h264Src.setCaps(Caps.fromString(
                "video/x-h264,colorimetry=bt709,stream-format=(string)byte-stream,alignment=(string)nal,framerate="
                        + framerate + "/1"));
        h264Src.set("is-live", true);
        h264Src.set("format", Format.TIME);
        h264Src.set("do-timestamp", true);
        h264Src.set("emit-signals", true);

        alacPipeline = (Pipeline) Gst.parseLaunch(
                "appsrc name=alac-src ! avdec_alac ! audioconvert ! audioresample"
                        + " ! volume name=alac-vol ! autoaudiosink sync=false");
        alacSrc = (AppSrc) alacPipeline.getElementByName("alac-src");
        alacSrc.setStreamType(AppSrc.StreamType.STREAM);
        alacSrc.setCaps(Caps.fromString("audio/x-alac,mpegversion=(int)4,channels=(int)2,rate=(int)44100,stream-format=raw,codec_data=(buffer)00000024616c616300000000000001600010280a0e0200ff00000000000000000000ac44"));
        alacSrc.set("is-live", true);
        alacSrc.set("format", Format.TIME);
        alacSrc.set("emit-signals", true);

        aacEldPipeline = (Pipeline) Gst.parseLaunch(
                "appsrc name=aac-eld-src ! avdec_aac ! audioconvert ! audioresample"
                        + " ! volume name=aac-eld-vol ! autoaudiosink sync=false");
        aacEldSrc = (AppSrc) aacEldPipeline.getElementByName("aac-eld-src");
        aacEldSrc.setStreamType(AppSrc.StreamType.STREAM);
        aacEldSrc.setCaps(Caps.fromString("audio/mpeg,mpegversion=(int)4,channels=(int)2,rate=(int)44100,stream-format=raw,codec_data=(buffer)f8e85000"));
        aacEldSrc.set("is-live", true);
        aacEldSrc.set("format", Format.TIME);
        aacEldSrc.set("emit-signals", true);

        aacLcPipeline = (Pipeline) Gst.parseLaunch(
                "appsrc name=aac-lc-src ! aacparse ! avdec_aac ! audioconvert ! audioresample"
                        + " ! volume name=aac-lc-vol ! autoaudiosink sync=false");
        aacLcSrc = (AppSrc) aacLcPipeline.getElementByName("aac-lc-src");
        aacLcSrc.setStreamType(AppSrc.StreamType.STREAM);
        aacLcSrc.setCaps(Caps.fromString("audio/mpeg,mpegversion=(int)4,stream-format=adts,channels=(int)2,rate=(int)44100"));
        aacLcSrc.set("is-live", true);
        aacLcSrc.set("format", Format.TIME);
        aacLcSrc.set("emit-signals", true);

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
        hls.start(playlistUri, volumeLinear);
    }

    @Override
    public void onMediaPlaylistRemove() {
        hls.stop();
    }

    @Override
    public void onMediaPlaylistContent(String playlistUri, String content) {
        if (playlistUri == null || !playlistUri.contains("mediadata.m3u8") || content == null) {
            return;
        }
        // Ignore sliding-window live playlists — their EXTINF sums inflate ad duration.
        if (!content.contains("#EXT-X-ENDLIST")) {
            return;
        }
        double sum = 0;
        for (String line : content.split("\n")) {
            if (line.startsWith("#EXTINF:")) {
                String value = line.substring("#EXTINF:".length()).split(",", 2)[0].trim();
                try {
                    sum += Double.parseDouble(value);
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
        }
        hls.noteMediaDuration(sum);
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
        hls.seek(positionSeconds);
    }

    @Override
    public void onVolume(double volumeLinear) {
        this.volumeLinear = Math.max(0.0, Math.min(1.0, volumeLinear));
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
        // At VOD EOS the pipeline is paused locally, but report rate=1 so the phone sees
        // "playing at end" (rate=0 looks like user pause and blocks playlistRemove).
        double rate = (hls.isPaused() && !hls.isEnded()) ? 0 : 1;
        return new PlaybackInfo(duration, position, rate);
    }

    boolean isVideoPipelinePlaying() {
        return h264Pipeline.isPlaying();
    }

    boolean isFullscreenConfigured() {
        return nativeFullscreen || window != null && window.isUndecorated() && !window.isResizable();
    }
}
