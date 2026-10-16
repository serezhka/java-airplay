package com.github.serezhka.airplay.app.gstreamer.capture;

import com.github.serezhka.airplay.app.gstreamer.util.GstPlayerUtils;
import com.github.serezhka.airplay.client.audio.AudioSource;
import lombok.extern.slf4j.Slf4j;
import org.freedesktop.gstreamer.FlowReturn;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;
import org.freedesktop.gstreamer.Sample;
import org.freedesktop.gstreamer.Version;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.glib.GLib;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * Sine tone as AAC-LC ADTS frames for AirPlay audio smoke tests.
 * Real mirror audio is often AAC-ELD ({@code ct=8}); this LC stream exercises
 * SETUP type=96, FairPlay audio crypto, and AAC playback paths ({@code ct=4}).
 */
@Slf4j
public final class GstAudioTestSource implements AudioSource {

    static {
        GstPlayerUtils.configurePaths();
        GLib.setEnv("GST_DEBUG", "2", true);
        Gst.init(Version.of(1, 10), "AirPlayAudioTest");
    }

    private final Pipeline pipeline;

    public GstAudioTestSource(Consumer<byte[]> consumer) {
        // ADTS so generic AAC demuxers can find frame boundaries after decrypt padding.
        pipeline = (Pipeline) Gst.parseLaunch(
                "audiotestsrc wave=sine freq=440 is-live=true ! "
                        + "audio/x-raw,rate=44100,channels=2 ! "
                        + "avenc_aac bitrate=128000 ! "
                        + "aacparse ! audio/mpeg,mpegversion=4,stream-format=adts ! "
                        + "appsink name=audioSink emit-signals=true sync=false");
        AppSink audioSink = (AppSink) pipeline.getElementByName("audioSink");
        audioSink.set("emit-signals", true);
        audioSink.connect((AppSink.NEW_SAMPLE) elem -> {
            Sample sample = elem.pullSample();
            ByteBuffer buffer = sample.getBuffer().map(false);
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            consumer.accept(bytes);
            sample.disown();
            return FlowReturn.OK;
        });
        pipeline.play();
        log.info("Audio test source started (AAC-LC ADTS sine)");
    }

    @Override
    public void close() {
        if (pipeline != null) {
            pipeline.stop();
        }
    }
}
