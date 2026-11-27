package com.github.serezhka.airplay.server.internal.handler.session;

import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HlsCdnProxyTest {

    @Test
    void rewriteLeavesRelativeFixtureSegmentsAlone() {
        String body = """
                #EXTM3U
                #EXTINF:1.0,
                seg000.ts
                """;
        String out = HlsCdnProxy.rewritePlaylistForProxy(
                body, "https://cdn.invalid/", "http://127.0.0.1:9/playlist/cdn?session=s&u=");
        assertTrue(out.contains("seg000.ts"));
        assertFalse(out.contains("/cdn?"));
    }

    @Test
    void rewriteWrapsHttpsSegmentsLeavesLocal() {
        String body = """
                #EXTM3U
                #EXTINF:5.0,
                https://rr1---sn-example.googlevideo.com/videoplayback/seg0
                #EXTINF:3.0,
                http://127.0.0.1:9/playlist/cdn?session=s&u=already
                """;
        String prefix = "http://127.0.0.1:9/playlist/cdn?session=s&u=";
        String out = HlsCdnProxy.rewritePlaylistForProxy(body, "https://cdn.invalid/", prefix);

        List<String> cdnLines = out.lines()
                .filter(l -> l.startsWith(prefix))
                .filter(l -> {
                    String u = URLDecoder.decode(l.substring(prefix.length()), StandardCharsets.UTF_8);
                    return u.startsWith("https://");
                })
                .toList();
        assertEquals(1, cdnLines.size());
        String upstream = URLDecoder.decode(cdnLines.getFirst().substring(prefix.length()), StandardCharsets.UTF_8);
        assertTrue(upstream.contains("googlevideo.com/videoplayback/seg0"));
        assertTrue(out.contains("http://127.0.0.1:9/playlist/cdn?session=s&u=already"));
        assertFalse(HlsCdnProxy.shouldProxy("http://127.0.0.1:9/x"));
        assertTrue(HlsCdnProxy.shouldProxy("https://rr1---sn-example.googlevideo.com/x"));
    }

    @Test
    void looksLikePlaylistSniffsExtm3uNotUrl() {
        assertTrue(HlsCdnProxy.looksLikePlaylist("application/octet-stream",
                "#EXTM3U\n#EXTINF:1,\nseg".getBytes(StandardCharsets.UTF_8)));
        assertFalse(HlsCdnProxy.looksLikePlaylist("application/octet-stream",
                new byte[]{0x47, 0x40, 0x00, 0x10}));
    }

    @Test
    void proxyPrefixShape() {
        assertEquals("http://127.0.0.1:7000/playlist/cdn?session=abc&u=",
                HlsCdnProxy.proxyPrefix("http://127.0.0.1:7000/playlist", "abc"));
    }

    @Test
    void allowedUpstreamHttpsAndLoopbackHttp() {
        assertTrue(HlsCdnProxy.isAllowedUpstream("https://cdn.example/seg.ts"));
        assertTrue(HlsCdnProxy.isAllowedUpstream("http://127.0.0.1:9/x"));
        assertFalse(HlsCdnProxy.isAllowedUpstream("http://evil.example/x"));
        assertFalse(HlsCdnProxy.isAllowedUpstream("file:///tmp/x"));
    }
}
