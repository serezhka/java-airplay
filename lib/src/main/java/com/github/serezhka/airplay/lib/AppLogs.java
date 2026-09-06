package com.github.serezhka.airplay.lib;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class AppLogs {

    static {
        try {
            Files.createDirectories(logsDirectory());
        } catch (IOException ignored) {
            // best effort; callers retry when opening files
        }
    }

    private AppLogs() {
    }

    public static Path logsDirectory() {
        return Path.of(System.getProperty("airplay.logs.directory", "logs"));
    }

    public static Path playerLogFile(String playerName) {
        String configured = System.getProperty("airplay." + playerName + ".debug.file");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return logsDirectory().resolve(playerName + ".log");
    }

    public static void configureProcessLogging(ProcessBuilder processBuilder, String playerName) throws IOException {
        Path logFile = playerLogFile(playerName);
        Files.createDirectories(logFile.getParent() == null ? Path.of(".") : logFile.getParent());
        processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        processBuilder.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
    }

    public static PrintStream openAppendPrintStream(String playerName) throws IOException {
        Path logFile = playerLogFile(playerName);
        Files.createDirectories(logFile.getParent() == null ? Path.of(".") : logFile.getParent());
        OutputStream outputStream = Files.newOutputStream(logFile,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return new PrintStream(outputStream, true, StandardCharsets.UTF_8);
    }
}
