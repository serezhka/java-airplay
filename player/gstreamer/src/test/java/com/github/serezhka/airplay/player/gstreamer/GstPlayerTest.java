package com.github.serezhka.airplay.player.gstreamer;

import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;
import org.freedesktop.gstreamer.Version;
import org.freedesktop.gstreamer.glib.GLib;

import java.util.concurrent.TimeUnit;

class GstPlayerTest {

    static {
        GstPlayerUtils.configurePaths();
        GLib.setEnv("GST_DEBUG", "3", true);
        Gst.init(Version.of(1, 10), "BasicPipeline");
    }

    public static void main(String[] args) {
        try (Pipeline pipeline = (Pipeline) Gst.parseLaunch("videotestsrc ! autovideosink")) {
            pipeline.play();
            TimeUnit.SECONDS.sleep(3);
            System.exit(0);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}