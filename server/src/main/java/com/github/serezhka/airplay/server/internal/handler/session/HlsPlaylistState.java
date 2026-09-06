package com.github.serezhka.airplay.server.internal.handler.session;

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

    public HlsPlaylistState(String remoteMasterUri, String playlistUriLocal) {
        this.remoteMasterUri = remoteMasterUri;
        this.playlistUriLocal = playlistUriLocal;
    }

    public int nextFcupRequestId() {
        return fcupRequestId++;
    }

    public void putPlaylist(String remoteUri, String body) {
        playlists.put(normalizeUri(remoteUri), body);
    }

    public void invalidatePlaylists() {
        playlists.clear();
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

    public void storeMasterPlaylist(String rewrittenMaster, String rawMaster) throws PlaylistParserException {
        List<String> mediaUris = HlsUriRewrite.extractMediaUris(rawMaster);
        if (mediaUris.isEmpty()) {
            throw new PlaylistParserException("No media playlists found in master playlist");
        }
        masterReceived = true;
        lastMasterBody = rewrittenMaster;
        putPlaylist(remoteMasterUri, rewrittenMaster);
        pendingMediaUris.clear();
        pendingMediaUris.addAll(mediaUris);
        nextMediaUriIndex = 0;
    }

    public boolean hasMoreMediaUris() {
        return nextMediaUriIndex < pendingMediaUris.size();
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
