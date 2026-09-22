package com.github.serezhka.airplay.client.hap;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * SRP-6a client for HAP Pair-Setup (SHA-512, 3072-bit RFC 5054 group, username {@code Pair-Setup}).
 */
public final class HapSrp6a {

    private static final String USERNAME = "Pair-Setup";

    /** RFC 5054 3072-bit safe prime. */
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

    private final SecureRandom random = new SecureRandom();

    private byte[] salt;
    private BigInteger A;
    private BigInteger a;
    private BigInteger B;
    private byte[] publicA;
    private byte[] proofM1;
    private byte[] sharedSecretK;

    public void start(byte[] salt, byte[] serverPublicB, String pin) throws GeneralSecurityException {
        if (salt == null || salt.length == 0) {
            throw new IllegalArgumentException("salt required");
        }
        if (serverPublicB == null || serverPublicB.length == 0) {
            throw new IllegalArgumentException("server public key required");
        }
        if (pin == null || pin.isBlank()) {
            throw new IllegalArgumentException("PIN required");
        }
        this.salt = salt.clone();
        this.B = new BigInteger(1, serverPublicB);
        if (B.mod(N).equals(BigInteger.ZERO)) {
            throw new IllegalStateException("SRP server public key is invalid (B % N == 0)");
        }

        // k = H(PAD(N) | PAD(g))
        byte[] kBytes = HapCrypto.sha512(pad(N), pad(G));
        BigInteger k = new BigInteger(1, kBytes);

        // HAP / Apple: 32-byte random private exponent (not a full-width mod-N sample).
        byte[] aBytes = new byte[32];
        random.nextBytes(aBytes);
        this.a = new BigInteger(1, aBytes);
        if (a.equals(BigInteger.ZERO)) {
            a = BigInteger.ONE;
        }
        this.A = G.modPow(a, N);
        this.publicA = pad(A);

        // u = H(PAD(A) | PAD(B))
        byte[] uBytes = HapCrypto.sha512(pad(A), pad(B));
        BigInteger u = new BigInteger(1, uBytes);

        // x = H(s | H(I | ":" | P))
        String password = pin.trim();
        byte[] identityHash = HapCrypto.sha512(
                (USERNAME + ":" + password).getBytes(StandardCharsets.UTF_8));
        byte[] xBytes = HapCrypto.sha512(salt, identityHash);
        BigInteger x = new BigInteger(1, xBytes);

        // S = (B - k * g^x) ^ (a + u*x) mod N
        // Exponent must NOT be reduced mod N (that breaks M1 vs accessory).
        BigInteger gx = G.modPow(x, N);
        BigInteger base = B.subtract(k.multiply(gx)).mod(N);
        BigInteger exp = a.add(u.multiply(x));
        BigInteger S = base.modPow(exp, N);
        this.sharedSecretK = HapCrypto.sha512(pad(S));

        // M1 = H(H(N) XOR H(g) | H(I) | s | A | B | K)
        // H(g) uses the *unpadded* generator (single byte 0x05) — HAP / fast-srp-hap.
        // k above still uses PAD(g); only the M1 XOR hash differs.
        byte[] nnXorG = xor(HapCrypto.sha512(pad(N)), HapCrypto.sha512(new byte[]{5}));
        byte[] hUser = HapCrypto.sha512(USERNAME.getBytes(StandardCharsets.UTF_8));
        this.proofM1 = HapCrypto.sha512(nnXorG, hUser, salt, pad(A), pad(B), sharedSecretK);
    }

    public byte[] getPublicA() {
        return publicA.clone();
    }

    public byte[] getClientProof() {
        return proofM1.clone();
    }

    public byte[] getSharedSecret() {
        return sharedSecretK.clone();
    }

    public void verifyServerProof(byte[] serverProofM2) throws GeneralSecurityException {
        byte[] expected = HapCrypto.sha512(pad(A), proofM1, sharedSecretK);
        if (!constantTimeEquals(expected, serverProofM2)) {
            throw new IllegalStateException("SRP server proof mismatch (wrong PIN or corrupt M4)");
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
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
