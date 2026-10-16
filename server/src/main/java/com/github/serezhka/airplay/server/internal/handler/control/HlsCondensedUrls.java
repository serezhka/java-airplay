package com.github.serezhka.airplay.server.internal.handler.control;

import io.lindstrom.m3u8.model.MediaPlaylist;
import io.lindstrom.m3u8.model.MediaSegment;
import lombok.extern.slf4j.Slf4j;

import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Expand {@code YT-EXT-CONDENSED-URL} media playlists into full segment URIs. */
@Slf4j
final class HlsCondensedUrls {

    private static final Pattern ATTR =
            Pattern.compile("([A-Z0-9\\-]+)=(?:\"([^\"]+)\"|([^,]+))");

    private HlsCondensedUrls() {
    }

    static MediaPlaylist expand(MediaPlaylist mediaPlaylist) {
        var condensedUrl = mediaPlaylist.comments().stream()
                .filter(comment -> comment.startsWith("YT-EXT-CONDENSED-URL:"))
                .map(comment -> comment.replace("YT-EXT-CONDENSED-URL:", ""))
                .flatMap(attributes -> ATTR.matcher(attributes).results())
                .collect(Collectors.toMap(
                        matcher -> matcher.group(1),
                        matcher -> matcher.group(2) != null ? matcher.group(2) : matcher.group(3)));
        if (condensedUrl.isEmpty()) {
            return mediaPlaylist;
        }
        var prefix = condensedUrl.get("PREFIX");
        var params = condensedUrl.get("PARAMS");
        var baseUri = condensedUrl.get("BASE-URI");
        if (prefix == null || params == null || baseUri == null) {
            return mediaPlaylist;
        }
        var paramNames = params.split(",");
        boolean matching = mediaPlaylist.mediaSegments().stream().allMatch(segment ->
                segment.uri().replaceFirst(Pattern.quote(prefix), "").split("/").length == paramNames.length);
        if (!matching) {
            log.warn("Condensed URL param count mismatch, leaving playlist unchanged");
            return mediaPlaylist;
        }
        return MediaPlaylist.builder()
                .from(mediaPlaylist)
                .mediaSegments(mediaPlaylist.mediaSegments().stream()
                        .map(segment -> {
                            var paramValues = segment.uri().replaceFirst(Pattern.quote(prefix), "").split("/");
                            var paramResult = new StringBuilder();
                            for (int i = 0; i < paramNames.length; i++) {
                                paramResult.append("/").append(paramNames[i]).append("/").append(paramValues[i]);
                            }
                            return MediaSegment.builder().from(segment).uri(baseUri + paramResult).build();
                        })
                        .toList())
                .build();
    }
}
