package com.github.serezhka.airplay.server.internal.handler.control;

import io.lindstrom.m3u8.parser.MediaPlaylistParser;
import io.lindstrom.m3u8.parser.ParsingMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HlsCondensedUrlsTest {

    @Test
    void expandsYtCondensedSegmentUris() throws Exception {
        String body = """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #YT-EXT-CONDENSED-URL:PREFIX="sg-",PARAMS="a,b",BASE-URI="https://cdn.example/x"
                #EXTINF:5.0,
                sg-1/2
                #EXTINF:5.0,
                sg-3/4
                #EXT-X-ENDLIST
                """;
        var parser = new MediaPlaylistParser(ParsingMode.LENIENT);
        var expanded = HlsCondensedUrls.expand(parser.readPlaylist(body));
        assertEquals(2, expanded.mediaSegments().size());
        assertEquals("https://cdn.example/x/a/1/b/2", expanded.mediaSegments().get(0).uri());
        assertEquals("https://cdn.example/x/a/3/b/4", expanded.mediaSegments().get(1).uri());
    }

    @Test
    void leavesPlaylistUnchangedWithoutCondensedTag() throws Exception {
        String body = """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXTINF:5.0,
                https://cdn.example/seg0.ts
                #EXT-X-ENDLIST
                """;
        var parser = new MediaPlaylistParser(ParsingMode.LENIENT);
        var playlist = parser.readPlaylist(body);
        var expanded = HlsCondensedUrls.expand(playlist);
        assertEquals(playlist.mediaSegments().get(0).uri(), expanded.mediaSegments().get(0).uri());
        assertTrue(expanded.mediaSegments().get(0).uri().startsWith("https://"));
    }
}
