package com.github.serezhka.airplay.server.internal.handler.control;

import com.dd.plist.BinaryPropertyListWriter;
import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import com.github.serezhka.airplay.protocol.media.AirPlayStreamType;
import com.github.serezhka.airplay.server.AirPlayConfig;
import com.github.serezhka.airplay.server.internal.handler.session.HlsPlaylistState;
import com.github.serezhka.airplay.server.internal.handler.session.Session;
import com.github.serezhka.airplay.server.internal.handler.session.SessionManager;
import com.github.serezhka.airplay.server.internal.handler.util.AirPlayVolume;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.rtsp.RtspMethods;
import io.netty.handler.codec.rtsp.RtspVersions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Control HTTP/RTSP paths must drive {@link com.github.serezhka.airplay.server.Playback}
 * without a native player. Uses {@link EmbeddedChannel} — no production hooks.
 */
class ControlHandlerPlaybackWiringTest {

    private static final String SESSION = "control-wiring-session";

    private SessionManager sessions;
    private RecordingPlayback playback;
    private HlsFcupService fcup;
    private EmbeddedChannel channel;

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
    void rtspSetParameterVolumeReachesPlayback() {
        FullHttpRequest req = new DefaultFullHttpRequest(
                RtspVersions.RTSP_1_0, RtspMethods.SET_PARAMETER, "/",
                Unpooled.copiedBuffer("volume: -6.000000\r\n", StandardCharsets.US_ASCII));
        req.headers().set("Active-Remote", SESSION);
        req.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/parameters");
        req.headers().set(HttpHeaderNames.CONTENT_LENGTH, req.content().readableBytes());

        writeInbound(req);

        assertTrue(playback.eventsContainInOrder("volume"));
        assertEquals(AirPlayVolume.fromDecibels(-6.0), playback.volume(), 1e-6);
    }

    @Test
    void rtspGetParameterReflectsPlaybackVolume() {
        playback.onVolume(0.25);
        playback.clearEvents();

        FullHttpRequest req = new DefaultFullHttpRequest(
                RtspVersions.RTSP_1_0, RtspMethods.GET_PARAMETER, "/");
        req.headers().set("Active-Remote", SESSION);

        channel.writeInbound(req);
        channel.checkException();
        FullHttpResponse response = channel.readOutbound();
        assertEquals(HttpResponseStatus.OK, response.status());
        String body = response.content().toString(StandardCharsets.US_ASCII);
        response.release();
        assertTrue(body.startsWith("volume:"), body);
        assertEquals(AirPlayVolume.toDecibels(0.25),
                Double.parseDouble(body.substring("volume:".length()).trim()), 0.05);
    }

    @Test
    void rateZeroThenOnePauseResume() {
        seedHlsStarted();

        postHttp("/rate?value=0");
        assertTrue(playback.eventsContainInOrder("pause"));

        playback.clearEvents();
        postHttp("/rate?value=1");
        assertTrue(playback.eventsContainInOrder("resume"));
    }

    @Test
    void rateIgnoredWhileWaitingForMasterChange() {
        HlsPlaylistState hls = seedHlsStarted();
        hls.setWaitingForMasterChange(true);

        postHttp("/rate?value=0");
        assertFalse(playback.events().contains("pause"));
        postHttp("/rate?value=1");
        assertFalse(playback.events().contains("resume"));
    }

    @Test
    void scrubSeeksWhenPlaybackStarted() {
        seedHlsStarted();

        postHttp("/scrub?position=4.5");
        assertTrue(playback.eventsContainInOrder("seek"));
        assertEquals(4.5, playback.lastSeek(), 1e-6);
    }

    @Test
    void stopAndStop2RemovePlaylist() {
        seedHlsStarted();

        postHttp("/stop");
        assertTrue(playback.eventsContainInOrder("playlistRemoved"));
        assertTrue(sessions.getSession(SESSION).getHlsPlaylistState() == null);

        playback.clearEvents();
        seedHlsStarted();
        postHttp("/stop2");
        assertTrue(playback.eventsContainInOrder("playlistRemoved"));
    }

