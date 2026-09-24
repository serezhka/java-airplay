package com.github.serezhka.airplay.player.gstreamer;

import lombok.extern.slf4j.Slf4j;
import org.freedesktop.gstreamer.Bin;
import org.freedesktop.gstreamer.Element;
import org.freedesktop.gstreamer.ElementFactory;
import org.freedesktop.gstreamer.GhostPad;
import org.freedesktop.gstreamer.Pad;
import org.freedesktop.gstreamer.Registry;

import java.awt.Canvas;
import java.awt.Color;

/**
 * Shared fullscreen video sinks for mirroring and HLS (d3d11 / ximage / auto).
 */
@Slf4j
final class GstVideoSinkFactory {

    record Result(Element sink, Canvas canvas, boolean overlay) {
    }

    static boolean hasD3d11() {
        return Registry.get().lookupFeature("d3d11videosink") != null;
    }

    static boolean hasXimage() {
        return Registry.get().lookupFeature("ximagesink") != null;
    }

    /**
     * @param sync clock-sync to the pipeline (prefer true when audio+video share one pipeline)
     */
    static Result create(String namePrefix, boolean sync) {
        if (hasD3d11()) {
            Canvas canvas = newCanvas();
            Element sink = createD3d11Bin(namePrefix, sync);
            return new Result(sink, canvas, true);
        }
        if (hasXimage()) {
            Canvas canvas = newCanvas();
            Element sink = ElementFactory.make("ximagesink", namePrefix + "-sink");
            applySinkProps(sink, sync);
            return new Result(sink, canvas, true);
        }
        Element sink = ElementFactory.make("autovideosink", namePrefix + "-sink");
        applySinkProps(sink, sync);
        return new Result(sink, null, false);
    }

    /** Leaf sink used for VideoOverlay (inside a bin when d3d11). */
    static Element overlayTarget(Element sinkOrBin, String leafName) {
        if (sinkOrBin instanceof Bin bin) {
            Element inner = bin.getElementByName(leafName);
            return inner != null ? inner : sinkOrBin;
        }
        return sinkOrBin;
    }

    private static Element createD3d11Bin(String namePrefix, boolean sync) {
        Bin bin = new Bin(namePrefix + "-bin");
        Element upload = ElementFactory.make("d3d11upload", namePrefix + "-upload");
        Element convert = ElementFactory.make("d3d11convert", namePrefix + "-convert");
        Element sink = ElementFactory.make("d3d11videosink", namePrefix + "-sink");
        if (upload == null || convert == null || sink == null) {
            Element fallback = ElementFactory.make("autovideosink", namePrefix + "-sink");
            applySinkProps(fallback, sync);
            return fallback;
        }
        applySinkProps(sink, sync);
        bin.addMany(upload, convert, sink);
        if (!Element.linkMany(upload, convert, sink)) {
            log.warn("Failed to link d3d11 sink chain, falling back to autovideosink");
            Element fallback = ElementFactory.make("autovideosink", namePrefix + "-sink");
            applySinkProps(fallback, sync);
            return fallback;
        }
        Pad sinkPad = upload.getStaticPad("sink");
        bin.addPad(new GhostPad("sink", sinkPad));
        return bin;
    }

    private static void applySinkProps(Element sink, boolean sync) {
        if (sink == null) {
            return;
        }
        sink.set("sync", sync);
        try {
            sink.set("force-aspect-ratio", true);
        } catch (Exception ignored) {
            // not all sinks support it
        }
    }

    private static Canvas newCanvas() {
        Canvas canvas = new Canvas();
        canvas.setBackground(Color.BLACK);
        return canvas;
    }

    private GstVideoSinkFactory() {
    }
}
