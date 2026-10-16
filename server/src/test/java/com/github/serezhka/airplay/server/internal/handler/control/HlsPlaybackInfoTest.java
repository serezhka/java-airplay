package com.github.serezhka.airplay.server.internal.handler.control;

import com.github.serezhka.airplay.server.Playback;
import com.github.serezhka.airplay.server.internal.handler.session.HlsPlaylistState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HlsPlaybackInfoTest {

    @Test
    void idleWithoutHlsPassesPlayerClockThrough() {
        var info = HlsPlaybackInfo.resolve(null, new Playback.Info(12.5, 3.0, 1));
        assertEquals(12.5, info.duration(), 0.001);
        assertEquals(3.0, info.position(), 0.001);
        assertEquals(1.0, info.rate(), 0.001);
    }

    @Test
    void clampsAbsurdPlayerDurationWithoutHls() {
        var info = HlsPlaybackInfo.resolve(null, new Playback.Info(900, 1.0, 1));
        assertEquals(0, info.duration(), 0.001);
    }

    @Test
    void vodEndPinUsesDurationAtEndWithRateOne() {
        var hls = new HlsPlaylistState(
                "mlhls://localhost/master.m3u8",
                "http://127.0.0.1/playlist/master.m3u8?session=x");
        hls.putPlaylist("mlhls://localhost/itag/136/mediadata.m3u8", """
                #EXTM3U
                #EXTINF:6.0,
                a.ts
                #EXT-X-ENDLIST
                """);
        hls.setWaitingForMasterChange(true);

        var info = HlsPlaybackInfo.resolve(hls, new Playback.Info(6.0, 5.9, 1));
        assertEquals(6.0, info.duration(), 0.001);
        assertEquals(6.0, info.position(), 0.001);
        assertEquals(1.0, info.rate(), 0.001);
    }

    @Test
    void respectsHlsPauseRateWhilePlaying() {
        var hls = new HlsPlaylistState(
                "mlhls://localhost/master.m3u8",
                "http://127.0.0.1/playlist/master.m3u8?session=x");
        hls.putPlaylist("mlhls://localhost/itag/136/mediadata.m3u8", """
                #EXTM3U
                #EXTINF:10.0,
                a.ts
                #EXT-X-ENDLIST
                """);
        hls.setPlaybackRate(0);

        var info = HlsPlaybackInfo.resolve(hls, new Playback.Info(10.0, 4.0, 1));
        assertEquals(10.0, info.duration(), 0.001);
        assertEquals(4.0, info.position(), 0.001);
        assertEquals(0.0, info.rate(), 0.001);
    }
}
