package com.github.serezhka.airplay.player.gstreamer;

import javax.swing.*;
import java.awt.*;

final class GstFullscreenWindow {

    static JFrame create(Component content) {
        JFrame window = new JFrame();
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

    static void show(JFrame window) {
        onEdt(() -> {
            window.setBounds(screenBounds());
            window.setVisible(true);
            window.toFront();
        });
    }

    static void hide(JFrame window) {
        onEdt(() -> window.setVisible(false));
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
        return GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice()
                .getDefaultConfiguration()
                .getBounds();
    }

    private GstFullscreenWindow() {
    }
}
