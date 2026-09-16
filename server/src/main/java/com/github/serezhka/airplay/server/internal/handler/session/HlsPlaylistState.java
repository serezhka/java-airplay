package com.github.serezhka.airplay.server.internal.handler.session;

import io.lindstrom.m3u8.model.MediaPlaylist;
import io.lindstrom.m3u8.model.MediaSegment;
import io.lindstrom.m3u8.parser.MediaPlaylistParser;
import io.lindstrom.m3u8.parser.ParsingMode;
import io.lindstrom.m3u8.parser.PlaylistParserException;
import lombok.Getter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
public class HlsPlaylistState {

    private final String remoteMasterUri;
    private final String playlistUriLocal;
    private final List<String> pendingMediaUris = new ArrayList<>();
    private final Map<String, String> playlists = new LinkedHashMap<>();
    /** Hashes of last-seen mediadata bodies; survive {@link #invalidatePlaylists()} for EOS compare. */
    private final Map<String, Integer> mediaBodyHashes = new HashMap<>();

    private int nextMediaUriIndex;
    private int fcupRequestId = 1;
    private boolean masterReceived;
    private boolean playbackStarted;
    private String lastMasterBody;
    /** Unfiltered FCUP master — AVC rewrite can hide ad→content changes. */
    private String lastRawMasterBody;
    private volatile boolean waitingForMasterChange;
    private volatile boolean postEosMediaRefreshing;
    private volatile boolean postEosMediaChanged;
    private Double pendingSeekSeconds;
    /** Best-effort VOD duration from media playlist {@code #EXTINF} sums. */
    private volatile double mediaDurationSeconds;
    /** AirPlay playback rate: {@code 0} paused, {@code 1} playing. */
    private volatile double playbackRate = 1;
    /** Ignore rate=0 until this nanoTime (YouTube pauses around /scrub). */
    private volatile long ignorePauseUntilNanos;

    public HlsPlaylistState(String remoteMasterUri, String playlistUriLocal) {
        this.remoteMasterUri = remoteMasterUri;
        this.playlistUriLocal = playlistUriLocal;
    }

    public void markScrubGrace(long durationNanos) {
        ignorePauseUntilNanos = System.nanoTime() + Math.max(0, durationNanos);
    }

    public boolean shouldIgnorePause() {
        return System.nanoTime() < ignorePauseUntilNanos;
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
        String key = normalizeUri(remoteUri);
        playlists.put(key, body);
        if (remoteUri.contains("mediadata.m3u8")) {
            double duration = sumMediaDurationSeconds(body);
            if (duration > mediaDurationSeconds) {
                mediaDurationSeconds = duration;
            }
            int hash = body.hashCode();
            Integer prev = mediaBodyHashes.put(key, hash);
            if (postEosMediaRefreshing && prev != null && prev != hash) {
                postEosMediaChanged = true;
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

    /** Track raw (pre-AVC-filter) master; returns true when bytes differ from last EOS cycle. */
    public boolean recordRawMasterIfChanged(String rawMaster) {
        if (rawMaster == null) {
            return false;
        }
        if (lastRawMasterBody != null && rawMaster.equals(lastRawMasterBody)) {
            return false;
        }
        lastRawMasterBody = rawMaster;
        return true;
    }

    /**
     * After ad EOS YouTube often keeps the same master URI list and only updates mediadata.
     * Re-queue media FCUP so we can detect that and restart.
     */
    public void beginPostEosMediaRefresh(List<String> remoteMediaUris) {
        postEosMediaRefreshing = true;
        postEosMediaChanged = false;
        pendingMediaUris.clear();
        if (remoteMediaUris != null) {
            for (String uri : remoteMediaUris) {
                pendingMediaUris.add(normalizeUri(uri));
            }
        }
        nextMediaUriIndex = 0;
    }

    public boolean isPostEosMediaRefreshing() {
        return postEosMediaRefreshing;
    }

    /** @return true if any mediadata body changed during the refresh */
    public boolean finishPostEosMediaRefresh() {
        postEosMediaRefreshing = false;
        boolean changed = postEosMediaChanged;
        postEosMediaChanged = false;
        return changed;
    }

    public void cancelPostEosMediaRefresh() {
        postEosMediaRefreshing = false;
        postEosMediaChanged = false;
    }

    public boolean isWaitingForMasterChange() {
        return waitingForMasterChange;
    }

    public void setWaitingForMasterChange(boolean waitingForMasterChange) {
        this.waitingForMasterChange = waitingForMasterChange;
        if (!waitingForMasterChange) {
            cancelPostEosMediaRefresh();
        }
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
