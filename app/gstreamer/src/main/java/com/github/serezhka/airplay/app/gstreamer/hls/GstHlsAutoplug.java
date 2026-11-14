package com.github.serezhka.airplay.app.gstreamer.hls;

import lombok.extern.slf4j.Slf4j;
import org.freedesktop.gstreamer.Bin;
import org.freedesktop.gstreamer.Element;
import org.freedesktop.gstreamer.ElementFactory;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * playbin3 hook: configure each {@code tsdemux} for YouTube-style HLS CC gaps
 * ({@link MpegTsContinuityFix}, or {@code ignore-continuity-counter} when present).
 */
@Slf4j
final class GstHlsAutoplug {

    /** Weak so orphaned playbin graphs do not pin forever. */
    private static final Set<Element> CONFIGURED =
            Collections.newSetFromMap(new WeakHashMap<>());

    private GstHlsAutoplug() {
    }

    static void install(Bin playbin) {
        playbin.connect((Bin.DEEP_ELEMENT_ADDED) (bin, subBin, element) -> configure(element));
    }

    static void configure(Element element) {
        if (element == null || !"tsdemux".equals(factoryName(element))) {
            return;
        }
        synchronized (CONFIGURED) {
            if (!CONFIGURED.add(element)) {
                return;
            }
        }
        if (trySet(element, "ignore-continuity-counter", true)) {
            log.info("HLS autoplug: tsdemux ignore-continuity-counter=true");
            return;
        }
        new MpegTsContinuityFix().install(element);
    }

    static boolean supportsIgnoreContinuityCounter() {
        Element probe = null;
        try {
            probe = ElementFactory.make("tsdemux", "hls-cc-probe");
            return probe != null && trySet(probe, "ignore-continuity-counter", true);
        } catch (Throwable t) {
            return false;
        } finally {
            if (probe != null) {
                try {
                    probe.dispose();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
    }

    private static String factoryName(Element element) {
        try {
            ElementFactory factory = element.getFactory();
            return factory == null ? null : factory.getName();
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean trySet(Element element, String property, Object value) {
        try {
            element.set(property, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
