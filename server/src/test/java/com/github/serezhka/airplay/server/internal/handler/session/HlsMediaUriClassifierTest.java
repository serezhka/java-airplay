package com.github.serezhka.airplay.server.internal.handler.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HlsMediaUriClassifierTest {

    @Test
    void classifiesVideoVersusAudioAlternatives() {
        assertTrue(HlsMediaUriClassifier.isLikelyVideo("mlhls://localhost/itag/229/mediadata.m3u8"));
        assertFalse(HlsMediaUriClassifier.isLikelyVideo("mlhls://localhost/itag/233/mediadata.m3u8"));
        assertFalse(HlsMediaUriClassifier.isLikelyVideo("mlhls://localhost/itag/234/mediadata.m3u8"));
        assertFalse(HlsMediaUriClassifier.isLikelyVideo(
                "mlhls://localhost/itag/233/xtags/abc/mediadata.m3u8"));
        assertFalse(HlsMediaUriClassifier.isLikelyVideo("http://example/plain.m3u8"));
    }
}
