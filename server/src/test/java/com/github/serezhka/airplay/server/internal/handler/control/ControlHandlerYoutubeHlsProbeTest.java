package com.github.serezhka.airplay.server.internal.handler.control;

import com.dd.plist.BinaryPropertyListWriter;
import com.dd.plist.NSDictionary;
import com.dd.plist.PropertyListParser;
import com.github.serezhka.airplay.server.AirPlayConfig;
import com.github.serezhka.airplay.server.discovery.AdvertisedReceiver;
import com.github.serezhka.airplay.server.internal.handler.session.SessionManager;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * YouTube HLS phone path (control plane only): compact {@code /server-info}, HTTP FairPlay
 * ack, {@code POST /play} with {@code Content-Location}, then {@code /playback-info}.
 *
 * <p>Does not fetch real YouTube mediadata — locks the request sequence our receiver must
 * accept before FCUP / playlist rewrite kicks in.
 */
class ControlHandlerYoutubeHlsProbeTest {

    private static final String SESSION = "youtube-hls-probe";

    private EmbeddedChannel channel;
    private HlsFcupService fcup;
    private RecordingPlayback playback;
    private SessionManager sessions;

    @BeforeEach
    void setUp() {
        sessions = new SessionManager();
        playback = new RecordingPlayback();
        fcup = new HlsFcupService(sessions, playback);
        AirPlayConfig config = new AirPlayConfig();
        config.setHlsEnabled(true);
        channel = new EmbeddedChannel(new ControlHandler(sessions, fcup, config, playback));
    }

    @AfterEach
    void tearDown() {
        fcup.stopStateHeartbeat();
        if (channel != null) {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void youtubeHlsControlSequence_serverInfoFpAckPlayPlaybackInfo() throws Exception {
        // 1) Capability probe — compact HLS profile, not full mDNS advertise bits.
        FullHttpResponse serverInfo = get("/server-info");
        assertEquals(HttpResponseStatus.OK, serverInfo.status());
        String infoXml = serverInfo.content().toString(StandardCharsets.UTF_8);
        serverInfo.release();
        assertTrue(infoXml.contains(Long.toString(AdvertisedReceiver.SERVER_INFO_FEATURES)), infoXml);
        assertTrue(infoXml.contains(AdvertisedReceiver.SERVER_INFO_SOURCE_VERSION), infoXml);
        assertFalse(infoXml.contains(Long.toString(AdvertisedReceiver.FEATURES)),
                "server-info must stay on SERVER_INFO profile (YouTube probes this)");

        // 2) HTTP FairPlay v1 stub — must 200 with empty body (never run RTSP v3).
        byte[] v1 = new byte[]{70, 80, 76, 89, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        FullHttpResponse fp = post("/fp-setup", v1);
        assertEquals(HttpResponseStatus.OK, fp.status());
        assertEquals(0, fp.content().readableBytes());
        fp.release();

        // 3) /play with Content-Location (YouTube master / mediadata URI).
        NSDictionary play = new NSDictionary();
        play.put("Content-Location", "mlhls://localhost/master.m3u8");
        play.put("clientProcName", "YouTube");
        play.put("uuid", "item-1");
        play.put("Start-Position-Seconds", 0.0);
        byte[] playBody = BinaryPropertyListWriter.writeToArray(play);
        FullHttpResponse playResp = post("/play", playBody);
        assertEquals(HttpResponseStatus.OK, playResp.status());
        playResp.release();

        assertNotNull(sessions.getSession(SESSION).getHlsPlaylistState(),
                "/play must install HlsPlaylistState");
        assertTrue(playback.events().contains("playlist")
                        || sessions.getSession(SESSION).getHlsPlaylistState() != null,
                "HLS session armed after /play");

        // 4) /playback-info must return a plist (pin duration/position/rate contract).
        FullHttpResponse info = get("/playback-info");
        assertEquals(HttpResponseStatus.OK, info.status());
        assertTrue(info.headers().get(HttpHeaderNames.CONTENT_TYPE)
                .contains("plist"), info.headers().get(HttpHeaderNames.CONTENT_TYPE));
        byte[] infoBytes = new byte[info.content().readableBytes()];
        info.content().readBytes(infoBytes);
        info.release();
        Object parsed = PropertyListParser.parse(infoBytes);
        assertTrue(parsed instanceof NSDictionary, "playback-info must be a plist dictionary");
    }

    @Test
    void playWithoutContentLocationIsNotImplemented() throws Exception {
        NSDictionary play = new NSDictionary();
        play.put("clientProcName", "YouTube");
        byte[] body = BinaryPropertyListWriter.writeToArray(play);
        FullHttpResponse response = post("/play", body);
        assertEquals(HttpResponseStatus.NOT_IMPLEMENTED, response.status());
        response.release();
    }

    private FullHttpResponse get(String path) {
        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path);
        req.headers().set("X-Apple-Session-ID", SESSION);
        channel.writeInbound(req);
        channel.checkException();
        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        return response;
    }

    private FullHttpResponse post(String path, byte[] body) {
        FullHttpRequest req = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, path, Unpooled.wrappedBuffer(body));
        req.headers().set("X-Apple-Session-ID", SESSION);
        req.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        channel.writeInbound(req);
        channel.checkException();
        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        return response;
    }
}
