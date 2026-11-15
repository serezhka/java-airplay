package com.github.serezhka.airplay.app.gstreamer.hls;

import com.github.serezhka.airplay.app.gstreamer.util.GstPlayerUtils;
import org.freedesktop.gstreamer.Element;
import org.freedesktop.gstreamer.ElementFactory;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Version;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class GstHlsAutoplugTest {

    private static boolean gstReady;

    @BeforeAll
    static void initGst() {
        try {
            GstPlayerUtils.configurePaths();
            if (!Gst.isInitialized()) {
                Gst.init(Version.of(1, 10), "GstHlsAutoplugTest");
            }
            gstReady = ElementFactory.make("fakesink", "probe") != null;
        } catch (Throwable t) {
            gstReady = false;
        }
    }

    @Test
    void configureTsDemuxInstallsCcFixOrIgnoreFlag() {
        Assumptions.assumeTrue(gstReady, "GStreamer native libs not available");
        Element ts = tryMake("tsdemux", "test-tsdemux");
        Assumptions.assumeTrue(ts != null, "tsdemux not installed");
        // Do not dispose: CC-fix pad probe keeps native refs for the process lifetime
        // (same as a live playbin). Disposing here aborts later GST tests in this JVM.
        assertDoesNotThrow(() -> GstHlsAutoplug.configure(ts));
    }

    @Test
    void configureTsDemuxIsIdempotent() {
        Assumptions.assumeTrue(gstReady, "GStreamer native libs not available");
        Element ts = tryMake("tsdemux", "test-tsdemux-2");
        Assumptions.assumeTrue(ts != null, "tsdemux not installed");
        assertDoesNotThrow(() -> {
            GstHlsAutoplug.configure(ts);
            GstHlsAutoplug.configure(ts);
        });
    }

    @Test
    void supportsIgnoreContinuityCounterDoesNotThrow() {
        Assumptions.assumeTrue(gstReady, "GStreamer native libs not available");
        Assumptions.assumeTrue(tryMake("tsdemux", "probe") != null, "tsdemux missing");
        assertDoesNotThrow(GstHlsAutoplug::supportsIgnoreContinuityCounter);
    }

    @Test
    void preferSoftwareH264ForHlsIsIdempotent() {
        Assumptions.assumeTrue(gstReady, "GStreamer native libs not available");
        assertDoesNotThrow(() -> {
            GstHlsAutoplug.preferSoftwareH264ForHls();
            GstHlsAutoplug.preferSoftwareH264ForHls();
        });
    }

    /** Unit CI may have base GST without mpegtsdemux; make() throws instead of null. */
    private static Element tryMake(String factory, String name) {
        try {
            return ElementFactory.make(factory, name);
        } catch (Throwable t) {
            return null;
        }
    }
}
