package com.github.serezhka.airplay.app.mse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Player Chromium must open fullscreen when headed (GST/FFmpeg parity) and stay
 * headless under CI ({@code airplay.mse.headless=true}).
 */
class ChromiumHostLaunchCommandTest {

    @TempDir
    Path temp;

    @Test
    void headedLaunchUsesStartFullscreenNotHeadless() {
        List<String> cmd = ChromiumHost.buildLaunchCommand(
                "chrome", temp.resolve("ud"), "http://127.0.0.1:9/", false);
        assertTrue(cmd.contains("--start-fullscreen"), cmd.toString());
        assertFalse(cmd.contains("--headless=new"), cmd.toString());
        assertTrue(cmd.stream().anyMatch(a -> a.startsWith("--app=")), cmd.toString());
    }

    @Test
    void headlessLaunchSkipsFullscreen() {
        List<String> cmd = ChromiumHost.buildLaunchCommand(
                "chrome", temp.resolve("ud"), "http://127.0.0.1:9/", true);
        assertTrue(cmd.contains("--headless=new"), cmd.toString());
        assertFalse(cmd.contains("--start-fullscreen"), cmd.toString());
    }
}
