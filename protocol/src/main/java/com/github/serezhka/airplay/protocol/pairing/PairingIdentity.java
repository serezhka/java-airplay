package com.github.serezhka.airplay.protocol.pairing;

import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.KeyPairGenerator;

import java.security.KeyPair;
import java.util.HexFormat;

/**
 * Ed25519 identity for one receiver process.
 * The public key is what mDNS advertises as {@code pk} and what {@code /pair-setup} returns.
 */
public final class PairingIdentity {

    private final KeyPair keyPair;

    private PairingIdentity(KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    public static PairingIdentity generate() {
        return new PairingIdentity(new KeyPairGenerator().generateKeyPair());
    }

    public byte[] publicKey() {
        return ((EdDSAPublicKey) keyPair.getPublic()).getAbyte();
    }

    public String publicKeyHex() {
        return HexFormat.of().formatHex(publicKey());
    }

    KeyPair keyPair() {
        return keyPair;
    }
}
