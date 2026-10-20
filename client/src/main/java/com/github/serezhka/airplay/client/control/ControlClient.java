package com.github.serezhka.airplay.client.control;

import com.github.serezhka.airplay.client.netty.NettyEventLoops;

import com.dd.plist.BinaryPropertyListParser;
import com.dd.plist.BinaryPropertyListWriter;
import com.dd.plist.NSDictionary;
import com.dd.plist.PropertyListFormatException;
import com.github.serezhka.airplay.client.hap.HapControlCipher;
import com.github.serezhka.airplay.client.hap.HapPairChannel;
import com.github.serezhka.airplay.client.hap.Tlv8;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufInputStream;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.rtsp.*;
import io.netty.handler.logging.ByteBufFormat;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Map;

@Slf4j
public class ControlClient implements Runnable, AutoCloseable, HapPairChannel {

    private final String address;
    private final int port;

    private int cseqIdx;

    private ControlHandler controlHandler;
    private volatile EventLoopGroup workerGroup;
    private volatile io.netty.channel.Channel channel;

    /**
     * {@code X-Apple-HKP} for HAP TLV pairing.
     * Default {@code 4} = transient AirPlay (works with on-screen PIN + SETUP/RECORD).
     */
    private String hkpVersion = "4";

    public ControlClient(String address, int port) throws InterruptedException {
        this.address = address;
        this.port = port;
        Thread t = new Thread(this, "airplay-control-client");
        t.setDaemon(true);
        t.start();
        synchronized (this) {
            wait(15_000L);
        }
        if (controlHandler == null) {
            throw new IllegalStateException("control client failed to start");
        }
    }

    @Override
    public void run() {
        workerGroup = eventLoopGroup();
        var bootstrap = new Bootstrap();

        controlHandler = new ControlHandler();

        try {
            bootstrap.group(workerGroup);
            bootstrap.channel(socketChannelClass());
            bootstrap.option(ChannelOption.SO_KEEPALIVE, true);
            bootstrap.remoteAddress(address, port);
            bootstrap.handler(new ChannelInitializer<SocketChannel>() {
                protected void initChannel(SocketChannel ch) {
                    ChannelPipeline p = ch.pipeline();
                    // Encrypt/decrypt are added only after pair-setup M4 — keeping them
                    // in the pipeline during plaintext pairing breaks M3/M4.
                    // Outbound uses framed bytes via sendRaw (RtspEncoder header quirks
                    // are rejected by some receivers). Decoder still parses responses.
                    p.addLast("decoder", new RtspDecoder());
                    p.addLast("logger", new LoggingHandler(LogLevel.DEBUG, ByteBufFormat.SIMPLE));
                    p.addLast("aggregator", new HttpObjectAggregator(64 * 1024));
                    p.addLast("control handler", controlHandler);
                }
            });

            var channelFuture = bootstrap.connect().sync();
            channel = channelFuture.channel();
            log.info("Control client started");

            synchronized (this) {
                this.notifyAll();
            }

            channelFuture.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            synchronized (this) {
                this.notifyAll();
            }
        } finally {
            log.info("Control client stopped");
            if (workerGroup != null) {
                workerGroup.shutdownGracefully();
            }
        }
    }

