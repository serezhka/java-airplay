package com.github.serezhka.airplay.player.gstreamer;

import com.github.serezhka.airplay.lib.AudioStreamInfo;
import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.server.AirPlayConsumer;
import lombok.extern.slf4j.Slf4j;
import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSrc;
import org.freedesktop.gstreamer.glib.GLib;

@Slf4j
public class GstPlayer implements AirPlayConsumer {

    static {
        GstPlayerUtils.configurePaths();
        GLib.setEnv("GST_DEBUG", "3", true);
        Gst.init(Version.of(1, 10), "BasicPipeline");
    }

    private final Pipeline h264Pipeline;
    private final Pipeline alacPipeline;
    private final Pipeline aacEldPipeline;

    private final AppSrc h264Src;
    private final AppSrc alacSrc;
    private final AppSrc aacEldSrc;

    private AudioStreamInfo.CompressionType audioCompressionType;

    public GstPlayer() {
        h264Pipeline = (Pipeline) Gst.parseLaunch("appsrc name=h264-src ! h264parse ! avdec_h264 ! autovideosink sync=false");

        h264Src = (AppSrc) h264Pipeline.getElementByName("h264-src");
        h264Src.setStreamType(AppSrc.StreamType.STREAM);
        h264Src.setCaps(Caps.fromString("video/x-h264,colorimetry=bt709,stream-format=(string)byte-stream,alignment=(string)au"));
        h264Src.set("is-live", true);
        h264Src.set("format", Format.TIME);
        h264Src.set("emit-signals", true);

        h264Pipeline.play();


        alacPipeline = (Pipeline) Gst.parseLaunch("appsrc name=alac-src ! avdec_alac ! audioconvert ! audioresample ! autoaudiosink sync=false"); // +

        alacSrc = (AppSrc) alacPipeline.getElementByName("alac-src");
        alacSrc.setStreamType(AppSrc.StreamType.STREAM);
        alacSrc.setCaps(Caps.fromString("audio/x-alac,mpegversion=(int)4,channels=(int)2,rate=(int)44100,stream-format=raw,codec_data=(buffer)00000024616c616300000000000001600010280a0e0200ff00000000000000000000ac44"));
        alacSrc.set("is-live", true);
        alacSrc.set("format", Format.TIME);
        alacSrc.set("emit-signals", true);

        alacPipeline.play();


        aacEldPipeline = (Pipeline) Gst.parseLaunch("appsrc name=aac-eld-src ! avdec_aac ! audioconvert ! audioresample ! autoaudiosink sync=false"); // +

        aacEldSrc = (AppSrc) aacEldPipeline.getElementByName("aac-eld-src");
        aacEldSrc.setStreamType(AppSrc.StreamType.STREAM);
        aacEldSrc.setCaps(Caps.fromString("audio/mpeg,mpegversion=(int)4,channnels=(int)2,rate=(int)44100,stream-format=raw,codec_data=(buffer)f8e85000"));
        aacEldSrc.set("is-live", true);
        aacEldSrc.set("format", Format.TIME);
        aacEldSrc.set("emit-signals", true);

        aacEldPipeline.play();
    }

    @Override
    public void onVideo(byte[] bytes) {
        Buffer buf = new Buffer(bytes.length);
        buf.map(true).put(bytes); // ByteBuffer.wrap(bytes)
        h264Src.pushBuffer(buf);
    }

    @Override
    public void onAudio(byte[] bytes) {
        Buffer buf = new Buffer(bytes.length);
        buf.map(true).put(bytes); // ByteBuffer.wrap(bytes)
        switch (audioCompressionType) {
            case ALAC:
                alacSrc.pushBuffer(buf);
                break;
            case AAC_ELD:
                aacEldSrc.pushBuffer(buf);
                break;
            default:
                break;
        }
    }

    @Override
    public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        log.info("onVideoFormat");
    }

    @Override
    public void onAudioFormat(AudioStreamInfo audioStreamInfo) {
        log.info("onAudioFormat: {}", audioStreamInfo.getAudioFormat());
        this.audioCompressionType = audioStreamInfo.getCompressionType();
    }
}
