package com.github.serezhka.airplay.app.ffmpeg.process;

import com.github.serezhka.airplay.app.common.support.PlayerWindowDefaults;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfplayProcessDisplayModeTest {

    @Test
    void headedFullscreenUsesFs() {
        List<String> cmd = new ArrayList<>();
        FfplayProcess.appendDisplayMode(cmd, false, true);
        assertEquals(List.of("-fs"), cmd);
    }

    @Test
    void headedWindowedUsesXySize() {
        List<String> cmd = new ArrayList<>();
        FfplayProcess.appendDisplayMode(cmd, false, false);
        assertEquals(List.of(
                "-x", String.valueOf(PlayerWindowDefaults.WIDTH),
                "-y", String.valueOf(PlayerWindowDefaults.HEIGHT)), cmd);
        assertFalse(cmd.contains("-fs"));
        assertFalse(cmd.contains("-nodisp"));
    }

    @Test
    void headlessIgnoresFullscreenFlag() {
        List<String> cmd = new ArrayList<>();
        FfplayProcess.appendDisplayMode(cmd, true, true);
        assertEquals(List.of("-nodisp"), cmd);
        cmd.clear();
        FfplayProcess.appendDisplayMode(cmd, true, false);
        assertEquals(List.of("-nodisp"), cmd);
        assertTrue(cmd.contains("-nodisp"));
    }
}
