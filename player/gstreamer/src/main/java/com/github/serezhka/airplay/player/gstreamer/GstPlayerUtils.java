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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Utility methods for use in examples.
 */
class GstPlayerUtils {

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
        if (Platform.isWindows()) {
            String gstPath = System.getProperty("gstreamer.path", findWindowsLocation());
            if (!gstPath.isEmpty()) {
                prependEnvironmentVariable("PATH", gstPath);
                configureBundledWindowsEnvironment(gstPath);
            }
        } else if (Platform.isMac()) {
            String gstPath = System.getProperty("gstreamer.path",
                    "/Library/Frameworks/GStreamer.framework/Libraries/");
            if (!gstPath.isEmpty()) {
                String jnaPath = System.getProperty("jna.library.path", "").trim();
                if (jnaPath.isEmpty()) {
                    System.setProperty("jna.library.path", gstPath);
                } else {
                    System.setProperty("jna.library.path", jnaPath + File.pathSeparator + gstPath);
                }
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
            String bundledLocation = findBundledWindowsLocation();
            if (!bundledLocation.isEmpty()) {
                return bundledLocation;
            }

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

    private static String findBundledWindowsLocation() {
        return Stream.of(
                        Path.of(System.getProperty("user.dir"), "gstreamer", "bin"),
                        Path.of(System.getProperty("user.dir"), "..", "gstreamer", "bin").normalize(),
                        Path.of(System.getProperty("java.home"), "..", "gstreamer", "bin").normalize())
                .filter(Files::isDirectory)
                .map(Path::toString)
                .findFirst()
                .orElse("");
    }

    private static void configureBundledWindowsEnvironment(String gstBinPath) {
        Path bin = Path.of(gstBinPath);
        Path root = bin.getParent();
        if (root == null || !Files.isDirectory(root.resolve("lib").resolve("gstreamer-1.0"))) {
            return;
        }

        setEnvironmentVariableIfEmpty("GST_PLUGIN_PATH", root.resolve("lib").resolve("gstreamer-1.0").toString());

        Path pluginScanner = root.resolve("libexec").resolve("gstreamer-1.0").resolve("gst-plugin-scanner.exe");
        if (Files.isRegularFile(pluginScanner)) {
            setEnvironmentVariableIfEmpty("GST_PLUGIN_SCANNER", pluginScanner.toString());
        }
    }

    private static void prependEnvironmentVariable(String name, String value) {
        String existingValue = System.getenv(name);
        if (existingValue == null || existingValue.trim().isEmpty()) {
            Kernel32.INSTANCE.SetEnvironmentVariable(name, value);
        } else {
            Kernel32.INSTANCE.SetEnvironmentVariable(name, value + File.pathSeparator + existingValue);
        }
    }

    private static void setEnvironmentVariableIfEmpty(String name, String value) {
        String existingValue = System.getenv(name);
        if (existingValue == null || existingValue.trim().isEmpty()) {
            Kernel32.INSTANCE.SetEnvironmentVariable(name, value);
        }
    }
}
