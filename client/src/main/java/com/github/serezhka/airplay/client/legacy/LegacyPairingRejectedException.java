package com.github.serezhka.airplay.client.legacy;

/**
 * Receiver refused legacy empty {@code POST /pair-setup} (typical modern HAP path).
 */
public final class LegacyPairingRejectedException extends IllegalStateException {

    public LegacyPairingRejectedException(String message) {
        super(message);
    }
}
