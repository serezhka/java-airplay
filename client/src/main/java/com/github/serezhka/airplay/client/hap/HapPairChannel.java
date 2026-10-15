package com.github.serezhka.airplay.client.hap;

import java.util.Map;

/** Minimal control-channel surface for HAP pair-setup / pair-verify. */
public interface HapPairChannel {

    String getHkpVersion();

    Map<Integer, byte[]> pairSetupTlv(byte[] tlvBody) throws Exception;

    Map<Integer, byte[]> pairVerifyTlv(byte[] tlvBody) throws Exception;

    void enableControlEncryption(byte[] sharedSecret) throws Exception;
}
