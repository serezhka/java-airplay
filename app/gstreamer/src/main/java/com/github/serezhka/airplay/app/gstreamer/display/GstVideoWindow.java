package com.github.serezhka.airplay.app.gstreamer.display;

import com.github.serezhka.airplay.app.common.support.PlayerWindowDefaults;

import javax.swing.*;
import java.awt.*;

/**
 * Swing host for GStreamer VideoOverlay / appsink: exclusive fullscreen or a
 * decorated resizable window ({@code airplay.fullscreen}).
 */
public final class GstVideoWindow {

    public static JFrame create(Component content, boolean fullscreen) {
        JFrame window = new JFrame(fullscreen ? "" : "JavaAirPlay");
        window.setUndecorated(fullscreen);
        window.setAlwaysOnTop(fullscreen);
        window.setResizable(!fullscreen);
        window.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        window.getContentPane().setBackground(Color.BLACK);
        window.getContentPane().setLayout(new BorderLayout());
        window.getContentPane().add(content, BorderLayout.CENTER);
        if (fullscreen) {
            window.setBounds(screenBounds());
        } else {
            window.setSize(PlayerWindowDefaults.WIDTH, PlayerWindowDefaults.HEIGHT);
            window.setLocationRelativeTo(null);
        }
        return window;
    }

    public static void show(JFrame window, boolean fullscreen) {
        onEdt(() -> {
            GraphicsDevice screen = defaultScreen();
            if (fullscreen) {
                window.setBounds(screen.getDefaultConfiguration().getBounds());
                window.setVisible(true);
                screen.setFullScreenWindow(window);
            } else {
                if (screen.getFullScreenWindow() == window) {
                    clearExclusiveFullscreen(screen);
                }
                if (!window.isVisible()) {
                    window.setVisible(true);
                }
            }
            window.toFront();
        });
    }

    public static void hide(JFrame window) {
        onEdt(() -> {
            GraphicsDevice screen = defaultScreen();
            if (screen.getFullScreenWindow() == window) {
                clearExclusiveFullscreen(screen);
            }
            window.setVisible(false);
            window.toBack();
        });
    }

    public static void onEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void clearExclusiveFullscreen(GraphicsDevice screen) {
        try {
            // Some displays reject exclusive fullscreen teardown; still hide.
            screen.setFullScreenWindow(null);
        } catch (IllegalArgumentException ignored) {
            // fall through to setVisible(false)
        }
    }

    private static Rectangle screenBounds() {
        return defaultScreen().getDefaultConfiguration().getBounds();
    }

    private static GraphicsDevice defaultScreen() {
        return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
    }

    private GstVideoWindow() {
    }
}
