package com.github.serezhka.airplay.lib;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;

/**
 * @deprecated use {@link AppLogs}
 */
@Deprecated
public final class PlayerProcessLog {

    private PlayerProcessLog() {
    }

    public static Path debugFile(String playerName) {
        return AppLogs.playerLogFile(playerName);
    }

    public static void configureProcessLogging(ProcessBuilder processBuilder, String playerName) throws IOException {
        AppLogs.configureProcessLogging(processBuilder, playerName);
    }

    public static PrintStream openAppendPrintStream(String playerName) throws IOException {
        return AppLogs.openAppendPrintStream(playerName);
    }
}
