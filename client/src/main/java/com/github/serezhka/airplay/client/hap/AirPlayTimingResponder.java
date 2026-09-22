package com.github.serezhka.airplay.client.hap;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Answers AirPlay NTP timing requests ({@code 0x80 0xd2}) with replies ({@code 0x80 0xd3}).
 * <p>
 * Samsung (and other receivers) send timing queries to the sender's advertised
 * {@code timingPort} during SETUP; without replies, streams SETUP (type 110) hangs.
 */
@Slf4j
public final class AirPlayTimingResponder implements AutoCloseable {

    private static final int PACKET_LEN = 32;
    private static final long NTP_UNIX_OFFSET_SECONDS = 2208988800L;

    private final DatagramSocket socket;
    private final Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger replies = new AtomicInteger();

    public AirPlayTimingResponder() throws SocketException {
        // Explicit IPv4 wildcard — dual-stack quirks can drop TV NTP queries otherwise.
        this.socket = new DatagramSocket(new java.net.InetSocketAddress("0.0.0.0", 0));
        this.socket.setSoTimeout(200);
        this.thread = new Thread(this::loop, "airplay-timing");
        this.thread.setDaemon(true);
        this.thread.start();
        log.info("timing UDP listening on {}", socket.getLocalPort());
    }

    public int localPort() {
        return socket.getLocalPort();
    }

    public int replyCount() {
        return replies.get();
    }

    private void loop() {
        byte[] buf = new byte[64];
        while (running.get()) {
            try {
                DatagramPacket in = new DatagramPacket(buf, buf.length);
                socket.receive(in);
                if (in.getLength() < PACKET_LEN) {
                    continue;
                }
                if ((buf[1] & 0xff) != 0xd2) {
                    continue;
                }
                byte[] reply = new byte[PACKET_LEN];
                reply[0] = (byte) 0x80;
                reply[1] = (byte) 0xd3;
                reply[2] = buf[2];
                reply[3] = buf[3];
                // origin = request transmit timestamp (bytes 24..31)
                System.arraycopy(buf, 24, reply, 8, 8);
                byte[] now = ntpNow();
                System.arraycopy(now, 0, reply, 16, 8);
                System.arraycopy(ntpNow(), 0, reply, 24, 8);
                socket.send(new DatagramPacket(reply, reply.length, in.getSocketAddress()));
                int n = replies.incrementAndGet();
                if (n <= 5 || n % 50 == 0) {
                    log.info("timing reply #{} → {}", n, in.getSocketAddress());
                }
            } catch (java.net.SocketTimeoutException ignored) {
                // poll stop flag
            } catch (IOException e) {
                if (running.get()) {
                    log.warn("timing responder: {}", e.getMessage());
                }
                break;
            }
        }
    }

    private static byte[] ntpNow() {
        double t = System.currentTimeMillis() / 1000.0 + NTP_UNIX_OFFSET_SECONDS;
        long sec = (long) t;
        long frac = (long) ((t - sec) * (1L << 32));
        ByteBuffer bb = ByteBuffer.allocate(8);
        bb.putInt((int) sec);
        bb.putInt((int) frac);
        return bb.array();
    }

    @Override
    public void close() {
        running.set(false);
        socket.close();
        try {
            thread.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
