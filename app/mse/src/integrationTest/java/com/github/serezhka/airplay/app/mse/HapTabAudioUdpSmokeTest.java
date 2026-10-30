package com.github.serezhka.airplay.app.mse;

import com.github.serezhka.airplay.client.audio.AudioSource;
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
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Canned ADTS → {@link HapAudioClient} SHK encrypt → local UDP (no TV / no Chromium).
 * Browser ADTS path is covered by {@link BrowserCastAudioAdtsSmokeTest}.
 */
@Tag("mse")
class HapTabAudioUdpSmokeTest {

    @Test
    @Timeout(30)
    void encryptedRtpReachesLocalUdp() throws Exception {
        AtomicInteger fed = new AtomicInteger();
        AudioSources.install(onFrame -> new PulsingAdtsSource(onFrame, fed));

        byte[] shk = new byte[16];
        Arrays.fill(shk, (byte) 0x5a);

        AtomicInteger udpPackets = new AtomicInteger();
        AtomicBoolean running = new AtomicBoolean(true);
        try (DatagramSocket sock = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
            sock.setSoTimeout(200);
            int port = sock.getLocalPort();
            Thread recv = new Thread(() -> {
                byte[] buf = new byte[4096];
                while (running.get()) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buf, buf.length);
                        sock.receive(packet);
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
                long deadline = System.currentTimeMillis() + 10_000;
                while (System.currentTimeMillis() < deadline
                        && (fed.get() < 5 || udpPackets.get() < 5)) {
                    Thread.sleep(50);
                }
                assertTrue(fed.get() >= 5, "expected ADTS fed into HapAudioClient, got " + fed.get());
                assertTrue(udpPackets.get() >= 5,
                        "expected HAP RTP UDP packets, got " + udpPackets.get());
            } finally {
                running.set(false);
                recv.join(2_000);
            }
        }
    }

    /** Minimal ADTS frame (AAC-LC silence-ish payload) paced for HAP send. */
    private static final class PulsingAdtsSource implements AudioSource {
        private final AtomicBoolean run = new AtomicBoolean(true);
        private final Thread thread;

        PulsingAdtsSource(Consumer<byte[]> onFrame, AtomicInteger fed) {
            // 7-byte ADTS header + 16 bytes payload (padded by HapAudioClient).
            byte[] frame = new byte[]{
                    (byte) 0xff, (byte) 0xf1, 0x50, 0x40, 0x01, (byte) 0xbf, (byte) 0xfc,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
            };
            thread = new Thread(() -> {
                while (run.get()) {
                    onFrame.accept(frame.clone());
                    fed.incrementAndGet();
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }, "pulsing-adts");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void close() {
            run.set(false);
            thread.interrupt();
        }
    }
}
