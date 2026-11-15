package com.github.serezhka.airplay.server.internal.handler.session;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Rewrites absolute CDN segment URIs in HLS media playlists onto a local HTTP
 * fetch path, and downloads upstream over {@link HttpClient}.
 * <p>
 * Native GST/FFmpeg HTTP stacks often prefer IPv6 and hit RST on googlevideo;
 * the JDK client prefers IPv4 by default ({@code preferIPv6Addresses=false}),
 * which matches the MSE {@code /hls?u=} path.
 */
public final class HlsCdnProxy {

    public static final int MAX_BYTES = 32 * 1024 * 1024;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private HlsCdnProxy() {
    }

    /**
     * @param proxyPrefix local prefix ending with {@code u=} (query value is appended encoded)
     */
    public static String rewritePlaylistForProxy(String body, String playlistUrl, String proxyPrefix) {
        URI base = URI.create(playlistUrl);
        StringBuilder out = new StringBuilder(body.length() + 256);
        for (String rawLine : body.split("\n", -1)) {
            String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            if (line.isEmpty()) {
                out.append('\n');
                continue;
            }
            if (line.startsWith("#")) {
                out.append(rewriteAttributeUris(line, base, proxyPrefix)).append('\n');
                continue;
            }
            String ref = line.trim();
            // Only wrap already-absolute https URIs. Resolving relatives against a dummy
            // https base would incorrectly proxy local VOD fixtures (seg000.ts → CDN hop).
            if (ref.regionMatches(true, 0, "https://", 0, 8)) {
                String resolved = resolveAgainst(base, ref);
                if (resolved != null && shouldProxy(resolved)) {
                    out.append(proxyPrefix).append(URLEncoder.encode(resolved, StandardCharsets.UTF_8)).append('\n');
                    continue;
                }
            }
            out.append(line).append('\n');
        }
        if (!body.endsWith("\n") && !out.isEmpty() && out.charAt(out.length() - 1) == '\n') {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    /** HTTPS CDN (and http loopback) allowed as upstream for the local fetch path. */
    public static boolean isAllowedUpstream(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null) {
                return false;
            }
            String host = uri.getHost();
            if ("https".equalsIgnoreCase(scheme)) {
                return host != null && !host.isBlank();
            }
            if ("http".equalsIgnoreCase(scheme)) {
                return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** Only wrap remote https URIs — never re-wrap an already-local proxy URL. */
    public static boolean shouldProxy(String url) {
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            String host = uri.getHost();
            return host != null && !host.isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean looksLikePlaylist(String contentType, byte[] body) {
        String lowerType = contentType == null ? "" : contentType.toLowerCase();
        if (lowerType.contains("mpegurl") || lowerType.contains("x-mpegurl")) {
            return true;
        }
        // YouTube segment URLs embed ".../playlist/index.m3u8/..." — do not classify by
        // URL substring; sniff the body instead.
        int n = Math.min(body.length, 16);
        if (n == 0) {
            return false;
        }
        String head = new String(body, 0, n, StandardCharsets.UTF_8).trim();
        return head.startsWith("#EXTM3U");
    }

    public static CompletableFuture<HttpResponse<byte[]>> fetchAsync(String upstream, Optional<String> range) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(upstream))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "airplay-hls-cdn-proxy")
                .GET();
        range.filter(r -> !r.isBlank()).ifPresent(r -> builder.header("Range", r));
        return HTTP.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    public static String proxyPrefix(String playlistBaseUrl, String sessionId) {
        // playlistBaseUrl = http://127.0.0.1:PORT/playlist
        return playlistBaseUrl + "/cdn?session=" + sessionId + "&u=";
    }

    private static String rewriteAttributeUris(String line, URI base, String proxyPrefix) {
        int idx = 0;
        StringBuilder sb = new StringBuilder(line.length() + 64);
        while (true) {
            int uriKey = line.indexOf("URI=\"", idx);
            if (uriKey < 0) {
                sb.append(line.substring(idx));
                break;
            }
            int valueStart = uriKey + "URI=\"".length();
            int valueEnd = line.indexOf('"', valueStart);
            if (valueEnd < 0) {
                sb.append(line.substring(idx));
                break;
            }
            sb.append(line, idx, valueStart);
            String original = line.substring(valueStart, valueEnd);
            if (original.regionMatches(true, 0, "https://", 0, 8)) {
                String resolved = resolveAgainst(base, original);
                if (resolved != null && shouldProxy(resolved)) {
                    sb.append(proxyPrefix).append(URLEncoder.encode(resolved, StandardCharsets.UTF_8));
                } else {
                    sb.append(original);
                }
            } else {
                sb.append(original);
            }
            sb.append('"');
            idx = valueEnd + 1;
        }
        return sb.toString();
    }

    private static String resolveAgainst(URI base, String ref) {
        try {
            return base.resolve(ref).toString();
        } catch (Exception e) {
            return null;
        }
    }
}
