package com.github.serezhka.airplay.client.hap;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Self-contained SRP-6a round-trip using the same HAP formulas as {@link HapSrp6a}.
 */
class HapSrp6aTest {

    private static final BigInteger N = new BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E08"
                    + "8A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B"
                    + "302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9"
                    + "A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE6"
                    + "49286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8"
                    + "FD24CF5F83655D23DCA3AD961C62F356208552BB9ED529077096966D"
                    + "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3BE39E772C"
                    + "180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718"
                    + "3995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D"
                    + "04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7D"
                    + "B3970F85A6E1E4C7ABF5AE8CDB0933D71E8C94E04A25619DCEE3D226"
                    + "1AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B18177B200C"
                    + "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFC"
                    + "E0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF", 16);
    private static final BigInteger G = BigInteger.valueOf(5);
    private static final int N_BYTES = 384;

    @Test
    void clientProofAcceptedByMatchingServer() throws Exception {
        String pin = "9804";
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);

        // Server verifier v = g^x mod N
        byte[] identityHash = HapCrypto.sha512(
                ("Pair-Setup:" + pin).getBytes(StandardCharsets.UTF_8));
        BigInteger x = new BigInteger(1, HapCrypto.sha512(salt, identityHash));
        BigInteger v = G.modPow(x, N);

        byte[] kBytes = HapCrypto.sha512(pad(N), pad(G));
        BigInteger k = new BigInteger(1, kBytes);

        byte[] bBytes = new byte[32];
        new SecureRandom().nextBytes(bBytes);
        BigInteger b = new BigInteger(1, bBytes);
        BigInteger B = k.multiply(v).add(G.modPow(b, N)).mod(N);
        byte[] serverB = pad(B);

        HapSrp6a client = new HapSrp6a();
        client.start(salt, serverB, pin);

        // Server side of S / M1 check
        BigInteger A = new BigInteger(1, client.getPublicA());
        BigInteger u = new BigInteger(1, HapCrypto.sha512(pad(A), pad(B)));
        BigInteger S = A.multiply(v.modPow(u, N)).modPow(b, N);
        byte[] K = HapCrypto.sha512(pad(S));

        byte[] nnXorG = xor(HapCrypto.sha512(pad(N)), HapCrypto.sha512(new byte[]{5}));
        byte[] hUser = HapCrypto.sha512("Pair-Setup".getBytes(StandardCharsets.UTF_8));
        byte[] expectedM1 = HapCrypto.sha512(nnXorG, hUser, salt, pad(A), pad(B), K);

        assertArrayEquals(expectedM1, client.getClientProof());
        assertArrayEquals(K, client.getSharedSecret());

        byte[] serverM2 = HapCrypto.sha512(pad(A), expectedM1, K);
        assertDoesNotThrow(() -> client.verifyServerProof(serverM2));
    }

    private static byte[] pad(BigInteger v) {
        byte[] raw = v.toByteArray();
        if (raw.length == N_BYTES) {
            return raw;
        }
        if (raw.length == N_BYTES + 1 && raw[0] == 0) {
            return Arrays.copyOfRange(raw, 1, raw.length);
        }
        if (raw.length > N_BYTES) {
            return Arrays.copyOfRange(raw, raw.length - N_BYTES, raw.length);
        }
        byte[] out = new byte[N_BYTES];
        System.arraycopy(raw, 0, out, N_BYTES - raw.length, raw.length);
        return out;
    }

    private static byte[] xor(byte[] a, byte[] b) {
        byte[] out = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = (byte) (a[i] ^ b[i]);
        }
        return out;
    }
}
