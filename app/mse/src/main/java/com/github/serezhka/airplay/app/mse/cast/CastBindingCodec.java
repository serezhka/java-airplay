package com.github.serezhka.airplay.app.mse.cast;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * JSON framing for {@code Runtime.addBinding} payloads from the cast tab
 * ({@code cast-inject.js}). Keeps Annex-B / ADTS off {@code ws://} mixed content.
 */
final class CastBindingCodec {

    static final String BINDING_NAME = "airplayCast";

    private CastBindingCodec() {
    }

    sealed interface Message permits VideoAnnexB, AudioAdts, Event {
    }

    record VideoAnnexB(byte[] annexB) implements Message {
    }

    record AudioAdts(byte[] adtsFrame) implements Message {
    }

    record Event(String kind, String detail) implements Message {
    }

    static Message parse(String payload) {
        if (payload == null || payload.isEmpty()) {
            throw new IllegalArgumentException("empty binding payload");
        }
        String t = extractJsonString(payload, "t");
        if (t == null) {
            throw new IllegalArgumentException("missing type field");
        }
        return switch (t) {
            case "v" -> new VideoAnnexB(decodeField(payload, "b"));
            case "a" -> new AudioAdts(decodeField(payload, "b"));
            case "e" -> new Event(
                    nullToEmpty(extractJsonString(payload, "event")),
                    nullToEmpty(extractJsonString(payload, "message")));
            default -> throw new IllegalArgumentException("unknown binding type: " + t);
        };
    }

    static String encodeEvent(String event, String message) {
        return "{\"t\":\"e\",\"event\":\"" + jsonEscape(event) + "\",\"message\":\""
                + jsonEscape(message) + "\"}";
    }

    private static byte[] decodeField(String json, String key) {
        String b64 = extractJsonString(json, key);
        if (b64 == null || b64.isEmpty()) {
            throw new IllegalArgumentException("missing " + key);
        }
        return Base64.getDecoder().decode(b64.getBytes(StandardCharsets.US_ASCII));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    static String extractJsonString(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int i = json.indexOf(marker);
        if (i < 0) {
            marker = "\"" + key + "\": \"";
            i = json.indexOf(marker);
        }
        if (i < 0) {
            return null;
        }
        int start = i + marker.length();
        StringBuilder out = new StringBuilder();
        for (int j = start; j < json.length(); j++) {
            char c = json.charAt(j);
            if (c == '\\' && j + 1 < json.length()) {
                char n = json.charAt(j + 1);
                switch (n) {
                    case '\\', '"', '/' -> out.append(n);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (j + 5 < json.length()) {
                            int cp = Integer.parseInt(json.substring(j + 2, j + 6), 16);
                            out.append((char) cp);
                            j += 5;
                        }
                    }
                    default -> out.append(n);
                }
                j++;
                continue;
            }
            if (c == '"') {
                return out.toString();
            }
            out.append(c);
        }
        return null;
    }
}
