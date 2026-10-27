package com.github.serezhka.airplay.app.mse;

import com.github.serezhka.airplay.app.mse.cast.CastCaptureServer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CastCaptureServerTest {

    @Test
    void servesCastShellPage() throws Exception {
        try (CastCaptureServer server = new CastCaptureServer()) {
            server.start();
            HttpResponse<String> page = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(server.pageUrl())).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("airplay-cast"));
            assertTrue(page.body().contains("getDisplayMedia"));
            assertTrue(page.body().contains("VideoEncoder"));
        }
    }
}
