package com.github.serezhka.airplay.server.internal.handler.session;

import com.github.serezhka.airplay.lib.AirPlay;
import com.github.serezhka.airplay.server.internal.AudioControlServer;
import com.github.serezhka.airplay.server.internal.AudioServer;
import com.github.serezhka.airplay.server.internal.VideoServer;
import com.github.serezhka.airplay.server.internal.handler.control.CallbackHandler;
import io.netty.channel.ChannelHandlerContext;
import lombok.Getter;
import lombok.Setter;

@Getter
public class Session {

    private final String id;

    private final AirPlay airPlay;
    private final VideoServer videoServer;
    private final AudioServer audioServer;
    private final AudioControlServer audioControlServer;

    @Setter
    private ChannelHandlerContext context;

    @Setter
    private CallbackHandler callbackHandler;

    Session(String id) {
        this.id = id;
        airPlay = new AirPlay();
        videoServer = new VideoServer(airPlay);
        audioServer = new AudioServer(airPlay);
        audioControlServer = new AudioControlServer();
    }
}
