package com.github.serezhka.airplay.lib;

import com.github.serezhka.airplay.lib.internal.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;

/**
 * Responds on pairing setup, fairplay setup requests, decrypts data
 */
public class AirPlay {

    private final Pairing pairing;
    private final FairPlay fairplay;
    private final RTSP rtsp;

    private FairPlayVideoDecryptor fairPlayVideoDecryptor;
    private FairPlayAudioDecryptor fairPlayAudioDecryptor;

    public AirPlay() {
        pairing = new Pairing();
        fairplay = new FairPlay();
        rtsp = new RTSP();
    }

    /**
     * {@code /pair-setup}
     * <p>
     * Writes EdDSA public key bytes to output stream
     */
    public void pairSetup(OutputStream out) throws Exception {
        pairing.pairSetup(out);
    }

    /**
     * {@code /pair-verify}
     * <p>
     * On first request writes curve25519 public key + encrypted signature bytes to output stream;
     * On second request verifies signature
     */
    public void pairVerify(InputStream in, OutputStream out) throws Exception {
        pairing.pairVerify(in, out);
    }

    /**
     * Pair was verified successfully
     */
    public boolean isPairVerified() {
        return pairing.isPairVerified();
    }

    /**
     * {@code /fp-setup}
     * <p>
     * Writes fp-setup response bytes to output stream
     */
    public void fairPlaySetup(InputStream in, OutputStream out) throws Exception {
        fairplay.fairPlaySetup(in, out);
    }

    /**
     * {@code RTSP SETUP}
     * <p>
     * Sets encrypted EAS key and IV or retrieves media stream info
     */
    public Optional<MediaStreamInfo> rtspSetup(InputStream in) throws Exception {
        return rtsp.setup(in);
    }

    /**
     * {@code RTSP TEARDOWN}
     * <p>
     * Retrieves media stream info
     */
    public Optional<MediaStreamInfo> rtspTeardown(InputStream in) throws Exception {
        return rtsp.teardown(in);
    }


    public byte[] getFairPlayAesKey() {
        return fairplay.decryptAesKey(rtsp.getEkey());
    }

    /**
     * Video decrypt needs FairPlay {@code ekey} + streamConnectionID.
     * Shared secret is optional: absent when the client skips pairing (features bit 27 off).
     */
    public boolean isFairPlayVideoDecryptorReady() {
        return rtsp.getEkey() != null && rtsp.getStreamConnectionID() != null;
    }

    /**
     * Audio decrypt needs FairPlay {@code ekey} + {@code eiv}.
     * Shared secret is optional (same as video).
     */
    public boolean isFairPlayAudioDecryptorReady() {
        return rtsp.getEkey() != null && rtsp.getEiv() != null;
    }

    public void decryptVideo(byte[] video) throws Exception {
        if (fairPlayVideoDecryptor == null) {
            if (!isFairPlayVideoDecryptorReady()) {
                throw new IllegalStateException("FairPlayVideoDecryptor not ready!");
            }
            byte[] secret = pairing.getSharedSecret() != null ? pairing.getSharedSecret() : new byte[0];
            fairPlayVideoDecryptor = new FairPlayVideoDecryptor(getFairPlayAesKey(), secret, rtsp.getStreamConnectionID());
        }
        fairPlayVideoDecryptor.decrypt(video);
    }

    public void decryptAudio(byte[] audio, int audioLength) throws Exception {
        if (fairPlayAudioDecryptor == null) {
            if (!isFairPlayAudioDecryptorReady()) {
                throw new IllegalStateException("FairPlayAudioDecryptor not ready!");
            }
            byte[] secret = pairing.getSharedSecret() != null ? pairing.getSharedSecret() : new byte[0];
            fairPlayAudioDecryptor = new FairPlayAudioDecryptor(getFairPlayAesKey(), rtsp.getEiv(), secret);
        }
        fairPlayAudioDecryptor.decrypt(audio, audioLength);
    }
}
