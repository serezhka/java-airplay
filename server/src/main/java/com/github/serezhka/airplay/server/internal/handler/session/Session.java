package com.github.serezhka.airplay.server.internal.handler.session;

import com.github.serezhka.airplay.lib.AirPlay;
import com.github.serezhka.airplay.server.internal.AudioControlServer;
import com.github.serezhka.airplay.server.internal.AudioServer;
import com.github.serezhka.airplay.server.internal.VideoServer;
import io.netty.channel.ChannelHandlerContext;
import lombok.Getter;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Getter
public class Session {

    private final String id;

    private final AirPlay airPlay;
    private final VideoServer videoServer;
    private final AudioServer audioServer;
    private final AudioControlServer audioControlServer;
    private final Map<String, ChannelHandlerContext> reverseContexts;
    private final Map<String, Queue<PlaylistRequest>> playlistRequests;
    private volatile HlsPlaylistState hlsPlaylistState;

    Session(String id) {
        this.id = id;
        airPlay = new AirPlay();
        videoServer = new VideoServer(airPlay);
        audioServer = new AudioServer(airPlay);
        audioControlServer = new AudioControlServer();
        reverseContexts = new ConcurrentHashMap<>();
        playlistRequests = new ConcurrentHashMap<>();
    }

    public void enqueuePlaylistRequest(String remoteUri, PlaylistRequest request) {
        playlistRequests.computeIfAbsent(remoteUri, ignored -> new ConcurrentLinkedQueue<>()).add(request);
    }

    public PlaylistRequest pollPlaylistRequest(String remoteUri) {
        Queue<PlaylistRequest> queue = playlistRequests.get(remoteUri);
        return queue == null ? null : queue.poll();
    }

    public void setHlsPlaylistState(HlsPlaylistState hlsPlaylistState) {
        this.hlsPlaylistState = hlsPlaylistState;
    }
}
