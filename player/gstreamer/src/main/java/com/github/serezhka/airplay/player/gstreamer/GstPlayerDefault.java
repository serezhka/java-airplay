package com.github.serezhka.airplay.player.gstreamer;

import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;

public class GstPlayerDefault extends GstPlayer {

    @Override
    protected Pipeline createH264Pipeline() {
        // autovideosink picks xvimagesink here. xvimagesink:
        //  - mis-renders decoder I420 with padded stride (green diagonal stripes)
        //  - rejects BGRx (not-negotiated)
        // ximagesink accepts packed BGRx, so stride padding is irrelevant.
        return (Pipeline) Gst.parseLaunch(
                "appsrc name=h264-src ! h264parse config-interval=-1 ! avdec_h264 ! videoconvert ! video/x-raw,format=BGRx ! ximagesink sync=false");
    }
}
