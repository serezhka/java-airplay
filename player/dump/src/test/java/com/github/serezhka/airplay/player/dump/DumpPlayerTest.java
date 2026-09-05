package com.github.serezhka.airplay.player.dump;

import com.github.serezhka.airplay.lib.AudioStreamInfo;
import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.server.ControlExchange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DumpPlayerTest {

    @TempDir
    Path tempDir;

    @Test
    void writesVideoBytesUnchanged() throws Exception {
        DumpPlayer player = player();
        byte[] video = {0, 0, 0, 1, 103, 42};

        player.onVideoFormat(new VideoStreamInfo("conn-1"));
        player.onVideo(video);
        player.onVideoSrcDisconnect();
        player.close();

        Path dumped = player.sessionDirectory().resolve("media").resolve("video-001.h264");
        assertArrayEquals(video, Files.readAllBytes(dumped));
    }

    @Test
    void rotatesVideoFileAfterDisconnect() throws Exception {
        DumpPlayer player = player();

        player.onVideoFormat(new VideoStreamInfo("conn-1"));
        player.onVideo(new byte[]{1});
        player.onVideoSrcDisconnect();
        player.onVideoFormat(new VideoStreamInfo("conn-1"));
        player.onVideo(new byte[]{2});
        player.onVideoSrcDisconnect();
        player.close();

        Path first = player.sessionDirectory().resolve("media").resolve("video-001.h264");
        Path second = player.sessionDirectory().resolve("media").resolve("video-002.h264");
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(first));
        assertArrayEquals(new byte[]{2}, Files.readAllBytes(second));
    }

    @Test
    void usesAudioExtensionFromCompressionType() throws Exception {
        DumpPlayer player = player();
        AudioStreamInfo alac = new AudioStreamInfo.AudioStreamInfoBuilder()
                .compressionType(AudioStreamInfo.CompressionType.ALAC)
                .audioFormat(AudioStreamInfo.AudioFormat.ALAC_44100_16_2)
                .samplesPerFrame(352)
                .build();

        player.onAudioFormat(alac);
        player.onAudio(new byte[]{9, 8, 7});
        player.onAudioSrcDisconnect();
        player.close();

        Path dumped = player.sessionDirectory().resolve("media").resolve("audio-001.caf");
        byte[] bytes = Files.readAllBytes(dumped);
        assertEquals("caff", new String(bytes, 0, 4, StandardCharsets.US_ASCII));
        assertArrayEquals(new byte[]{9, 8, 7},
                java.util.Arrays.copyOfRange(bytes, AlacCaf.PACKETS_OFFSET, AlacCaf.PACKETS_OFFSET + 3));
    }

    @Test
    void disabledFlagsCreateNoMediaOrProtocolFiles() throws Exception {
        DumpConfig config = config();
        config.setVideo(false);
        config.setAudio(false);
        config.setProtocol(false);
        config.setArtwork(false);
        DumpPlayer player = new DumpPlayer(config, Clock.fixed(Instant.parse("2026-09-05T12:00:00Z"), ZoneOffset.UTC));

        player.onVideoFormat(new VideoStreamInfo("conn-1"));
        player.onVideo(new byte[]{1});
        player.onAudioFormat(new AudioStreamInfo.AudioStreamInfoBuilder()
                .compressionType(AudioStreamInfo.CompressionType.AAC_ELD)
                .audioFormat(AudioStreamInfo.AudioFormat.AAC_ELD_44100_2)
                .samplesPerFrame(480)
                .build());
        player.onAudio(new byte[]{2});
        player.onControlExchange(exchange("image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8}));
        player.close();

        assertTrue(Files.exists(player.sessionDirectory().resolve("session.json")));
        assertFalse(Files.exists(player.sessionDirectory().resolve("media").resolve("video-001.h264")));
        assertFalse(Files.exists(player.sessionDirectory().resolve("media").resolve("audio-001.aac")));
        assertFalse(Files.exists(player.sessionDirectory().resolve("protocol").resolve("index.ndjson")));
        assertFalse(Files.exists(player.sessionDirectory().resolve("extras").resolve("artwork-001.jpg")));
    }

    @Test
    void writesProtocolCaptureAndArtwork() throws Exception {
        DumpPlayer player = player();
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3};

        player.onControlExchange(exchange("image/jpeg", jpeg));
        player.close();

        Path protocol = player.sessionDirectory().resolve("protocol");
        assertTrue(Files.exists(protocol.resolve("index.ndjson")));
        assertTrue(Files.exists(protocol.resolve("0001_SET_PARAMETER_artwork.request.bin")));
        assertArrayEquals(jpeg, Files.readAllBytes(protocol.resolve("0001_SET_PARAMETER_artwork.request.bin")));
        String index = Files.readString(protocol.resolve("index.ndjson"));
        assertTrue(index.contains("\"method\":\"SET_PARAMETER\""));
        assertTrue(index.contains("\"responseStatus\":200"));
        assertArrayEquals(jpeg, Files.readAllBytes(player.sessionDirectory().resolve("extras").resolve("artwork-001.jpg")));
        String session = Files.readString(player.sessionDirectory().resolve("session.json"));
        assertTrue(session.contains("session-42"));
    }

    @Test
    void savesPlaylistUri() throws Exception {
        DumpPlayer player = player();
        player.onMediaPlaylist("mlhls://localhost/master.m3u8");
        player.close();

        Path uriFile = player.sessionDirectory().resolve("extras").resolve("playlist-001.uri.txt");
        assertEquals("mlhls://localhost/master.m3u8", Files.readString(uriFile));
        assertTrue(Files.readString(player.sessionDirectory().resolve("session.json")).contains("mlhls://localhost/master.m3u8"));
    }

    @Test
    void savesPlaylistContentWithoutHttpFetch() throws Exception {
        DumpPlayer player = player();
        player.onMediaPlaylist("http://localhost:9/playlist/master.m3u8?session=x");
        player.onMediaPlaylistContent("mlhls://localhost/master.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nmediadata.m3u8\n");
        player.onMediaPlaylistContent("mlhls://localhost/mediadata.m3u8", "#EXTM3U\n#EXTINF:1.0,\nseg.ts\n");
        player.close();

        Path extras = player.sessionDirectory().resolve("extras");
        assertEquals("http://localhost:9/playlist/master.m3u8?session=x",
                Files.readString(extras.resolve("playlist-001.uri.txt")));
        assertTrue(Files.readString(extras.resolve("playlist-001-master.m3u8")).contains("#EXTM3U"));
        assertTrue(Files.readString(extras.resolve("playlist-002-media.m3u8")).contains("seg.ts"));
        String session = Files.readString(player.sessionDirectory().resolve("session.json"));
        assertTrue(session.contains("\"fps\": 60"));
    }

    private DumpPlayer player() {
        return new DumpPlayer(config(), Clock.fixed(Instant.parse("2026-09-05T12:00:00Z"), ZoneOffset.UTC));
    }

    private DumpConfig config() {
        DumpConfig config = new DumpConfig();
        config.setDirectory(tempDir.toString());
        return config;
    }

    private static ControlExchange exchange(String contentType, byte[] body) {
        return new ControlExchange(
                Instant.parse("2026-09-05T12:00:00Z"),
                "session-42",
                "RTSP/1.0",
                "SET_PARAMETER",
                "/artwork",
                Map.of("Content-Type", contentType, "CSeq", "10"),
                body,
                200,
                Map.of("Audio-Jack-Status", "connected; type=analog"),
                "ok".getBytes(StandardCharsets.US_ASCII));
    }
}
