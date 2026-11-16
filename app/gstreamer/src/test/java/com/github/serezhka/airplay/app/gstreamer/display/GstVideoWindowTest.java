package com.github.serezhka.airplay.app.gstreamer.display;

import com.github.serezhka.airplay.app.common.support.PlayerWindowDefaults;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.swing.JFrame;
import javax.swing.JPanel;
import java.awt.GraphicsEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GstVideoWindowTest {

    @Test
    void fullscreenFrameIsUndecoratedAndFixed() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "needs display");
        JFrame frame = GstVideoWindow.create(new JPanel(), true);
        try {
            assertTrue(frame.isUndecorated());
            assertFalse(frame.isResizable());
            assertTrue(frame.isAlwaysOnTop());
        } finally {
            GstVideoWindow.hide(frame);
            frame.dispose();
        }
    }

    @Test
    void windowedFrameIsDecoratedResizableAndDefaultSized() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "needs display");
        JFrame frame = GstVideoWindow.create(new JPanel(), false);
        try {
            assertFalse(frame.isUndecorated());
            assertTrue(frame.isResizable());
            assertFalse(frame.isAlwaysOnTop());
            assertEquals(PlayerWindowDefaults.WIDTH, frame.getWidth());
            assertEquals(PlayerWindowDefaults.HEIGHT, frame.getHeight());
            assertEquals("JavaAirPlay", frame.getTitle());
        } finally {
            GstVideoWindow.hide(frame);
            frame.dispose();
        }
    }
}
