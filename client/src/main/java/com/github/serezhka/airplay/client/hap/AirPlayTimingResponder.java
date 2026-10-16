package com.github.serezhka.airplay.client.hap;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Answers AirPlay NTP timing requests ({@code 0x80 0xd2}) with replies ({@code 0x80 0xd3}).
 * <p>
 * Receivers send timing queries to the sender's advertised {@code timingPort}
 * during SETUP; without replies, streams SETUP (type 110) can hang.
 * <p>
 * Replies use <b>boot-relative</b> time plus the NTP epoch so the receiver can
 * compare them with mirror packet timestamps. Wall-clock replies make every
 * frame look decades late relative to the mirror clock.
 */
@Slf4j
public final class AirPlayTimingResponder implements AutoCloseable {

    private static final int PACKET_LEN = 32;
    private static final long NTP_UNIX_OFFSET_SECONDS = 2208988800L;

    private final DatagramSocket socket;
    private final DatagramSocket neighbor1;
    private final DatagramSocket neighbor2;
    private final Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger replies = new AtomicInteger();
    private final long bootNanos;

    public AirPlayTimingResponder() throws SocketException {
        this(System.nanoTime());
    }

    public AirPlayTimingResponder(long bootNanos) throws SocketException {
        this.bootNanos = bootNanos;
        Bound bound = bindTimingWithNeighbors();
        this.socket = bound.timing;
        this.neighbor1 = bound.n1;
        this.neighbor2 = bound.n2;
        this.socket.setSoTimeout(200);
        this.thread = new Thread(this::loop, "airplay-timing");
        this.thread.setDaemon(true);
        this.thread.start();
        log.info("timing UDP listening on {}", socket.getLocalPort());
    }

    private static Bound bindTimingWithNeighbors() throws SocketException {
        for (int base = 40_000; base < 50_000; base++) {
            DatagramSocket timing = null;
            DatagramSocket n1 = null;
            DatagramSocket n2 = null;
            try {
                timing = new DatagramSocket(new InetSocketAddress("0.0.0.0", base));
                n1 = new DatagramSocket(new InetSocketAddress("0.0.0.0", base + 1));
                n2 = new DatagramSocket(new InetSocketAddress("0.0.0.0", base + 2));
                return new Bound(timing, n1, n2);
            } catch (SocketException e) {
                closeQuiet(timing);
                closeQuiet(n1);
                closeQuiet(n2);
            }
        }
        return new Bound(new DatagramSocket(new InetSocketAddress("0.0.0.0", 0)), null, null);
    }

    private record Bound(DatagramSocket timing, DatagramSocket n1, DatagramSocket n2) {
    }

    public long bootNanos() {
        return bootNanos;
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

    private byte[] ntpNow() {
        double elapsed = Math.max((System.nanoTime() - bootNanos) / 1_000_000_000.0, 0.0);
        long sec = (long) elapsed + NTP_UNIX_OFFSET_SECONDS;
        long frac = (long) ((elapsed - (long) elapsed) * (1L << 32)) & 0xFFFFFFFFL;
        ByteBuffer bb = ByteBuffer.allocate(8);
        bb.putInt((int) sec);
        bb.putInt((int) frac);
        return bb.array();
    }

    @Override
    public void close() {
        running.set(false);
        closeQuiet(socket);
        closeQuiet(neighbor1);
        closeQuiet(neighbor2);
        try {
            thread.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuiet(DatagramSocket s) {
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }
}
