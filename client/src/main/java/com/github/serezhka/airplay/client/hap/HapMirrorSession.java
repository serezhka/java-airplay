package com.github.serezhka.airplay.client.hap;

import com.dd.plist.NSArray;
import com.dd.plist.NSData;
import com.dd.plist.NSDictionary;
import com.github.serezhka.airplay.client.video.DataStreamVideoClient;
import com.github.serezhka.airplay.protocol.crypto.AirPlayKeyLabels;
import com.github.serezhka.airplay.protocol.crypto.DataStreamCipher;
import com.github.serezhka.airplay.protocol.media.AirPlayStreamType;
import com.github.serezhka.airplay.protocol.mirror.MirrorTimestampInfo;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

/**
 * HKP mirror session: pair-verify → audio+video SETUP → RECORD → DataStream ChaCha video.
 */
@Slf4j
public final class HapMirrorSession implements AutoCloseable {

    private static final String DEVICE_ID = "AA:BB:CC:DD:EE:11";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final HapSocketControl control;
    private final AirPlayTimingResponder timing;
    private final Socket eventSocket;
    private final DataStreamVideoClient video;
    private final long streamConnectionId;

    private HapMirrorSession(
            HapSocketControl control,
            AirPlayTimingResponder timing,
            Socket eventSocket,
            DataStreamVideoClient video,
            long streamConnectionId) {
        this.control = control;
        this.timing = timing;
        this.eventSocket = eventSocket;
        this.video = video;
        this.streamConnectionId = streamConnectionId;
    }

