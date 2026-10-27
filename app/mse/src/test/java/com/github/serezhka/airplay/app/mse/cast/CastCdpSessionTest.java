package com.github.serezhka.airplay.app.mse.cast;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CastCdpSessionTest {

    @Test
    void extractsPageWebSocketPreferringTypePage() {
        String body = """
                [ {
                   "description": "",
                   "type": "background_page",
                   "webSocketDebuggerUrl": "ws://127.0.0.1:9/devtools/page/BG"
                }, {
                   "description": "",
                   "type": "page",
                   "url": "about:blank",
                   "webSocketDebuggerUrl": "ws://127.0.0.1:9/devtools/page/TAB"
                } ]
                """;
        assertEquals("ws://127.0.0.1:9/devtools/page/TAB", CastCdpSession.extractPageWebSocketUrl(body));
    }

    @Test
    void matchesResponseIdDoesNotPrefixMatch() {
        assertTrue(CastCdpSession.matchesResponseId("{\"id\":1,\"result\":{}}", 1));
        assertTrue(CastCdpSession.matchesResponseId("{\"id\":10,\"result\":{}}", 10));
        assertFalse(CastCdpSession.matchesResponseId("{\"id\":10,\"result\":{}}", 1));
        assertFalse(CastCdpSession.matchesResponseId("{\"method\":\"Page.screencastFrame\"}", 1));
    }

    @Test
    void extractsNumericScreencastSessionId() {
        String frame = "{\"method\":\"Page.screencastFrame\",\"params\":{\"data\":\"abc\",\"sessionId\":7}}";
        assertEquals(7, CastCdpSession.extractScreencastSessionId(frame));
        assertNull(CastCdpSession.extractScreencastSessionId(
                "{\"method\":\"Page.screencastFrame\",\"params\":{\"data\":\"abc\"},\"sessionId\":\"UUID\"}"));
    }
}
