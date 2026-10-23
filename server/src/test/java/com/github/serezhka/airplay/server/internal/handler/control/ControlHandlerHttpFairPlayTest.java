package com.github.serezhka.airplay.server.internal.handler.control;

import com.github.serezhka.airplay.server.AirPlayConfig;
import com.github.serezhka.airplay.server.discovery.AdvertisedReceiver;
import com.github.serezhka.airplay.server.internal.handler.session.SessionManager;
import com.github.serezhka.airplay.server.internal.handler.util.PropertyListUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.rtsp.RtspVersions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * YouTube HLS posts FairPlay version 1 over HTTP {@code /fp-setup}. We only implement
 * FairPlay v3 on RTSP. Regression guard: HTTP must ack 200 (never 404 / never run v3).
 */
class ControlHandlerHttpFairPlayTest {

    private static final String SESSION = "http-fp-session";

    private EmbeddedChannel channel;
    private HlsFcupService fcup;

    @BeforeEach
    void setUp() {
        SessionManager sessions = new SessionManager();
        RecordingPlayback playback = new RecordingPlayback();
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
    void httpFpSetupAcksOkWithoutRunningFairPlayV3() {
        // Minimal FairPlay v1-shaped header (version byte at index 4 = 1).
        byte[] v1 = new byte[]{70, 80, 76, 89, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        FullHttpResponse response = postHttp("/fp-setup", v1);
        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals(0, response.content().readableBytes());
        response.release();
    }

    @Test
    void httpFpSetup2AlsoAcksOk() {
        FullHttpResponse response = postHttp("/fp-setup2", new byte[0]);
        assertEquals(HttpResponseStatus.OK, response.status());
        response.release();
    }

    @Test
    void unknownHttpPathStillNotFound() {
        FullHttpResponse response = postHttp("/fp-setup-unknown", new byte[0]);
        assertEquals(HttpResponseStatus.NOT_FOUND, response.status());
        response.release();
    }

    @Test
    void rtspFpSetupWithV3StillHandled() {
        // Same 16-byte setup-1 used by FairPlaySessionFairPlayTest (version 3).
        byte[] v3 = new byte[]{70, 80, 76, 89, 3, 1, 1, 0, 0, 0, 0, 4, 2, 0, 0, -69};
        FullHttpRequest req = new DefaultFullHttpRequest(
                RtspVersions.RTSP_1_0, HttpMethod.POST, "/fp-setup",
                Unpooled.wrappedBuffer(v3));
        req.headers().set("Active-Remote", SESSION);
        req.headers().set(HttpHeaderNames.CONTENT_LENGTH, v3.length);
        req.headers().set("CSeq", "1");

        channel.writeInbound(req);
        channel.checkException();
        FullHttpResponse response = channel.readOutbound();
        assertEquals(HttpResponseStatus.OK, response.status());
        assertTrue(response.content().readableBytes() > 0, "RTSP FairPlay v3 must return a body");
        response.release();
    }

    @Test
    void serverInfoUsesCompactHlsProbeProfileNotMdns() {
        String xml = new String(PropertyListUtil.prepareServerInfoResponse(), StandardCharsets.UTF_8);
        assertTrue(xml.contains(Long.toString(AdvertisedReceiver.SERVER_INFO_FEATURES)), xml);
        assertTrue(xml.contains(AdvertisedReceiver.SERVER_INFO_SOURCE_VERSION), xml);
        assertFalse(xml.contains(Long.toString(AdvertisedReceiver.FEATURES)), xml);
        assertFalse(xml.contains(AdvertisedReceiver.SOURCE_VERSION), xml);
        assertFalse(xml.contains(AdvertisedReceiver.MODEL), xml);
    }

    @Test
    void getServerInfoReturnsOkWithCompactProfile() {
        FullHttpRequest req = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/server-info");
        req.headers().set("X-Apple-Session-ID", SESSION);
        channel.writeInbound(req);
        channel.checkException();
        FullHttpResponse response = channel.readOutbound();
        assertEquals(HttpResponseStatus.OK, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        response.release();
        assertTrue(body.contains(Long.toString(AdvertisedReceiver.SERVER_INFO_FEATURES)), body);
        assertTrue(body.contains(AdvertisedReceiver.SERVER_INFO_SOURCE_VERSION), body);
    }

    private FullHttpResponse postHttp(String path, byte[] body) {
        FullHttpRequest req = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, path,
                Unpooled.wrappedBuffer(body));
        req.headers().set("X-Apple-Session-ID", SESSION);
        req.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        channel.writeInbound(req);
        channel.checkException();
        FullHttpResponse response = channel.readOutbound();
        if (response == null) {
            throw new AssertionError("expected HTTP response for " + path + " (must not silently drop)");
        }
        return response;
    }
}
