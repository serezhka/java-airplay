package com.github.serezhka.airplay.server.internal.handler.util;

import com.dd.plist.BinaryPropertyListWriter;
import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import com.github.serezhka.airplay.lib.ReceiverProfile;
import com.github.serezhka.airplay.server.AirPlayConfig;
import com.github.serezhka.airplay.server.AirPlayConsumer;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

@Slf4j
public class PropertyListUtil {

    public static byte[] prepareInfoResponse(AirPlayConfig airPlayConfig) throws Exception {
        NSDictionary audioFormat100 = new NSDictionary();
        audioFormat100.put("audioInputFormats", 67108860);
        audioFormat100.put("audioOutputFormats", 67108860);
        audioFormat100.put("type", 100);

        NSDictionary audioFormat101 = new NSDictionary();
        audioFormat101.put("audioInputFormats", 67108860);
        audioFormat101.put("audioOutputFormats", 67108860);
        audioFormat101.put("type", 101);

        NSArray audioFormats = new NSArray(audioFormat100, audioFormat101);

        NSDictionary audioLatency100 = new NSDictionary();
        audioLatency100.put("audioType", "default");
        audioLatency100.put("inputLatencyMicros", false);
        audioLatency100.put("type", 100);

        NSDictionary audioLatency101 = new NSDictionary();
        audioLatency101.put("audioType", "default");
        audioLatency101.put("inputLatencyMicros", false);
        audioLatency101.put("type", 101);

        NSArray audioLatencies = new NSArray(audioLatency100, audioLatency101);

        NSDictionary display = new NSDictionary();
        display.put("features", 14);
        display.put("height", airPlayConfig.getHeight());
        display.put("heightPhysical", false);
        display.put("heightPixels", airPlayConfig.getHeight());
        display.put("maxFPS", airPlayConfig.getFps());
        display.put("overscanned", false);
        display.put("refreshRate", 60);
        display.put("rotation", false);
        display.put("uuid", "e5f7a68d-7b0f-4305-984b-974f677a150b");
        display.put("width", airPlayConfig.getWidth());
        display.put("widthPhysical", false);
        display.put("widthPixels", airPlayConfig.getWidth());

        NSArray displays = new NSArray(display);

        NSDictionary response = new NSDictionary();
        response.put("audioFormats", audioFormats);
        response.put("audioLatencies", audioLatencies);
        response.put("displays", displays);
        response.put("features", ReceiverProfile.FEATURES);
        response.put("keepAliveSendStatsAsBody", 1);
        response.put("model", ReceiverProfile.MODEL);
        response.put("name", "Apple TV");
        response.put("pi", "b08f5a79-db29-4384-b456-a4784d9e6055");
        response.put("sourceVersion", ReceiverProfile.SOURCE_VERSION);
        response.put("statusFlags", ReceiverProfile.STATUS_FLAGS);
        response.put("vv", ReceiverProfile.VV);
        // response.put("pk", new NSData("XYMxJlYMsZoUGTcneJbw/UN7poAeshCsTDnZAHLXDag="));

        return BinaryPropertyListWriter.writeToArray(response);
    }

    public static byte[] prepareSetupAudioResponse(int dataPort, int controlPort) throws Exception {
        NSArray streams = new NSArray(1);
        NSDictionary dataStream = new NSDictionary();
        dataStream.put("dataPort", dataPort);
        dataStream.put("type", 96);
        dataStream.put("controlPort", controlPort);
        streams.setValue(0, dataStream);

        NSDictionary response = new NSDictionary();
        response.put("streams", streams);

        return BinaryPropertyListWriter.writeToArray(response);
    }

    public static byte[] prepareSetupVideoResponse(int dataPort, int eventPort, int timingPort) throws Exception {
        NSArray streams = new NSArray(1);
        NSDictionary dataStream = new NSDictionary();
        dataStream.put("dataPort", dataPort);
        dataStream.put("type", 110);
        streams.setValue(0, dataStream);

        NSDictionary response = new NSDictionary();
        response.put("streams", streams);
        response.put("eventPort", eventPort);
        response.put("timingPort", timingPort);

        return BinaryPropertyListWriter.writeToArray(response);
    }

