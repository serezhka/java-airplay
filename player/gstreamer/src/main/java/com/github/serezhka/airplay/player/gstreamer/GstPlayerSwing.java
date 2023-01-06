package com.github.serezhka.airplay.player.gstreamer;

import com.formdev.flatlaf.FlatDarkLaf;
import com.github.serezhka.airplay.lib.VideoStreamInfo;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.swing.GstVideoComponent;

import javax.swing.*;
import java.awt.*;

public class GstPlayerSwing extends GstPlayer {

    private final JFrame window;

    static {
        FlatDarkLaf.setup();
    }

    public GstPlayerSwing() {
        AppSink sink = (AppSink) h264Pipeline.getElementByName("sink");
        GstVideoComponent vc = new GstVideoComponent(sink);

        window = new JFrame("AirPlay player");
        window.add(vc);
        vc.setPreferredSize(new Dimension(800, 600));
        window.pack();
        window.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    }

    @Override
    protected Pipeline createH264Pipeline() {
        return (Pipeline) Gst.parseLaunch("appsrc name=h264-src ! h264parse ! avdec_h264 ! videoconvert ! appsink name=sink sync=false");
    }

    @Override
    public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        window.setVisible(true);
        super.onVideoFormat(videoStreamInfo);
    }

    @Override
    public void onVideoSrcDisconnect() {
        window.setVisible(false);
        super.onVideoSrcDisconnect();
    }
}
