package com.github.serezhka.airplay.app.mse;

import com.github.serezhka.airplay.app.mse.cast.BrowserCastAudioSource;
import com.github.serezhka.airplay.client.audio.AudioSources;
import com.github.serezhka.airplay.client.hap.HapAudioClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tab ADTS → {@link HapAudioClient} SHK encrypt → local UDP (no TV). Covers the HAP audio send path.
 */
@Tag("mse")
class HapTabAudioUdpSmokeTest {

    @Test
    @Timeout(120)
    void encryptedRtpReachesLocalUdp() throws Exception {
        MseCastTestSupport.assumeCastEnvironment();
        MseCastTestSupport.prepareBrowserCastProperties();
        System.setProperty("airplay.mse.cast.enableAudio", "true");

        AtomicInteger adts = new AtomicInteger();
        AudioSources.install(onFrame -> new BrowserCastAudioSource(frame -> {
            if (frame != null && frame.length > 0) {
                adts.incrementAndGet();
            }
            onFrame.accept(frame);
        }));

        byte[] shk = new byte[16];
        Arrays.fill(shk, (byte) 0x5a);

        AtomicInteger udpPackets = new AtomicInteger();
        AtomicBoolean running = new AtomicBoolean(true);
        try (DatagramSocket sock = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
            sock.setSoTimeout(500);
            int port = sock.getLocalPort();
            Thread recv = new Thread(() -> {
                byte[] buf = new byte[4096];
                while (running.get()) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buf, buf.length);
                        sock.receive(packet);
                        // RTP header is 12 bytes; encrypted AAC payload follows.
                        if (packet.getLength() > 12) {
                            udpPackets.incrementAndGet();
                        }
                    } catch (SocketTimeoutException ignored) {
                    } catch (Exception e) {
                        break;
                    }
                }
            }, "hap-audio-udp-smoke");
            recv.setDaemon(true);
            recv.start();

            try (HapAudioClient client = new HapAudioClient("127.0.0.1", port, shk)) {
                long deadline = System.currentTimeMillis() + 60_000;
                while (System.currentTimeMillis() < deadline
                        && (adts.get() < 5 || udpPackets.get() < 5)) {
                    Thread.sleep(250);
                }
                assertTrue(adts.get() >= 5, "expected ADTS from tab, got " + adts.get());
                assertTrue(udpPackets.get() >= 5,
                        "expected HAP RTP UDP packets, got " + udpPackets.get());
            } finally {
                running.set(false);
                recv.join(2_000);
            }
        }
    }
}
