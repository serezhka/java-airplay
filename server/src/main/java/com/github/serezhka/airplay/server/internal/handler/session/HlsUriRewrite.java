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
