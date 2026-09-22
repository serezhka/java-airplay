package com.github.serezhka.airplay.client.hap;

import com.github.serezhka.airplay.client.control.ControlClient;
import lombok.extern.slf4j.Slf4j;

/**
 * HAP TLV pair-setup / pair-verify followed by FairPlay {@code fp-setup} when the receiver supports it.
 * Explicit HomeKit-style path — not a silent fallback from legacy pairing.
 */
@Slf4j
public final class HapFairPlayHandshake {

    private HapFairPlayHandshake() {
    }

    /**
     * @param pin TV on-screen pairing code (required)
     * @return Curve25519 shared secret from pair-verify
     */
    public static byte[] perform(ControlClient controlClient, String pin) throws Exception {
        HapPairing pairing = new HapPairing();
        byte[] sharedSecret = pairing.perform(controlClient, pin);

        // Skip /fp-setup when the receiver has no FairPlay SAP — a 404 on the encrypted
        // control channel has been observed to poison later SETUP (phase2 → 400) on Samsung.
        log.info("Skipping /fp-setup (use FairPlay only when FPSAP is advertised)");
        return sharedSecret;
    }
}
