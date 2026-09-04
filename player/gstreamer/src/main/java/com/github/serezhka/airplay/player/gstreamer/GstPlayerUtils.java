/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2021 Neil C Smith - Codelerity Ltd.
 *
 * Copying and distribution of this file, with or without modification,
 * are permitted in any medium without royalty provided the copyright
 * notice and this notice are preserved. This file is offered as-is,
 * without any warranty.
 *
 */
package com.github.serezhka.airplay.player.gstreamer;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Kernel32;

import java.io.File;
import java.util.stream.Stream;

/**
 * Utility methods for use in examples.
 */
class GstPlayerUtils {

    private static final String[] LIBRARY_DIRS = {
            "/Library/Frameworks/GStreamer.framework/Libraries",
            "/opt/homebrew/lib",
            "/usr/local/lib",
            "/usr/lib",
            "/usr/lib64",
            "/usr/lib/x86_64-linux-gnu",
            "/usr/lib/aarch64-linux-gnu",
            "/usr/lib/arm-linux-gnueabihf",
            "C:\\gstreamer\\1.0\\msvc_x86_64\\bin",
            "C:\\gstreamer\\1.0\\mingw_x86_64\\bin",
            "C:\\Program Files\\gstreamer\\1.0\\msvc_x86_64\\bin",
    };

    private GstPlayerUtils() {
    }

    /**
     * Configures paths to the GStreamer libraries. On Windows queries various
     * GStreamer environment variables, and then sets up the PATH environment
     * variable. On macOS, adds the location to jna.library.path (macOS binaries
     * link to each other). On both, the gstreamer.path system property can be
     * used to override. On Linux, assumes GStreamer is in the path already.
     */
    static void configurePaths() {
        String customPath = System.getProperty("gstreamer.path", "").trim();
        if (Platform.isWindows()) {
            String gstPath = customPath;
            if (gstPath.isEmpty()) {
                gstPath = findWindowsLocation();
            }
            if (gstPath.isEmpty()) {
                for (String dir : LIBRARY_DIRS) {
                    if (new File(dir).isDirectory()) {
                        gstPath = dir.endsWith("\\") ? dir : dir + "\\";
                        break;
                    }
                }
            }
            if (!gstPath.isEmpty()) {
                String systemPath = System.getenv("PATH");
                if (systemPath == null || systemPath.trim().isEmpty()) {
                    Kernel32.INSTANCE.SetEnvironmentVariable("PATH", gstPath);
                } else {
                    Kernel32.INSTANCE.SetEnvironmentVariable("PATH", gstPath
                            + File.pathSeparator + systemPath);
                }
            }
        } else {
            StringBuilder jnaPath = new StringBuilder(System.getProperty("jna.library.path", "").trim());
            if (!customPath.isEmpty()) {
                if (jnaPath.length() > 0) {
                    jnaPath.append(File.pathSeparator);
                }
                jnaPath.append(customPath);
            } else {
                for (String dir : LIBRARY_DIRS) {
                    if (new File(dir).isDirectory()) {
                        if (jnaPath.length() > 0) {
                            jnaPath.append(File.pathSeparator);
                        }
                        jnaPath.append(dir);
                    }
                }
            }
            if (jnaPath.length() > 0) {
                System.setProperty("jna.library.path", jnaPath.toString());
            }
        }
    }

    /**
     * Query over a stream of possible environment variables for GStreamer
     * location, filtering on the first non-null result, and adding \bin\ to the
     * value.
     *
     * @return location or empty string
     */
    static String findWindowsLocation() {
        if (Platform.is64Bit()) {
            return Stream.of("GSTREAMER_1_0_ROOT_MSVC_X86_64",
                            "GSTREAMER_1_0_ROOT_MINGW_X86_64",
                            "GSTREAMER_1_0_ROOT_X86_64")
                    .map(System::getenv)
                    .filter(p -> p != null)
                    .map(p -> p.endsWith("\\") ? p + "bin\\" : p + "\\bin\\")
                    .findFirst().orElse("");
        } else {
            return "";
        }
    }
}
