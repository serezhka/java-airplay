package com.github.serezhka.airplay.client.video.source;

import lombok.extern.slf4j.Slf4j;
import org.freedesktop.gstreamer.FlowReturn;
import org.freedesktop.gstreamer.Pipeline;
import org.freedesktop.gstreamer.Sample;
import org.freedesktop.gstreamer.State;
import org.freedesktop.gstreamer.Version;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.glib.GLib;
import org.freedesktop.gstreamer.Gst;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

@Slf4j
public class GstTestSource implements AutoCloseable {

    static {
        GstUtils.configurePaths();
        GLib.setEnv("GST_DEBUG", "2", true);
        Gst.init(Version.of(1, 10), "airplay-client-testsrc");
    }

    private final Pipeline pipeline;

    public GstTestSource(Consumer<byte[]> consumer) {
        pipeline = (Pipeline) Gst.parseLaunch(
                "videotestsrc is-live=true ! "
                        + "video/x-raw,framerate=30/1,width=1280,height=720 ! "
                        + "x264enc tune=zerolatency byte-stream=true key-int-max=30 ! "
                        + "video/x-h264,colorimetry=bt709,stream-format=(string)byte-stream,"
                        + "alignment=(string)au ! appsink name=videoSink emit-signals=true sync=false");
        AppSink videoSink = (AppSink) pipeline.getElementByName("videoSink");
        videoSink.set("emit-signals", true);
        videoSink.set("async", false);
        videoSink.connect((AppSink.NEW_SAMPLE) elem -> {
            Sample sample = elem.pullSample();
            if (sample == null) {
                return FlowReturn.ERROR;
            }
            try {
                ByteBuffer buffer = sample.getBuffer().map(false);
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                consumer.accept(bytes);
            } finally {
                sample.dispose();
            }
            return FlowReturn.OK;
        });
        pipeline.play();
    }

    @Override
    public void close() {
        try {
            pipeline.setState(State.NULL);
        } catch (Throwable t) {
            log.debug("GstTestSource stop: {}", t.toString());
        }
        try {
            pipeline.dispose();
        } catch (Throwable t) {
            log.debug("GstTestSource dispose: {}", t.toString());
        }
    }
}
