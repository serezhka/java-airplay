package com.github.serezhka.airplay.server.internal.handler.session;

import io.lindstrom.m3u8.model.AlternativeRendition;
import io.lindstrom.m3u8.model.MultivariantPlaylist;
import io.lindstrom.m3u8.model.Variant;
import io.lindstrom.m3u8.parser.MultivariantPlaylistParser;
import io.lindstrom.m3u8.parser.PlaylistParserException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HlsUriRewrite {

    private static final Pattern MLHLS_URI = Pattern.compile("mlhls://localhost[^\\s\"']+");
    private static final Pattern MEDIA_URI = Pattern.compile("mlhls://localhost[^\\s\"']+/mediadata\\.m3u8");

    private HlsUriRewrite() {
    }

    public static String toLocalUri(String remoteUri, String baseUrl, String sessionId) {
        // Absolute HTTP(S) Content-Location (direct VOD / offline replay) — pass through.
        if (remoteUri.startsWith("http://") || remoteUri.startsWith("https://")) {
            return remoteUri;
        }
        String path = remoteUri.replace("mlhls://localhost", "");
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        int queryIndex = path.indexOf('?');
        if (queryIndex >= 0) {
            path = path.substring(0, queryIndex);
        }
        return baseUrl + path + "?session=" + sessionId;
    }

    public static String rewritePlaylist(String playlist, String baseUrl, String sessionId) {
        Matcher matcher = MLHLS_URI.matcher(playlist);
        StringBuilder rewritten = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(toLocalUri(matcher.group(), baseUrl, sessionId)));
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    /**
     * Keep H.264 ({@code avc1}) variants only. YouTube masters mix AVC + VP9/AV1; a single
     * HLS consumer cannot switch video codecs mid-playlist. Also drops subtitle renditions.
     */
    public static String preferAvcVariants(String masterPlaylist) {
        String[] lines = masterPlaylist.split("\\R", -1);
        List<String> headers = new ArrayList<>();
        List<String> audioMedia = new ArrayList<>();
        List<String> variants = new ArrayList<>();
        Set<String> audioGroups = new LinkedHashSet<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                String info = line;
                String uri = (i + 1 < lines.length) ? lines[++i] : "";
                if (isAvcStreamInfo(info)) {
                    variants.add(info);
                    variants.add(uri);
                    extractAttr(info, "AUDIO").ifPresent(audioGroups::add);
                }
                continue;
            }
            if (line.startsWith("#EXT-X-MEDIA:")) {
                String type = extractAttr(line, "TYPE").orElse("");
                if ("AUDIO".equalsIgnoreCase(type)) {
                    audioMedia.add(line);
                }
                // drop SUBTITLES / CLOSED-CAPTIONS / other
                continue;
            }
            if (!line.isEmpty() || headers.isEmpty()) {
                headers.add(line);
            }
        }

        if (variants.isEmpty()) {
            return masterPlaylist;
        }

        StringBuilder out = new StringBuilder();
        for (String header : headers) {
            if (header.isEmpty() && out.isEmpty()) {
                continue;
            }
            out.append(header).append('\n');
        }
        for (String media : audioMedia) {
            String group = extractAttr(media, "GROUP-ID").orElse("");
            if (audioGroups.isEmpty() || audioGroups.contains(group)) {
                out.append(media).append('\n');
            }
        }
        for (String variantLine : variants) {
            out.append(variantLine).append('\n');
        }
        return out.toString();
    }

    private static boolean isAvcStreamInfo(String streamInf) {
        String codecs = extractAttr(streamInf, "CODECS").orElse("").toLowerCase();
        if (codecs.isBlank()) {
            return true;
        }
        boolean avc = codecs.contains("avc1") || codecs.contains("avc3");
        boolean vp9 = codecs.contains("vp09") || codecs.contains("vp9");
        boolean av1 = codecs.contains("av01") || codecs.contains("av1.");
        return avc && !vp9 && !av1;
    }

    private static java.util.Optional<String> extractAttr(String line, String name) {
        Pattern p = Pattern.compile(name + "=(?:\"([^\"]*)\"|([^,]*))", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(line);
        if (!m.find()) {
            return java.util.Optional.empty();
        }
        String v = m.group(1) != null ? m.group(1) : m.group(2);
        return java.util.Optional.ofNullable(v).map(String::trim).filter(s -> !s.isEmpty());
    }

    public static List<String> extractMediaUris(String masterPlaylist) throws PlaylistParserException {
        try {
            return extractMediaUrisParsed(masterPlaylist);
        } catch (PlaylistParserException parserError) {
            List<String> uris = extractMediaUrisLenient(masterPlaylist);
            if (uris.isEmpty()) {
                throw parserError;
            }
            return uris;
        }
    }

    private static List<String> extractMediaUrisParsed(String masterPlaylist) throws PlaylistParserException {
        var parser = new MultivariantPlaylistParser();
        MultivariantPlaylist playlist = parser.readPlaylist(masterPlaylist);
        Set<String> uris = new LinkedHashSet<>();
        for (Variant variant : playlist.variants()) {
            addUri(uris, variant.uri());
        }
        for (AlternativeRendition rendition : playlist.alternativeRenditions()) {
            rendition.uri().ifPresent(uri -> addUri(uris, uri));
        }
        return new ArrayList<>(uris);
    }

    private static List<String> extractMediaUrisLenient(String masterPlaylist) {
        Set<String> uris = new LinkedHashSet<>();
        Matcher matcher = MEDIA_URI.matcher(masterPlaylist);
        while (matcher.find()) {
            addUri(uris, matcher.group());
        }
        return new ArrayList<>(uris);
    }

    private static void addUri(Set<String> uris, String uri) {
        if (uri == null || uri.isBlank()) {
            return;
        }
        uris.add(uri.split("\\?")[0]);
    }
}
