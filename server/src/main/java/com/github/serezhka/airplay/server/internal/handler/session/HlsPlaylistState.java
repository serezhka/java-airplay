package com.github.serezhka.airplay.server.internal.handler.session;

import io.lindstrom.m3u8.model.MediaPlaylist;
import io.lindstrom.m3u8.model.MediaSegment;
import io.lindstrom.m3u8.parser.MediaPlaylistParser;
import io.lindstrom.m3u8.parser.ParsingMode;
import io.lindstrom.m3u8.parser.PlaylistParserException;
import lombok.Getter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
public class HlsPlaylistState {

    private final String remoteMasterUri;
    private final String playlistUriLocal;
    private final List<String> pendingMediaUris = new ArrayList<>();
    private final Map<String, String> playlists = new LinkedHashMap<>();

    private int nextMediaUriIndex;
    private int fcupRequestId = 1;
    private boolean masterReceived;
    private boolean playbackStarted;
    private String lastMasterBody;
    private volatile boolean waitingForMasterChange;
    private Double pendingSeekSeconds;
    /** Best-effort VOD duration from media playlist {@code #EXTINF} sums. */
    private volatile double mediaDurationSeconds;
    /** AirPlay playback rate: {@code 0} paused, {@code 1} playing. */
    private volatile double playbackRate = 1;

    public HlsPlaylistState(String remoteMasterUri, String playlistUriLocal) {
        this.remoteMasterUri = remoteMasterUri;
        this.playlistUriLocal = playlistUriLocal;
    }

    public void setPendingSeekSeconds(Double pendingSeekSeconds) {
        this.pendingSeekSeconds = pendingSeekSeconds;
    }

    public Double takePendingSeekSeconds() {
        Double seek = pendingSeekSeconds;
        pendingSeekSeconds = null;
        return seek;
    }

    public int nextFcupRequestId() {
        return fcupRequestId++;
    }

    public void putPlaylist(String remoteUri, String body) {
        playlists.put(normalizeUri(remoteUri), body);
        if (remoteUri.contains("mediadata.m3u8")) {
            double duration = sumMediaDurationSeconds(body);
            if (duration > mediaDurationSeconds) {
                mediaDurationSeconds = duration;
            }
        }
    }

    public void setPlaybackRate(double playbackRate) {
        this.playbackRate = playbackRate <= 0 ? 0 : 1;
    }

    public void invalidatePlaylists() {
        playlists.clear();
        mediaDurationSeconds = 0;
    }

    static double sumMediaDurationSeconds(String mediaPlaylistBody) {
        if (mediaPlaylistBody == null || mediaPlaylistBody.isBlank()) {
            return 0;
        }
        try {
            MediaPlaylist playlist = new MediaPlaylistParser(ParsingMode.LENIENT).readPlaylist(mediaPlaylistBody);
            double sum = 0;
            for (MediaSegment segment : playlist.mediaSegments()) {
                sum += segment.duration();
            }
            if (sum > 0) {
                return sum;
            }
        } catch (Exception ignored) {
            // use #EXTINF line scan below
        }
        double sum = 0;
        for (String line : mediaPlaylistBody.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#EXTINF:")) {
                String value = trimmed.substring("#EXTINF:".length()).split(",", 2)[0].trim();
                try {
                    sum += Double.parseDouble(value);
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
        }
        return sum;
    }

    public void updateMasterPlaylist(String rewrittenMaster) {
        putPlaylist(remoteMasterUri, rewrittenMaster);
    }

    public boolean recordMasterIfChanged(String rewrittenMaster) {
        if (lastMasterBody != null && rewrittenMaster.equals(lastMasterBody)) {
            return false;
        }
        lastMasterBody = rewrittenMaster;
        updateMasterPlaylist(rewrittenMaster);
        return true;
    }

    public boolean isWaitingForMasterChange() {
        return waitingForMasterChange;
    }

    public void setWaitingForMasterChange(boolean waitingForMasterChange) {
        this.waitingForMasterChange = waitingForMasterChange;
    }

    public String getPlaylist(String remoteUri) {
        return playlists.get(normalizeUri(remoteUri));
    }

    public void storeMasterPlaylist(String rewrittenMaster, List<String> remoteMediaUris) throws PlaylistParserException {
        // rewrittenMaster uses local http URLs for the media consumer; remoteMediaUris stay
        // mlhls://… so FCUP prefetch talks to the AirPlay client, not loopback.
        if (remoteMediaUris == null || remoteMediaUris.isEmpty()) {
            throw new PlaylistParserException("No media playlists found in master playlist");
        }
        masterReceived = true;
        lastMasterBody = rewrittenMaster;
        putPlaylist(remoteMasterUri, rewrittenMaster);
        pendingMediaUris.clear();
        for (String uri : remoteMediaUris) {
            pendingMediaUris.add(normalizeUri(uri));
        }
        nextMediaUriIndex = 0;
    }

    public boolean hasMoreMediaUris() {
        return nextMediaUriIndex < pendingMediaUris.size();
    }

    /** Stop FCUP media prefetch (e.g. on EOS) so late responses don't starve master refresh. */
    public void abortMediaPrefetch() {
        nextMediaUriIndex = pendingMediaUris.size();
    }

    public String nextMediaUri() {
        if (!hasMoreMediaUris()) {
            return null;
        }
        return pendingMediaUris.get(nextMediaUriIndex++);
    }

    public boolean allPlaylistsFetched() {
        return masterReceived && !pendingMediaUris.isEmpty() && nextMediaUriIndex >= pendingMediaUris.size();
    }

    public boolean isPlaybackStarted() {
        return playbackStarted;
    }

    public void markPlaybackStarted() {
        playbackStarted = true;
    }

    static List<String> extractMediaUris(String masterPlaylist) throws PlaylistParserException {
        return HlsUriRewrite.extractMediaUris(masterPlaylist);
    }

    private static String normalizeUri(String uri) {
        return uri.split("\\?")[0];
    }

    public int pendingMediaUriCount() {
        return pendingMediaUris.size();
    }

    public int fetchedMediaPlaylistCount() {
        return Math.max(0, nextMediaUriIndex);
    }

    @Override
    public String toString() {
        return "HlsPlaylistState{master=" + masterReceived
                + ", mediaUris=" + pendingMediaUris.size()
                + ", fetched=" + fetchedMediaPlaylistCount()
                + ", playlists=" + playlists.size()
                + ", localUri=" + playlistUriLocal + "}";
    }
}
