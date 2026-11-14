package com.github.serezhka.airplay.app.gstreamer.hls;

import com.github.serezhka.airplay.app.gstreamer.util.GstPlayerUtils;
import org.freedesktop.gstreamer.Bus;
import org.freedesktop.gstreamer.Element;
import org.freedesktop.gstreamer.ElementFactory;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;
import org.freedesktop.gstreamer.Version;
import org.freedesktop.gstreamer.glib.GLib;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Uses captured YouTube HLS fragments when present at {@code /tmp/seg000.ts}+{@code seg001.ts}
 * (lab self-test). Skips otherwise.
 */
class MpegTsContinuityFixLiveSegTest {

    private static final Path SEG0 = Path.of("/tmp/seg000.ts");
    private static final Path SEG1 = Path.of("/tmp/seg001.ts");

    @BeforeAll
    static void initGst() {
        Assumptions.assumeTrue(Files.isRegularFile(SEG0) && Files.isRegularFile(SEG1),
                "need /tmp/seg000.ts and seg001.ts");
        try {
            GstPlayerUtils.configurePaths();
            GLib.setEnv("GST_DEBUG_NO_COLOR", "1", true);
            if (!Gst.isInitialized()) {
                Gst.init(Version.of(1, 10), "MpegTsContinuityFixLiveSegTest");
            }
        } catch (Throwable t) {
            Assumptions.assumeTrue(false, "GStreamer native libs not available: " + t);
        }
        Assumptions.assumeTrue(ElementFactory.make("tsdemux", "probe") != null, "tsdemux missing");
    }

    @Test
    @Timeout(40)
    void twoYoutubeSegsContinuityClearedByProbe() throws Exception {
        Path concat = Files.createTempFile("yt-two", ".ts");
        Files.write(concat, concatBytes(SEG0, SEG1));
        try {
            int raw = countContinuity(concat, false);
            assertTrue(raw > 0, "raw concat must warn CONTINUITY, got " + raw);
            int fixed = countContinuity(concat, true);
            assertEquals(0, fixed, "probe must clear CONTINUITY");
        } finally {
            Files.deleteIfExists(concat);
        }
    }

    private static byte[] concatBytes(Path a, Path b) throws Exception {
        byte[] ba = Files.readAllBytes(a);
        byte[] bb = Files.readAllBytes(b);
        byte[] out = new byte[ba.length + bb.length];
        System.arraycopy(ba, 0, out, 0, ba.length);
        System.arraycopy(bb, 0, out, ba.length, bb.length);
        return out;
    }

    private static int countContinuity(Path ts, boolean installFix) throws Exception {
        AtomicInteger continuity = new AtomicInteger();
        String loc = ts.toAbsolutePath().toString().replace('\\', '/');
        Pipeline pipe = (Pipeline) Gst.parseLaunch(
                "filesrc location=\"" + loc + "\" ! tsdemux name=d ! fakesink sync=false");
        Element demux = pipe.getElementByName("d");
        if (installFix) {
            new MpegTsContinuityFix().install(demux);
        }
        pipe.getBus().connect((Bus.WARNING) (source, code, message) -> {
            if (message != null && message.contains("CONTINUITY")) {
                continuity.incrementAndGet();
            }
        });
        pipe.play();
        Thread.sleep(4_000);
        pipe.stop();
        // Soft-stop only; disposing after a pad probe can abort later GST tests in this JVM.
        return continuity.get();
    }
}
