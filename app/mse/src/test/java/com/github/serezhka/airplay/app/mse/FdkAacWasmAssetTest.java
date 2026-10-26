package com.github.serezhka.airplay.app.mse;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ensures the bundled fdk-aac WASM decoder ships with the MSE jar. */
class FdkAacWasmAssetTest {

    @Test
    void fdkWasmAndGlueAreOnClasspath() throws Exception {
        for (String name : new String[]{"fdk_aac.js", "fdk_aac.wasm", "FDK_AAC_NOTICE.txt"}) {
            try (InputStream in = FdkAacWasmAssetTest.class.getResourceAsStream("/mse/" + name)) {
                assertNotNull(in, "missing /mse/" + name);
                byte[] bytes = in.readAllBytes();
                assertTrue(bytes.length > 100, name + " too small: " + bytes.length);
            }
        }
    }
}
