package com.github.serezhka.airplay.app.mse;

import com.github.serezhka.airplay.app.common.support.PlayerWindowDefaults;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Player Chromium must open fullscreen when headed (GST/FFmpeg parity), stay
 * headless under CI ({@code airplay.mse.headless=true}), and use a sized window
 * when {@code airplay.fullscreen=false}.
 */
class ChromiumHostLaunchCommandTest {

    @TempDir
    Path temp;

    @Test
    void headedLaunchUsesStartFullscreenNotHeadless() {
        List<String> cmd = ChromiumHost.buildLaunchCommand(
                "chrome", temp.resolve("ud"), "http://127.0.0.1:9/", false, true);
        assertTrue(cmd.contains("--start-fullscreen"), cmd.toString());
        assertFalse(cmd.contains("--headless=new"), cmd.toString());
        assertTrue(cmd.stream().anyMatch(a -> a.startsWith("--app=")), cmd.toString());
    }

    @Test
    void headedWindowedUsesWindowSizeNotFullscreen() {
        List<String> cmd = ChromiumHost.buildLaunchCommand(
                "chrome", temp.resolve("ud"), "http://127.0.0.1:9/", false, false);
        assertFalse(cmd.contains("--start-fullscreen"), cmd.toString());
        assertFalse(cmd.contains("--headless=new"), cmd.toString());
        assertTrue(cmd.contains("--window-size=" + PlayerWindowDefaults.WIDTH + ","
                + PlayerWindowDefaults.HEIGHT), cmd.toString());
        assertTrue(cmd.stream().anyMatch(a -> a.startsWith("--app=")), cmd.toString());
    }

    @Test
    void headlessLaunchSkipsFullscreen() {
        List<String> cmd = ChromiumHost.buildLaunchCommand(
                "chrome", temp.resolve("ud"), "http://127.0.0.1:9/", true, true);
        assertTrue(cmd.contains("--headless=new"), cmd.toString());
        assertFalse(cmd.contains("--start-fullscreen"), cmd.toString());
    }
}
