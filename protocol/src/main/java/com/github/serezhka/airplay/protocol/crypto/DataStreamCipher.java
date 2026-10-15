package com.github.serezhka.airplay.protocol.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ChaCha20-Poly1305 for mirror video payloads after HKP pair-verify.
 * Salt {@link AirPlayKeyLabels#dataStreamSalt(long)}, info
 * {@link AirPlayKeyLabels#DATA_STREAM_OUTPUT_KEY}. The 128-byte packet header is AAD.
 */
public final class DataStreamCipher {

    private final byte[] key;
    private final AtomicLong nonce = new AtomicLong();

    public DataStreamCipher(byte[] sharedSecret, long streamConnectionId)
            throws GeneralSecurityException {
        this.key = HkdfSha512.derive(
                sharedSecret,
                AirPlayKeyLabels.dataStreamSalt(streamConnectionId).getBytes(StandardCharsets.UTF_8),
                AirPlayKeyLabels.DATA_STREAM_OUTPUT_KEY.getBytes(StandardCharsets.UTF_8),
                32);
    }

    /** Encrypt {@code plain}; {@code header} is associated data (not ciphertext). */
    public byte[] seal(byte[] plain, byte[] header) throws GeneralSecurityException {
        long n = nonce.getAndIncrement();
        return chacha(Cipher.ENCRYPT_MODE, plain, header, counterNonce(n));
    }

    /** Decrypt with an explicit nonce counter (tests / reopen). */
    public byte[] open(byte[] sealed, byte[] header, long counter)
            throws GeneralSecurityException {
        return chacha(Cipher.DECRYPT_MODE, sealed, header, counterNonce(counter));
    }

    private byte[] chacha(int mode, byte[] input, byte[] aad, byte[] nonce12)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");
        cipher.init(mode, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce12));
        cipher.updateAAD(aad);
        return cipher.doFinal(input);
    }

    /** 12-byte nonce: 4 zero bytes + little-endian counter. */
    static byte[] counterNonce(long counter) {
        byte[] n = new byte[12];
        ByteBuffer.wrap(n, 4, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(counter);
        return n;
    }
}
