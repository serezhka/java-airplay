package com.github.serezhka.airplay.client.video.source;

import lombok.extern.slf4j.Slf4j;
import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.glib.GLib;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
public class GstTestSource {

    static {
        GstUtils.configurePaths();
        GLib.setEnv("GST_DEBUG", "3", true);
        Gst.init(Version.of(1, 10), "BasicPipeline");
    }

    public GstTestSource(Consumer<byte[]> consumer) {
        Pipeline pipeline = (Pipeline) Gst.parseLaunch("videotestsrc ! x264enc tune=zerolatency byte-stream=true ! video/x-h264,colorimetry=bt709,stream-format=(string)byte-stream,alignment=(string)au ! appsink name=videoSink");
        AppSink videoSink = (AppSink) pipeline.getElementByName("videoSink");
        videoSink.set("emit-signals", true);
        videoSink.set("async", true);

        VideoSinkListener videoSinkListener = new VideoSinkListener(consumer);
        videoSink.connect(videoSinkListener);

        pipeline.play();
    }

    private static class VideoSinkListener implements AppSink.NEW_SAMPLE {

        private final Consumer<byte[]> consumer;

        public VideoSinkListener(Consumer<byte[]> consumer) {
            this.consumer = consumer;
        }

        @Override
        public FlowReturn newSample(AppSink elem) {
            Sample sample = elem.pullSample();
            ByteBuffer buffer = sample.getBuffer().map(false);
            byte[] bytes = new byte[buffer.capacity()];
            buffer.get(bytes);
            consumer.accept(bytes);
            sample.disown();
            return FlowReturn.OK;
        }
    }
}
