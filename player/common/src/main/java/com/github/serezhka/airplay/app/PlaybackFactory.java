package com.github.serezhka.airplay.app;

import com.github.serezhka.airplay.player.dump.DumpConfig;
import com.github.serezhka.airplay.player.dump.DumpPlayer;
import com.github.serezhka.airplay.player.dump.DumpingAirPlayConsumer;
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
