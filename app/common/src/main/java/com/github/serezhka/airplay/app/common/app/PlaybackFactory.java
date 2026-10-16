package com.github.serezhka.airplay.app.common.app;

import com.github.serezhka.airplay.app.common.dump.DumpConfig;
import com.github.serezhka.airplay.app.common.dump.DumpPlayer;
import com.github.serezhka.airplay.app.common.dump.DumpingAirPlayConsumer;
import com.github.serezhka.airplay.server.Playback;

public final class PlaybackFactory {

    private PlaybackFactory() {
    }

    public static Playback withDump(Playback player, DumpConfig dumpConfig) {
        if (!dumpConfig.isEnabled()) {
            return player;
        }
        return new DumpingAirPlayConsumer(player, new DumpPlayer(dumpConfig));
    }
}
