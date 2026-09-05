package com.github.serezhka.airplay.server;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public record ControlExchange(
        Instant timestamp,
        String sessionId,
        String protocol,
        String method,
        String uri,
        Map<String, String> requestHeaders,
        byte[] requestBody,
        int responseStatus,
        Map<String, String> responseHeaders,
        byte[] responseBody
) {

    public ControlExchange {
        requestHeaders = copyHeaders(requestHeaders);
        responseHeaders = copyHeaders(responseHeaders);
        requestBody = requestBody == null ? new byte[0] : requestBody;
        responseBody = responseBody == null ? new byte[0] : responseBody;
    }

    public String requestContentType() {
        return header(requestHeaders, "Content-Type");
    }

    public String header(Map<String, String> headers, String name) {
        if (headers == null || name == null) {
            return null;
        }
        String expected = name.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && expected.equals(entry.getKey().toLowerCase(Locale.ROOT))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static Map<String, String> copyHeaders(Map<String, String> headers) {
        return headers == null ? Map.of() : new LinkedHashMap<>(headers);
    }
}