    public static byte[] prepareServerInfoResponse() {
        NSDictionary response = new NSDictionary();
        response.put("features", 119); // 130367356919L -> leads to HTTP fp-setup, fp-setup2
        response.put("protovers", 1.0);
        response.put("srcvers", 101.28);
        return response.toXMLPropertyList().getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] preparePlaybackInfoResponse(AirPlayConsumer.PlaybackInfo playbackInfo) {
        NSDictionary response = new NSDictionary();
        double duration = Math.max(0, playbackInfo.duration());
        double position = Math.max(0, playbackInfo.position());
        if (duration > 0) {
            position = Math.min(position, duration);
        }
        double rate = playbackInfo.rate() <= 0 ? 0 : 1;
        response.put("duration", duration);
        NSDictionary loadedTimeRanges = new NSDictionary();
        loadedTimeRanges.put("duration", duration);
        loadedTimeRanges.put("start", 0.0);
        response.put("loadedTimeRanges", new NSArray(loadedTimeRanges));
        boolean hasDuration = duration > 0;
        // Do NOT force rate=0 when position≈duration. That made short VOD ads look like a
        // user pause; YouTube then stuck until Skip (ControlHandler pins rate=1 while waiting
        // for playlistRemove — dump 20260916-164913 kept rate=1 through the gap).
        //
        // Buffer flags match known receiver /playback-info templates (and
        // reverse_engineering/get_playback_info_response.txt): empty=true, full=false,
        // keepUp=true while readyToPlay. Flipping empty/full with hasDuration diverged from
        // that and is a candidate cause of VOD ad EOS hangs.
        if (hasDuration) {
            response.put("playbackBufferEmpty", true);
            response.put("playbackBufferFull", false);
            response.put("playbackLikelyToKeepUp", true);
            response.put("readyToPlay", true);
        } else {
            response.put("playbackBufferEmpty", true);
            response.put("playbackBufferFull", false);
            response.put("playbackLikelyToKeepUp", false);
            response.put("readyToPlay", false);
        }
        response.put("position", position);
        response.put("rate", rate);
        NSDictionary seekableTimeRanges = new NSDictionary();
        seekableTimeRanges.put("duration", duration);
        seekableTimeRanges.put("start", 0.0);
        response.put("seekableTimeRanges", new NSArray(seekableTimeRanges));
        log.debug("Playback info: duration={}, position={}, rate={}", duration, position, rate);
        return response.toXMLPropertyList().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Playback state event sent to the client over the reverse HTTP channel.
     * States: {@code loading}, {@code playing}, {@code paused}, {@code stopped}.
     */
    public static byte[] preparePlaybackStateEvent(String state) {
        return preparePlaybackStateEvent(state, 1, null, null);
    }

    /**
     * Reverse {@code POST /event} with {@code category=video} and a {@code state}.
     *
     * @param reason optional; working VOD EOS uses {@code "ended"} with {@code stopped}
     * @param itemUuid optional playlist-item uuid under {@code params}
     */
    public static byte[] preparePlaybackStateEvent(String state, int reverseSessionId,
                                                   String itemUuid, String reason) {
        NSDictionary event = new NSDictionary();
        event.put("category", "video");
        event.put("sessionID", reverseSessionId);
        event.put("state", state);
        if (reason != null) {
            event.put("reason", reason);
        }
        if (itemUuid != null) {
            NSDictionary params = new NSDictionary();
            params.put("uuid", itemUuid);
            event.put("params", params);
        }
        return event.toXMLPropertyList().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Typed video event ({@code itemPlayedToEnd}, etc.) with top-level {@code uuid}.
     */
    public static byte[] prepareVideoTypedEvent(String type, int reverseSessionId, String itemUuid) {
        NSDictionary event = new NSDictionary();
        event.put("category", "video");
        event.put("sessionID", reverseSessionId);
        event.put("type", type);
        if (itemUuid != null) {
            event.put("uuid", itemUuid);
        }
        return event.toXMLPropertyList().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * {@code itemRemoved}: {@code sessionID} is the play UUID string (X-Apple-Session-ID), not the
     * integer reverse-event session id.
     */
    public static byte[] prepareItemRemovedEvent(String playSessionId, String itemUuid) {
        NSDictionary event = new NSDictionary();
        event.put("category", "video");
        event.put("sessionID", playSessionId);
        event.put("type", "itemRemoved");
        if (itemUuid != null) {
            event.put("uuid", itemUuid);
        }
        return event.toXMLPropertyList().getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] prepareCurrentItemChangedEvent(int reverseSessionId) {
        NSDictionary event = new NSDictionary();
        event.put("category", "video");
        event.put("sessionID", reverseSessionId);
        event.put("type", "currentItemChanged");
        return event.toXMLPropertyList().getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] prepareEmptyPropertyResponse() {
        return new NSDictionary().toXMLPropertyList().getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] prepareEventRequest(String sessionId, String listUri, int requestId) {
        NSDictionary headers = new NSDictionary();
        headers.put("X-Playback-Session-Id", sessionId);
        headers.put("User-Agent", "AppleCoreMedia/1.0.0.11B554a (Apple TV; U; CPU OS 7_0_4 like Mac OS X; en_us");

        NSDictionary request = new NSDictionary();
        request.put("FCUP_Response_ClientInfo", 1);
        request.put("FCUP_Response_ClientRef", 40030004);
        request.put("FCUP_Response_Headers", headers);
        request.put("FCUP_Response_RequestID", requestId);
        request.put("FCUP_Response_URL", listUri);
        request.put("sessionID", 1);

        NSDictionary wrapper = new NSDictionary();
        wrapper.put("request", request);
        wrapper.put("sessionID", 1);
        wrapper.put("type", "unhandledURLRequest");

        return wrapper.toXMLPropertyList().getBytes(StandardCharsets.UTF_8);
    }
}