    @Override
    public void close() {
        io.netty.channel.Channel ch = channel;
        if (ch != null) {
            ch.close();
        }
        EventLoopGroup group = workerGroup;
        if (group != null) {
            group.shutdownGracefully(0, 2, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    public NSDictionary requestInfo() throws InterruptedException, PropertyListFormatException, IOException {
        FullHttpResponse response = exchangeRaw("GET", "/info", Map.of(), new byte[0]);
        return (NSDictionary) BinaryPropertyListParser.parse(new ByteBufInputStream(response.content()));
    }

    public byte[] pairSetup() throws InterruptedException {
        FullHttpResponse response = exchangeRaw("POST", "/pair-setup", Map.of(), new byte[0]);
        byte[] responseBytes = new byte[response.content().readableBytes()];
        response.content().readBytes(responseBytes);
        return responseBytes;
    }

    /**
     * Asks the receiver to show an on-screen pairing PIN. Best-effort.
     */
    public void pairPinStart() throws InterruptedException {
        FullHttpResponse response = exchangeRaw("POST", "/pair-pin-start",
                Map.of("X-Apple-HKP", hkpVersion), new byte[0]);
        HttpResponseStatus status = response.status();
        if (status.code() >= 400) {
            throw new IllegalStateException("POST /pair-pin-start → " + status);
        }
        log.info("POST /pair-pin-start → {} (look for PIN on the receiver)", status);
    }

    /**
     * HAP TLV {@code POST /pair-setup} with {@code X-Apple-HKP} + {@code application/pairing+tlv8}.
     */
    public Map<Integer, byte[]> pairSetupTlv(byte[] tlvBody) throws InterruptedException {
        return exchangeTlv("/pair-setup", tlvBody);
    }

    /**
     * HAP TLV {@code POST /pair-verify} with {@code X-Apple-HKP} + {@code application/pairing+tlv8}.
     */
    public Map<Integer, byte[]> pairVerifyTlv(byte[] tlvBody) throws InterruptedException {
        return exchangeTlv("/pair-verify", tlvBody);
    }

    /**
     * HomeKit protocol version for pairing: {@code 4} = transient AirPlay (PIN often {@code 3939}),
     * {@code 3} = HomeKit-style PIN pairing. Wrong value yields a well-formed M2 then
     * {@code authentication} at M4.
     */
    public void setHkpVersion(String version) {
        this.hkpVersion = version == null || version.isBlank() ? "4" : version.trim();
    }

    public String getHkpVersion() {
        return hkpVersion;
    }

    public byte[] pairVerify(byte[] requestBytes) throws InterruptedException {
        FullHttpResponse response = exchangeRaw("POST", "/pair-verify", Map.of(), requestBytes);
        byte[] responseBytes = new byte[response.content().readableBytes()];
        response.content().readBytes(responseBytes);
        return responseBytes;
    }

    private Map<Integer, byte[]> exchangeTlv(String path, byte[] tlvBody) throws InterruptedException {
        FullHttpResponse response = exchangeRaw("POST", path, Map.of(
                "Content-Type", "application/pairing+tlv8",
                "X-Apple-HKP", hkpVersion
        ), tlvBody);
        HttpResponseStatus status = response.status();
        byte[] responseBytes = new byte[response.content().readableBytes()];
        response.content().readBytes(responseBytes);

        if (status.code() >= 400) {
            throw new IllegalStateException(
                    "POST " + path + " → " + status + " (body " + responseBytes.length + " bytes). "
                            + "Modern receivers need HAP TLV + X-Apple-HKP; legacy empty pair-setup gets 400.");
        }
        if (responseBytes.length == 0) {
            throw new IllegalStateException("POST " + path + " → " + status + " with empty body");
        }
        return Tlv8.decode(responseBytes);
    }

    public byte[] fpSetup(byte[] requestBytes) throws InterruptedException {
        FullHttpResponse response = exchangeRaw("POST", "/fp-setup",
                Map.of("X-Apple-ET", "32"), requestBytes);
        byte[] responseBytes = new byte[response.content().readableBytes()];
        response.content().readBytes(responseBytes);
        return responseBytes;
    }

    /**
     * Enable HAP control-channel encryption after a successful pair-setup M4 (SRP session key).
     */
    public void enableControlEncryption(byte[] srpSharedSecret) throws Exception {
        HapControlCipher cipher = HapControlCipher.forClient(srpSharedSecret);
        controlHandler.enableControlEncryption(cipher);
    }

    public NSDictionary rtspSetup(NSDictionary setup) throws InterruptedException, IOException, PropertyListFormatException {
        byte[] body = BinaryPropertyListWriter.writeToArray(setup);
        FullHttpResponse response = exchangeRaw("SETUP", "rtsp://" + address + "/stream", Map.of(
                "Content-Type", "application/x-apple-binary-plist",
                "X-Apple-ProtocolVersion", "1"
        ), body);
        HttpResponseStatus status = response.status();
        if (status.code() >= 400) {
            throw new IllegalStateException("SETUP → " + status);
        }

        if (response.content().readableBytes() > 0) {
            return (NSDictionary) BinaryPropertyListParser.parse(new ByteBufInputStream(response.content()));
        }
        return null;
    }

    /**
     * Raw RTSP framing for every method. Avoids Netty {@code RtspEncoder} header quirks
     * that some receivers reject on streams SETUP.
     */
    private FullHttpResponse exchangeRaw(String method, String path, Map<String, String> extraHeaders, byte[] body)
            throws InterruptedException {
        int cseq = ++cseqIdx;
        StringBuilder h = new StringBuilder();
        h.append(method).append(' ').append(path).append(" RTSP/1.0\r\n");
        h.append("CSeq: ").append(cseq).append("\r\n");
        h.append("User-Agent: AirPlay/770.10.1\r\n");
        h.append("DACP-ID: AABBCCDDEE11\r\n");
        h.append("Active-Remote: 1234567890\r\n");
        h.append("Connection: keep-alive\r\n");
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                h.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
        }
        h.append("Content-Length: ").append(body == null ? 0 : body.length).append("\r\n\r\n");
        byte[] head = h.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] msg;
        if (body == null || body.length == 0) {
            msg = head;
        } else {
            msg = new byte[head.length + body.length];
            System.arraycopy(head, 0, msg, 0, head.length);
            System.arraycopy(body, 0, msg, head.length, body.length);
        }
        controlHandler.sendRaw(msg);
        return controlHandler.receive();
    }

    private EventLoopGroup eventLoopGroup() {
        return NettyEventLoops.create();
    }

    private Class<? extends SocketChannel> socketChannelClass() {
        return Epoll.isAvailable() ? EpollSocketChannel.class : NioSocketChannel.class;
    }
}
