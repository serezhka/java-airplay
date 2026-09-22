package com.github.serezhka.airplay.client.hap;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Optional;

/**
 * Post pair-setup AirPlay control framing: {@code [u16 LE len][ciphertext][16-byte tag]}.
 * Keys from HKDF-SHA512 over the SRP session key ({@code Control-Salt} / Write|Read info).
 */
public final class HapControlCipher {

    public static final int BLOCK_MAX = 1024;
    private static final int TAG_LEN = 16;
    private static final int LEN_LEN = 2;

    private final Encryptor encryptor;
    private final Decryptor decryptor;

    private HapControlCipher(Encryptor encryptor, Decryptor decryptor) {
        this.encryptor = encryptor;
        this.decryptor = decryptor;
    }

    /** Client role: encrypt with Write key, decrypt with Read key. */
    public static HapControlCipher forClient(byte[] srpSharedSecret) throws GeneralSecurityException {
        byte[] writeKey = HapCrypto.hkdfSha512(srpSharedSecret,
                "Control-Salt", "Control-Write-Encryption-Key", 32);
        byte[] readKey = HapCrypto.hkdfSha512(srpSharedSecret,
                "Control-Salt", "Control-Read-Encryption-Key", 32);
        return new HapControlCipher(new Encryptor(writeKey), new Decryptor(readKey));
    }

    public Encryptor encryptor() {
        return encryptor;
    }

    public Decryptor decryptor() {
        return decryptor;
    }

    public static final class Encryptor {
        private final byte[] key;
        private long counter;

        Encryptor(byte[] key) {
            this.key = Arrays.copyOf(key, key.length);
        }

        public byte[] encrypt(byte[] plaintext) {
            byte[] out = new byte[0];
            for (int offset = 0; offset < plaintext.length; offset += BLOCK_MAX) {
                int blockLen = Math.min(BLOCK_MAX, plaintext.length - offset);
                byte[] block = Arrays.copyOfRange(plaintext, offset, offset + blockLen);
                byte[] length = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
                        .putShort((short) blockLen).array();
                out = concat(out, length, seal(block, length, counter));
                counter++;
            }
            return out;
        }

        private byte[] seal(byte[] plaintext, byte[] aad, long blockCounter) {
            try {
                Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"),
                        nonceSpec(blockCounter));
                cipher.updateAAD(aad);
                return cipher.doFinal(plaintext);
            } catch (Exception e) {
                throw new IllegalStateException("control encrypt failed", e);
            }
        }
    }

    public static final class Decryptor {
        private final byte[] key;
        private long counter;

        Decryptor(byte[] key) {
            this.key = Arrays.copyOf(key, key.length);
        }

        public Optional<DecryptResult> decryptAvailable(byte[] input) {
            int pos = 0;
            byte[] plaintext = new byte[0];
            while (pos + LEN_LEN <= input.length) {
                int len = ByteBuffer.wrap(input, pos, LEN_LEN).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
                int blockEnd = pos + LEN_LEN + len + TAG_LEN;
                if (blockEnd > input.length) {
                    break;
                }
                byte[] aad = Arrays.copyOfRange(input, pos, pos + LEN_LEN);
                byte[] ciphertext = Arrays.copyOfRange(input, pos + LEN_LEN, blockEnd);
                try {
                    plaintext = concat(plaintext, open(ciphertext, aad, counter));
                } catch (AEADBadTagException e) {
                    throw new IllegalStateException("control auth tag mismatch", e);
                }
                counter++;
                pos = blockEnd;
            }
            if (pos == 0 && plaintext.length == 0) {
                return Optional.empty();
            }
            return Optional.of(new DecryptResult(plaintext, pos));
        }

        private byte[] open(byte[] ciphertext, byte[] aad, long blockCounter) throws AEADBadTagException {
            try {
                Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "ChaCha20"),
                        nonceSpec(blockCounter));
                cipher.updateAAD(aad);
                return cipher.doFinal(ciphertext);
            } catch (AEADBadTagException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("control decrypt failed", e);
            }
        }
    }

    public record DecryptResult(byte[] plaintext, int consumedBytes) {
    }

    private static IvParameterSpec nonceSpec(long blockCounter) {
        byte[] nonce = new byte[12];
        ByteBuffer.wrap(nonce, 4, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(blockCounter);
        return new IvParameterSpec(nonce);
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }

    /** Debug helper for wire dumps. */
    public static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
