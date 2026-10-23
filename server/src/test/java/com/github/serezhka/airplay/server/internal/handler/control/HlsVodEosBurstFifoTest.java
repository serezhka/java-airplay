package com.github.serezhka.airplay.server.internal.handler.control;

import com.github.serezhka.airplay.server.internal.handler.session.HlsPlaylistState;
import com.github.serezhka.airplay.server.internal.handler.session.Session;
import com.github.serezhka.airplay.server.internal.handler.session.SessionManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real {@link HlsFcupService#sendVodEosEventBurst} FIFO on the reverse {@code /event}
 * channel (not a label stub).
 */
class HlsVodEosBurstFifoTest {

    private final SessionManager sessions = new SessionManager();
    private final RecordingPlayback playback = new RecordingPlayback();
    private final HlsFcupService service = new HlsFcupService(sessions, playback);

    @AfterEach
    void tearDown() {
        service.cancelAllMasterPolls();
        service.stopStateHeartbeat();
    }

    @Test
    void vodEosBurstOrderOnReverseEventChannel() {
        Session session = sessions.getSession("eos-fifo");
        HlsPlaylistState hls = new HlsPlaylistState(
                "mlhls://localhost/master.m3u8",
                "http://127.0.0.1/playlist/master.m3u8?session=eos-fifo",
                "ITEM-EOS");
        session.setHlsPlaylistState(hls);

        AtomicReference<ChannelHandlerContext> eventCtx = new AtomicReference<>();
        EmbeddedChannel reverse = new EmbeddedChannel(new ChannelInboundHandlerAdapter() {
            @Override
            public void handlerAdded(ChannelHandlerContext ctx) {
                eventCtx.set(ctx);
            }
        });
        session.getReverseContexts().put("event", eventCtx.get());

        service.sendVodEosEventBurst(session);

        List<String> labels = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            FullHttpRequest event = reverse.readOutbound();
            assertTrue(event != null, "missing reverse /event #" + i);
            assertEquals("/event", event.uri());
            String xml = event.content().toString(StandardCharsets.UTF_8);
            labels.add(classify(xml));
            event.release();
            service.onReverseEventResponse(eventCtx.get());
        }
        assertTrue(reverse.readOutbound() == null, "extra reverse events");

        assertEquals(List.of(
                "loading",
                "itemPlayedToEnd",
                "stopped:ended",
                "itemRemoved",
                "currentItemChanged",
                "stopped"), labels);
    }

    private static String classify(String xml) {
        if (xml.contains("<string>itemPlayedToEnd</string>")) {
            return "itemPlayedToEnd";
        }
        if (xml.contains("<string>itemRemoved</string>")) {
            return "itemRemoved";
        }
        if (xml.contains("<string>currentItemChanged</string>")) {
            return "currentItemChanged";
        }
        if (xml.contains("<string>stopped</string>") && xml.contains("<string>ended</string>")) {
            return "stopped:ended";
        }
        if (xml.contains("<string>stopped</string>")) {
            return "stopped";
        }
        if (xml.contains("<string>loading</string>")) {
            return "loading";
        }
        return "unknown:" + xml.replaceAll("\\s+", " ").trim();
    }
}