    /**
     * After Control-Salt is enabled on {@code control}, run audio→video SETUP and start
     * the DataStream video feed.
     *
     * @param sharedSecret pair-verify X25519 shared secret
     */
    public static HapMirrorSession startDataStream(
            HapSocketControl control, String host, int controlPort, byte[] sharedSecret)
            throws Exception {
        long bootNanos = System.nanoTime();
        AirPlayTimingResponder timing = new AirPlayTimingResponder(bootNanos);
        Socket eventSocket = null;
        try {
            long audioId = RANDOM.nextLong() & Long.MAX_VALUE;
            long videoId = RANDOM.nextLong() & Long.MAX_VALUE;
            String session = UUID.randomUUID().toString().toUpperCase();
            String audioUri = "rtsp://" + host + ":" + controlPort + "/" + Long.toUnsignedString(audioId);
            String videoUri = "rtsp://" + host + ":" + controlPort + "/" + Long.toUnsignedString(videoId);

            byte[] audioShk = new byte[32];
            RANDOM.nextBytes(audioShk);

            NSDictionary audioStream = new NSDictionary();
            audioStream.put("type", AirPlayStreamType.AUDIO_REALTIME);
            audioStream.put("streamConnectionID", audioId);
            audioStream.put("ct", 2);
            audioStream.put("spf", 352);
            audioStream.put("sr", 44100);
            audioStream.put("audioFormat", 0x40000);
            audioStream.put("audioFormatIndex", 0x12);
            audioStream.put("controlPort", timing.localPort() + 1);
            audioStream.put("audioMode", "default");
            audioStream.put("usingScreen", true);
            audioStream.put("latencyMin", 4410);
            audioStream.put("latencyMax", 4410);
            audioStream.put("redundantAudio", 0);
            audioStream.put("disableRetransmits", true);
            audioStream.put("shk", new NSData(audioShk));
            audioStream.put("isMedia", true);
            audioStream.put("supportsDynamicStreamID", true);
            NSArray audioStreams = new NSArray(1);
            audioStreams.setValue(0, audioStream);

            NSDictionary audioSetup = new NSDictionary();
            audioSetup.put("deviceID", DEVICE_ID);
            audioSetup.put("macAddress", DEVICE_ID);
            audioSetup.put("sessionUUID", session);
            audioSetup.put("sourceVersion", "280.33");
            audioSetup.put("timingProtocol", "NTP");
            audioSetup.put("timingPort", timing.localPort());
            audioSetup.put("model", "Linux");
            audioSetup.put("name", "testsrc");
            audioSetup.put("streams", audioStreams);

            log.info("HAP audio SETUP → timingPort={}", timing.localPort());
            NSDictionary audioReply = control.rtspSetup(audioUri, audioSetup);
            if (audioReply == null) {
                throw new IllegalStateException("audio SETUP returned empty body");
            }
            int eventPort = audioReply.containsKey("eventPort")
                    ? audioReply.get("eventPort").toJavaObject(Integer.class)
                    : 0;
            log.info("HAP audio SETUP ok eventPort={}", eventPort);
            if (eventPort > 0) {
                eventSocket = new Socket();
                eventSocket.connect(new InetSocketAddress(host, eventPort), 5000);
                eventSocket.setTcpNoDelay(true);
            }

            byte[] shk = Arrays.copyOf(
                    HapCrypto.hkdfSha512(sharedSecret,
                            AirPlayKeyLabels.CONTROL_SALT, AirPlayKeyLabels.CONTROL_WRITE_KEY, 32),
                    16);
            byte[] shiv = Arrays.copyOf(
                    HapCrypto.hkdfSha512(sharedSecret,
                            AirPlayKeyLabels.CONTROL_SALT, AirPlayKeyLabels.CONTROL_READ_KEY, 32),
                    16);

            NSDictionary videoStream = new NSDictionary();
            videoStream.put("type", AirPlayStreamType.SCREEN);
            videoStream.put("streamConnectionID", videoId);
            videoStream.put("shk", new NSData(shk));
            videoStream.put("shiv", new NSData(shiv));
            NSArray timestampInfo = new NSArray(MirrorTimestampInfo.NAMES.length);
            for (int i = 0; i < MirrorTimestampInfo.NAMES.length; i++) {
                NSDictionary entry = new NSDictionary();
                entry.put("name", MirrorTimestampInfo.NAMES[i]);
                timestampInfo.setValue(i, entry);
            }
            videoStream.put("timestampInfo", timestampInfo);
            NSArray videoStreams = new NSArray(1);
            videoStreams.setValue(0, videoStream);

            NSDictionary videoSetup = new NSDictionary();
            videoSetup.put("deviceID", DEVICE_ID);
            videoSetup.put("macAddress", DEVICE_ID);
            videoSetup.put("sessionUUID", session);
            videoSetup.put("sourceVersion", "280.33");
            videoSetup.put("isScreenMirroringSession", true);
            videoSetup.put("timingProtocol", "NTP");
            videoSetup.put("timingPort", timing.localPort());
            videoSetup.put("model", "Linux");
            videoSetup.put("name", "testsrc");
            videoSetup.put("streams", videoStreams);

            NSDictionary videoReply = control.rtspSetup(videoUri, videoSetup);
            if (videoReply == null || !videoReply.containsKey("streams")) {
                throw new IllegalStateException("video SETUP returned no streams");
            }
            int dataPort = ((NSDictionary) ((NSArray) videoReply.get("streams")).getArray()[0])
                    .get("dataPort").toJavaObject(Integer.class);

            control.rtspRecord(audioUri, Map.of(
                    "Range", "npt=0-",
                    "RTP-Info", "seq=0;rtptime=0"));
            log.info("HAP SETUP/RECORD ok → {}:{} streamConnectionID={} timingReplies={}",
                    host, dataPort, Long.toUnsignedString(videoId), timing.replyCount());

            DataStreamCipher cipher = new DataStreamCipher(sharedSecret, videoId);
            DataStreamVideoClient video = new DataStreamVideoClient(host, dataPort, cipher, bootNanos);
            return new HapMirrorSession(control, timing, eventSocket, video, videoId);
        } catch (Exception e) {
            if (eventSocket != null) {
                try {
                    eventSocket.close();
                } catch (Exception ignored) {
                }
            }
            timing.close();
            throw e;
        }
    }

    public DataStreamVideoClient video() {
        return video;
    }

    public long streamConnectionId() {
        return streamConnectionId;
    }

    @Override
    public void close() {
        if (video != null) {
            video.close();
        }
        if (eventSocket != null) {
            try {
                eventSocket.close();
            } catch (Exception ignored) {
            }
        }
        if (timing != null) {
            timing.close();
        }
        if (control != null) {
            try {
                control.close();
            } catch (Exception ignored) {
            }
        }
    }
}
