package com.github.serezhka.airplay.app.gstreamer.hls;

import lombok.extern.slf4j.Slf4j;
import org.freedesktop.gstreamer.Bin;
import org.freedesktop.gstreamer.Element;
import org.freedesktop.gstreamer.ElementFactory;
import org.freedesktop.gstreamer.PluginFeature;
import org.freedesktop.gstreamer.Registry;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * playbin3 hook: configure each {@code tsdemux} for YouTube-style HLS CC gaps
 * ({@link MpegTsContinuityFix}, or {@code ignore-continuity-counter} when present).
 * <p>
 * Also prefers software H.264 for HLS autoplug: {@code vah264dec} often READY-probes
 * OK then emits periodic {@code vaSyncSurface: internal decoding error} on fragmented
 * YouTube TS (smear), while {@code avdec_h264} conceals the same cuts. Mirror already
 * hard-wires software decode.
 */
@Slf4j
final class GstHlsAutoplug {

    /** Weak so orphaned playbin graphs do not pin forever. */
    private static final Set<Element> CONFIGURED =
            Collections.newSetFromMap(new WeakHashMap<>());

    private static final AtomicBoolean SOFTWARE_H264_FOR_HLS = new AtomicBoolean();

    /** Above PRIMARY so playbin3 picks software over a still-present HW factory. */
    private static final int AVDEC_H264_HLS_RANK = 301;

    private GstHlsAutoplug() {
    }

    static void install(Bin playbin) {
        preferSoftwareH264ForHls();
        playbin.connect((Bin.DEEP_ELEMENT_ADDED) (bin, subBin, element) -> configure(element));
    }

    /**
     * Demote {@code vah264dec} for the process so HLS {@code playbin3} autoplugs
     * {@code avdec_h264}. Idempotent; honors nothing from {@code GST_PLUGIN_FEATURE_RANK}
     * once HLS has started (env soft-rank is still fine).
     */
    static void preferSoftwareH264ForHls() {
        if (!SOFTWARE_H264_FOR_HLS.compareAndSet(false, true)) {
            return;
        }
        PluginFeature va = Registry.get().lookupFeature("vah264dec");
        if (va != null) {
            va.setRank(PluginFeature.Rank.NONE);
            log.info("HLS autoplug: vah264dec -> NONE (VA mid-stream decode errors on fragmented HLS)");
        }
        PluginFeature soft = Registry.get().lookupFeature("avdec_h264");
        if (soft != null) {
            soft.setRank(AVDEC_H264_HLS_RANK);
            log.info("HLS autoplug: avdec_h264 -> rank {}", AVDEC_H264_HLS_RANK);
        }
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
