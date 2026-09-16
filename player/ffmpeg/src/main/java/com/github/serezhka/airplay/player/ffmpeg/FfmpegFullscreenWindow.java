package com.github.serezhka.airplay.player.ffmpeg;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Component;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;

/**
 * Exclusive fullscreen host for HLS frames (same pattern as the GStreamer player window).
 */
final class FfmpegFullscreenWindow {

    static JFrame create(Component content) {
        JFrame window = new JFrame("AirPlay HLS");
        window.setUndecorated(true);
        window.setAlwaysOnTop(true);
        window.setResizable(false);
        window.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        window.getContentPane().setBackground(Color.BLACK);
        window.getContentPane().setLayout(new BorderLayout());
        window.getContentPane().add(content, BorderLayout.CENTER);
        window.setBounds(screenBounds());
        return window;
    }

    static Canvas createVideoCanvas() {
        Canvas canvas = new Canvas();
        canvas.setBackground(Color.BLACK);
        canvas.setIgnoreRepaint(true);
        return canvas;
    }

    static void show(JFrame window) {
        onEdt(() -> {
            GraphicsDevice screen = defaultScreen();
            window.setBounds(screen.getDefaultConfiguration().getBounds());
            window.setVisible(true);
            screen.setFullScreenWindow(window);
            window.toFront();
        });
    }

    static void hide(JFrame window) {
        if (window == null) {
            return;
        }
        onEdt(() -> {
            GraphicsDevice screen = defaultScreen();
            if (screen.getFullScreenWindow() == window) {
                screen.setFullScreenWindow(null);
            }
            window.setVisible(false);
            window.dispose();
        });
    }

    static void onEdt(Runnable action) {
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

    private static Rectangle screenBounds() {
        return defaultScreen().getDefaultConfiguration().getBounds();
    }

    private static GraphicsDevice defaultScreen() {
        return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
    }

    private FfmpegFullscreenWindow() {
    }
}
