package com.github.serezhka.airplay.player.gstreamer;

import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;

public class GstPlayerDefault extends GstPlayer {

    @Override
    protected Pipeline createH264Pipeline() {
        return (Pipeline) Gst.parseLaunch("appsrc name=h264-src ! h264parse ! avdec_h264 ! autovideosink sync=false");
    }
}
