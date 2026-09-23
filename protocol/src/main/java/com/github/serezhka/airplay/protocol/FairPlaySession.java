package com.github.serezhka.airplay.protocol;

import com.github.serezhka.airplay.protocol.fairplay.FairPlayAudioDecryptor;
import com.github.serezhka.airplay.protocol.fairplay.FairPlayHandshake;
import com.github.serezhka.airplay.protocol.fairplay.FairPlayVideoDecryptor;
import com.github.serezhka.airplay.protocol.media.MediaStreamInfo;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import com.github.serezhka.airplay.protocol.pairing.PairingHandshake;
import com.github.serezhka.airplay.protocol.rtsp.RtspMediaSetup;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;

/**
 * Crypto and RTSP media-setup state for one AirPlay connection.
 */
public final class FairPlaySession {

    private final PairingHandshake pairing = new PairingHandshake();
    private final FairPlayHandshake fairPlay = new FairPlayHandshake();
    private final RtspMediaSetup rtsp = new RtspMediaSetup();

    private FairPlayVideoDecryptor fairPlayVideoDecryptor;
    private FairPlayAudioDecryptor fairPlayAudioDecryptor;

    public PairingHandshake pairing() {
        return pairing;
    }

    public FairPlayHandshake fairPlay() {
        return fairPlay;
    }

    public RtspMediaSetup rtsp() {
        return rtsp;
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
        fairPlay.fairPlaySetup(in, out);
    }

    /**
     * {@code RTSP SETUP}
     * <p>
     * Sets encrypted EAS key and IV or retrieves media stream info
     */
    public Optional<MediaStreamInfo> rtspSetup(InputStream in) throws Exception {
        Optional<MediaStreamInfo> mediaStreamInfo = rtsp.setup(in);
        // YouTube ads / next item send a new type-110 stream with a new streamConnectionID.
        // Reusing the previous AES-CTR decryptor yields garbage NALs for the new stream.
        if (mediaStreamInfo.isPresent() && mediaStreamInfo.get() instanceof VideoStreamInfo) {
            fairPlayVideoDecryptor = null;
        }
        return mediaStreamInfo;
    }

    /**
     * {@code RTSP TEARDOWN}
     * <p>
     * Retrieves media stream info
     */
    public Optional<MediaStreamInfo> rtspTeardown(InputStream in) throws Exception {
        Optional<MediaStreamInfo> mediaStreamInfo = rtsp.teardown(in);
        if (mediaStreamInfo.isPresent() && mediaStreamInfo.get() instanceof VideoStreamInfo) {
            fairPlayVideoDecryptor = null;
        }
        return mediaStreamInfo;
    }


    public byte[] getFairPlayAesKey() {
        return fairPlay.decryptAesKey(rtsp.getEkey());
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
