package com.github.serezhka.airplay.lib;

import com.github.serezhka.airplay.lib.internal.*;

import java.io.InputStream;
import java.io.OutputStream;

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
     * {@code /info}
     * <p>
     * Writes server info to output stream
     */
    public void info(int width, int height, int fps, OutputStream out) throws Exception {
        pairing.info(width, height, fps, out);
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
     * Retrieves information about media stream from RTSP SETUP request
     *
     * @return null if there's no stream info
     */
    public MediaStreamInfo rtspGetMediaStreamInfo(InputStream in) throws Exception {
        return rtsp.getMediaStreamInfo(in);
    }

    /**
     * {@code RTSP SETUP ENCRYPTION}
     * <p>
     * Retrieves encrypted EAS key and IV
     */
    public void rtspSetupEncryption(InputStream in) throws Exception {
        rtsp.setup(in);
    }

    /**
     * {@code RTSP SETUP VIDEO}
     * <p>
     * Writes video event, data and timing ports info to output stream
     */
    public void rtspSetupVideo(OutputStream out, int videoDataPort, int videoEventPort, int videoTimingPort) throws Exception {
        rtsp.setupVideo(out, videoDataPort, videoEventPort, videoTimingPort);
    }

    /**
     * {@code RTSP SETUP AUDIO}
     * <p>
     * Writes audio control and data ports info to output stream
     */
    public void rtspSetupAudio(OutputStream out, int audioDataPort, int audioControlPort) throws Exception {
        rtsp.setupAudio(out, audioDataPort, audioControlPort);
    }

    public byte[] getFairPlayAesKey() {
        return fairplay.decryptAesKey(rtsp.getEncryptedAESKey());
    }

    /**
     * @return {@code true} if we got shared secret during pairing, ekey & stream connection id during RTSP SETUP
     */
    public boolean isFairPlayVideoDecryptorReady() {
        return pairing.getSharedSecret() != null && rtsp.getEncryptedAESKey() != null && rtsp.getStreamConnectionID() != null;
    }

    /**
     * @return {@code true} if we got shared secret during pairing, ekey & eiv during RTSP SETUP
     */
    public boolean isFairPlayAudioDecryptorReady() {
        return pairing.getSharedSecret() != null && rtsp.getEncryptedAESKey() != null && rtsp.getEiv() != null;
    }

    public void decryptVideo(byte[] video) throws Exception {
        if (fairPlayVideoDecryptor == null) {
            if (!isFairPlayVideoDecryptorReady()) {
                throw new IllegalStateException("FairPlayVideoDecryptor not ready!");
            }
            fairPlayVideoDecryptor = new FairPlayVideoDecryptor(getFairPlayAesKey(), pairing.getSharedSecret(), rtsp.getStreamConnectionID());
        }
        fairPlayVideoDecryptor.decrypt(video);
    }

    public void decryptAudio(byte[] audio, int audioLength) throws Exception {
        if (fairPlayAudioDecryptor == null) {
            if (!isFairPlayAudioDecryptorReady()) {
                throw new IllegalStateException("FairPlayAudioDecryptor not ready!");
            }
            fairPlayAudioDecryptor = new FairPlayAudioDecryptor(getFairPlayAesKey(), rtsp.getEiv(), pairing.getSharedSecret());
        }
        fairPlayAudioDecryptor.decrypt(audio, audioLength);
    }
}
