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
        assertFalse(CastCdpSession.matchesResponseId("{\"method\":\"Runtime.bindingCalled\"}", 1));
    }

    @Test
    void extractsEscapedBindingPayloadFromCdpEvent() {
        // Chrome sends payload as a JSON string; inner quotes are backslash-escaped.
        String cdp = "{\"method\":\"Runtime.bindingCalled\",\"params\":{"
                + "\"name\":\"airplayCast\","
                + "\"payload\":\"{\\\"t\\\":\\\"e\\\",\\\"event\\\":\\\"ready\\\",\\\"message\\\":\\\"\\\"}\","
                + "\"executionContextId\":1}}";
        String payload = CastCdpSession.extractBindingPayload(cdp);
        assertEquals("{\"t\":\"e\",\"event\":\"ready\",\"message\":\"\"}", payload);
        CastBindingCodec.Message msg = CastBindingCodec.parse(payload);
        assertTrue(msg instanceof CastBindingCodec.Event);
        assertEquals("ready", ((CastBindingCodec.Event) msg).kind());
    }

    @Test
    void escapedCdpVideoPayloadRoundTripsAnnexB() {
        byte[] annex = new byte[]{0, 0, 0, 1, 0x67, 0x42, 0x00, 0x0a};
        String b64 = java.util.Base64.getEncoder().encodeToString(annex);
        String inner = "{\"t\":\"v\",\"b\":\"" + b64 + "\"}";
        String escaped = CastBindingCodec.jsonEscape(inner);
        String cdp = "{\"method\":\"Runtime.bindingCalled\",\"params\":{"
                + "\"name\":\"airplayCast\",\"payload\":\"" + escaped + "\"}}";
        String payload = CastCdpSession.extractBindingPayload(cdp);
        CastBindingCodec.Message msg = CastBindingCodec.parse(payload);
        assertTrue(msg instanceof CastBindingCodec.VideoAnnexB);
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                annex, ((CastBindingCodec.VideoAnnexB) msg).annexB());
    }

    @Test
    void naiveQuoteCutWouldCorruptEscapedPayload() {
        String cdp = "{\"params\":{\"payload\":\"{\\\"t\\\":\\\"v\\\"}\"}}";
        // Bug we hit in production: first " after payload": truncates to "{".
        int start = cdp.indexOf("\"payload\":\"") + "\"payload\":\"".length();
        String naive = cdp.substring(start, cdp.indexOf('"', start));
        assertEquals("{\\", naive);
        assertNull(CastBindingCodec.extractJsonString(naive, "t"));
    }
}
