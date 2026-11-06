package com.github.serezhka.airplay.app.mse.cast;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CastBindingCodecTest {

    @Test
    void parsesVideoAnnexB() {
        byte[] raw = new byte[] {0, 0, 0, 1, 0x67, 0x42};
        String b64 = Base64.getEncoder().encodeToString(raw);
        CastBindingCodec.Message msg = CastBindingCodec.parse("{\"t\":\"v\",\"b\":\"" + b64 + "\"}");
        assertInstanceOf(CastBindingCodec.VideoAnnexB.class, msg);
        assertArrayEquals(raw, ((CastBindingCodec.VideoAnnexB) msg).annexB());
    }

    @Test
    void parsesAudioAdts() {
        byte[] adts = new byte[] {(byte) 0xff, (byte) 0xf1, 0x50};
        String b64 = Base64.getEncoder().encodeToString(adts);
        CastBindingCodec.Message msg = CastBindingCodec.parse("{\"t\":\"a\",\"b\":\"" + b64 + "\"}");
        assertInstanceOf(CastBindingCodec.AudioAdts.class, msg);
        assertArrayEquals(adts, ((CastBindingCodec.AudioAdts) msg).adtsFrame());
    }

    @Test
    void parsesEvent() {
        CastBindingCodec.Message msg =
                CastBindingCodec.parse("{\"t\":\"e\",\"event\":\"log\",\"message\":\"hello\"}");
        assertInstanceOf(CastBindingCodec.Event.class, msg);
        CastBindingCodec.Event ev = (CastBindingCodec.Event) msg;
        assertEquals("log", ev.kind());
        assertEquals("hello", ev.detail());
    }

    @Test
    void rejectsUnknownType() {
        assertThrows(IllegalArgumentException.class, () -> CastBindingCodec.parse("{\"t\":\"z\"}"));
    }

    @Test
    void injectScriptContainsBindingPlaceholder() throws Exception {
        String js = BrowserCastSession.loadInjectScriptForTest();
        assertEquals(CastBindingCodec.BINDING_NAME, "airplayCast");
        org.junit.jupiter.api.Assertions.assertTrue(js.contains(CastBindingCodec.BINDING_NAME));
        org.junit.jupiter.api.Assertions.assertFalse(js.contains("__VIDEO_WS__"));
        org.junit.jupiter.api.Assertions.assertTrue(js.contains("var ENABLE_AUDIO = false"));
        org.junit.jupiter.api.Assertions.assertTrue(js.contains("var ALLOW_FAKE_AUDIO = false"));
    }

    @Test
    void injectScriptEnablesAudioAndFakeFlagsForCi() throws Exception {
        System.setProperty("airplay.mse.cast.autoCapture", "true");
        try {
            String js = BrowserCastSession.loadInjectScriptForTest(true);
            org.junit.jupiter.api.Assertions.assertTrue(js.contains("var ENABLE_AUDIO = true"));
            org.junit.jupiter.api.Assertions.assertTrue(js.contains("var ALLOW_FAKE_AUDIO = true"));
            org.junit.jupiter.api.Assertions.assertTrue(js.contains("pumpSyntheticAudio"));
        } finally {
            System.clearProperty("airplay.mse.cast.autoCapture");
        }
    }
}