    @Test
    void playlistRemoveActionStopsHls() throws Exception {
        seedHlsStarted();

        NSDictionary action = new NSDictionary();
        action.put("type", "playlistRemove");
        byte[] body = BinaryPropertyListWriter.writeToArray(action);

        FullHttpRequest req = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/action",
                Unpooled.wrappedBuffer(body));
        req.headers().set("X-Apple-Session-ID", SESSION);
        req.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        writeInbound(req);

        assertTrue(playback.eventsContainInOrder("playlistRemoved"));
    }

    @Test
    void rtspTeardownVideoDisconnectsVideoOnly() throws Exception {
        seedHlsStarted();
        writeInbound(rtspTeardownWithStream(AirPlayStreamType.SCREEN));
        assertTrue(playback.eventsContainInOrder("videoDisconnect"));
        assertFalse(playback.events().contains("audioDisconnect"));
        assertFalse(playback.events().contains("playlistRemoved"),
                "stream TEARDOWN must not stop HLS");
        assertTrue(sessions.getSession(SESSION).getHlsPlaylistState() != null);
    }

    @Test
    void rtspTeardownAudioDisconnectsAudioOnly() throws Exception {
        writeInbound(rtspTeardownWithStream(AirPlayStreamType.AUDIO_REALTIME));
        assertTrue(playback.eventsContainInOrder("audioDisconnect"));
        assertFalse(playback.events().contains("videoDisconnect"));
    }

    @Test
    void rtspTeardownFullStopsMirrorAudioAndHls() throws Exception {
        seedHlsStarted();
        // Plist without "streams" → full session teardown (audio + video + HLS).
        byte[] body = BinaryPropertyListWriter.writeToArray(new NSDictionary());
        FullHttpRequest req = new DefaultFullHttpRequest(
                RtspVersions.RTSP_1_0, RtspMethods.TEARDOWN, "/",
                Unpooled.wrappedBuffer(body));
        req.headers().set("Active-Remote", SESSION);
        req.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);

        writeInbound(req);

        assertTrue(playback.eventsContainInOrder("audioDisconnect"));
        assertTrue(playback.eventsContainInOrder("videoDisconnect"));
        assertTrue(playback.eventsContainInOrder("playlistRemoved"));
        assertNull(sessions.getSession(SESSION).getHlsPlaylistState());
    }

    private FullHttpRequest rtspTeardownWithStream(int streamType) throws Exception {
        NSDictionary stream = new NSDictionary();
        stream.put("type", streamType);
        NSArray streams = new NSArray(1);
        streams.setValue(0, stream);
        NSDictionary teardown = new NSDictionary();
        teardown.put("streams", streams);
        byte[] body = BinaryPropertyListWriter.writeToArray(teardown);

        FullHttpRequest req = new DefaultFullHttpRequest(
                RtspVersions.RTSP_1_0, RtspMethods.TEARDOWN, "/",
                Unpooled.wrappedBuffer(body));
        req.headers().set("Active-Remote", SESSION);
        req.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        return req;
    }

    private HlsPlaylistState seedHlsStarted() {
        Session session = sessions.getSession(SESSION);
        HlsPlaylistState hls = new HlsPlaylistState(
                "mlhls://localhost/master.m3u8",
                "http://127.0.0.1/playlist/master.m3u8?session=" + SESSION);
        hls.markPlaybackStarted();
        hls.setPlaybackRate(1);
        session.setHlsPlaylistState(hls);
        return hls;
    }

    private void postHttp(String uri) {
        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri);
        req.headers().set("X-Apple-Session-ID", SESSION);
        writeInbound(req);
    }

    /**
     * Terminal inbound handlers do not forward the message, so {@code writeInbound}
     * returns false — that is expected. Always drain outbound + checkException.
     */
    private void writeInbound(FullHttpRequest req) {
        channel.writeInbound(req);
        channel.checkException();
        drainOutbound();
    }

    private void drainOutbound() {
        Object msg;
        while ((msg = channel.readOutbound()) != null) {
            if (msg instanceof FullHttpResponse response) {
                response.release();
            }
        }
    }
}
