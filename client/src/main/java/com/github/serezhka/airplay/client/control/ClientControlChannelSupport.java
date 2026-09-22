package com.github.serezhka.airplay.client.control;

import com.github.serezhka.airplay.client.hap.HapControlCipher;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;

/** Netty handlers for HAP control-channel encryption after pair-setup M4. */
@Slf4j
public final class ClientControlChannelSupport {

    public static final AttributeKey<HapControlCipher> CIPHER =
            AttributeKey.valueOf("airplay.client.hapControlCipher");

    private ClientControlChannelSupport() {
    }

    public static void activate(ChannelHandlerContext ctx, HapControlCipher cipher) {
        ctx.channel().attr(CIPHER).set(cipher);
        log.info("Control channel encryption enabled (client)");
    }

    public static final class DecryptDecoder extends ByteToMessageDecoder {

        private byte[] buffer = new byte[0];

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            HapControlCipher cipher = ctx.channel().attr(CIPHER).get();
            if (cipher == null) {
                if (in.isReadable()) {
                    out.add(in.readRetainedSlice(in.readableBytes()));
                }
                return;
            }

            byte[] chunk = new byte[in.readableBytes()];
            in.readBytes(chunk);
            buffer = concat(buffer, chunk);

            var result = cipher.decryptor().decryptAvailable(buffer);
            if (result.isEmpty()) {
                return;
            }

            HapControlCipher.DecryptResult decrypted = result.get();
            if (decrypted.consumedBytes() > 0) {
                buffer = Arrays.copyOfRange(buffer, decrypted.consumedBytes(), buffer.length);
            }
            if (decrypted.plaintext().length > 0) {
                out.add(Unpooled.wrappedBuffer(decrypted.plaintext()));
            }
        }

        private static byte[] concat(byte[] left, byte[] right) {
            byte[] combined = new byte[left.length + right.length];
            System.arraycopy(left, 0, combined, 0, left.length);
            System.arraycopy(right, 0, combined, left.length, right.length);
            return combined;
        }
    }

    public static final class EncryptHandler extends ChannelOutboundHandlerAdapter {

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            HapControlCipher cipher = ctx.channel().attr(CIPHER).get();
            if (!(msg instanceof ByteBuf buf) || cipher == null) {
                ctx.write(msg, promise);
                return;
            }
            try {
                byte[] plain = new byte[buf.readableBytes()];
                buf.readBytes(plain);
                // Temporary wire dump for Samsung SETUP 400 diagnosis.
                if (plain.length > 8 && plain[0] == 'S' && plain[1] == 'E') {
                    String head = new String(plain, 0, Math.min(plain.length, 800), java.nio.charset.StandardCharsets.ISO_8859_1);
                    int cut = head.indexOf("\r\n\r\n");
                    log.warn("OUTBOUND SETUP plaintext headers ({}B total):\n{}",
                            plain.length, cut >= 0 ? head.substring(0, cut) : head);
                    if (cut >= 0 && cut + 4 < plain.length) {
                        byte[] body = Arrays.copyOfRange(plain, cut + 4, plain.length);
                        log.warn("OUTBOUND SETUP body hex ({}B): {}",
                                body.length, HapControlCipher.toHex(body));
                        try {
                            java.nio.file.Files.write(
                                    java.nio.file.Path.of("/tmp/ap_probe/java_setup_" + plain.length + ".bplist"),
                                    body);
                        } catch (Exception ignored) {
                        }
                    }
                }
                byte[] encrypted = cipher.encryptor().encrypt(plain);
                if (plain.length > 8 && plain[0] == 'S' && plain[1] == 'E') {
                    log.warn("OUTBOUND SETUP ciphertext len={} head={}",
                            encrypted.length,
                            HapControlCipher.toHex(Arrays.copyOf(encrypted, Math.min(24, encrypted.length))));
                }
                ctx.write(Unpooled.wrappedBuffer(encrypted), promise);
            } finally {
                buf.release();
            }
        }
    }
}
