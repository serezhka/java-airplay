package com.github.serezhka.airplay.app.mse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Locate a system Chromium/Chrome/Edge binary for MSE playback and browser cast.
 * Honours {@code -Dairplay.mse.browser=} / {@code -Dairplay.mse.cast.browser=}.
 */
public final class MseBrowsers {

    private MseBrowsers() {
    }

    /** Resolve using cast then playback property overrides. */
    public static String resolveForCast() {
        return resolve("airplay.mse.cast.browser", "airplay.mse.browser");
    }

    /** Resolve using the playback-host property only. */
    public static String resolveForPlayback() {
        return resolve("airplay.mse.browser");
    }

    public static String resolve(String... propertyKeys) {
        for (String key : propertyKeys) {
            String override = System.getProperty(key);
            if (override != null && !override.isBlank()) {
                return override.trim();
            }
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            for (String candidate : new String[]{
                    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                    "/Applications/Chromium.app/Contents/MacOS/Chromium",
                    "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"
            }) {
                if (isUsable(candidate)) {
                    return candidate;
                }
            }
        } else if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            String[] candidates = {
                    local == null ? null : local + "\\Google\\Chrome\\Application\\chrome.exe",
                    "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                    "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
                    local == null ? null : local + "\\Microsoft\\Edge\\Application\\msedge.exe",
                    "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
                    "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe"
            };
            for (String candidate : candidates) {
                if (candidate != null && isUsable(candidate)) {
                    return candidate;
                }
            }
        }
        for (String candidate : new String[]{
                "google-chrome-stable", "google-chrome", "chrome",
                "chromium", "chromium-browser", "msedge"
        }) {
            if (onPath(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    public static boolean isUsable(String binary) {
        if (binary == null || binary.isBlank()) {
            return false;
        }
        Path p = Path.of(binary);
        if (p.isAbsolute()) {
            return Files.isRegularFile(p) && (Files.isExecutable(p) || isWindows());
        }
        return onPath(binary);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static boolean onPath(String binary) {
        String finder = isWindows() ? "where" : "which";
        try {
            Process p = new ProcessBuilder(finder, binary).redirectErrorStream(true).start();
            boolean ok = p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0;
            p.destroyForcibly();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }
}
