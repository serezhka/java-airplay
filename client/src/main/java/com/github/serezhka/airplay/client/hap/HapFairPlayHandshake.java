package com.github.serezhka.airplay.client.hap;

import lombok.extern.slf4j.Slf4j;

/**
 * HAP TLV pair-setup followed by FairPlay {@code fp-setup} when the receiver supports it.
 */
@Slf4j
public final class HapFairPlayHandshake {

    private HapFairPlayHandshake() {
    }

    /**
     * @param pin receiver on-screen pairing code (required)
     * @return SRP session key used for control-channel encryption
     */
    public static byte[] perform(HapPairChannel control, String pin) throws Exception {
        HapPairing pairing = new HapPairing();
        byte[] sharedSecret = pairing.perform(control, pin);
        log.info("Skipping /fp-setup (use FairPlay only when FPSAP is advertised)");
        return sharedSecret;
    }
}
