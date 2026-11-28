package com.github.serezhka.airplay.server.internal.handler.audio;

import com.github.serezhka.airplay.protocol.FairPlaySession;
import com.github.serezhka.airplay.server.Playback;
import com.github.serezhka.airplay.server.internal.packet.AudioPacket;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;

import java.util.function.LongSupplier;

@Slf4j
public class AudioHandler extends ChannelInboundHandlerAdapter {

    @FunctionalInterface
    interface FrameDecryptor {
        void decrypt(byte[] audio, int length) throws Exception;
    }

    private final FrameDecryptor decryptor;
    private final Playback playback;
    private final AudioResendRequests resend;
    private final LongSupplier clock;
    private final AudioRtpBuffer buffer = new AudioRtpBuffer();

    public AudioHandler(FairPlaySession airPlay, Playback playback) {
        this(airPlay::decryptAudio, playback, AudioResendRequests.NOOP, System::nanoTime);
    }

    public AudioHandler(FairPlaySession airPlay, Playback playback, AudioResendRequests resend) {
        this(airPlay::decryptAudio, playback, resend, System::nanoTime);
    }

    AudioHandler(FrameDecryptor decryptor, Playback playback, AudioResendRequests resend, LongSupplier clock) {
        this.decryptor = decryptor;
        this.playback = playback;
        this.resend = resend != null ? resend : AudioResendRequests.NOOP;
        this.clock = clock != null ? clock : System::nanoTime;
    }

    AudioHandler(FrameDecryptor decryptor, Playback playback) {
        this(decryptor, playback, AudioResendRequests.NOOP, System::nanoTime);
    }

    public synchronized void reset() {
        buffer.reset();
    }

    /**
     * RTSP FLUSH/RECORD with {@code RTP-Info}: drop pre-flush audio and re-anchor the window.
     */
    public synchronized void flush(AudioRtpInfo info) {
        buffer.flush(info != null ? info : AudioRtpInfo.empty());
    }

    /** Session stream latency (frames @ sample rate) for resend give-up. */
    public synchronized void setStreamLatency(int frames, int sampleRateHz) {
        buffer.setStreamLatency(frames, sampleRateHz);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        offer((AudioPacket) msg);
    }

    /** Data-port RTP or control-port resend reply — same reorder path. */
    public void offer(AudioPacket packet) {
        if (packet == null) {
            return;
        }
        synchronized (this) {
            long now = clock.getAsLong();
            buffer.offer(packet, now);
            drain(now);
            // Resend requests need multi-second buffering; live path leaves this off
            // (see AudioRtpBuffer.resendRequestsEnabled) and skips holes instead.
            if (AudioRtpBuffer.resendRequestsEnabled) {
                for (AudioRtpBuffer.Resend gap : buffer.dueResends(now)) {
                    try {
                        resend.requestResend(gap.firstSeq(), gap.count());
                    } catch (Exception e) {
                        log.debug("Audio resend request failed: {}", e.toString());
                    }
                }
            }
        }
    }

    private void drain(long nowNs) {
        while (true) {
            var ready = buffer.pollReady();
            if (ready.isPresent()) {
                emit(ready.get());
                continue;
            }
            int skipped = buffer.skipExpiredMissing(nowNs);
            if (skipped <= 0) {
                return;
            }
            log.debug("Audio RTP gave up on {} missing packet(s)", skipped);
            try {
                playback.onAudioGap(skipped);
            } catch (Exception e) {
                log.debug("onAudioGap: {}", e.toString());
            }
        }
    }

    private void emit(AudioPacket packet) {
        int seq = packet.getSequenceNumber() & 0xFFFF;
        int size = packet.getEncodedAudioSize();
        try {
            decryptor.decrypt(packet.getEncodedAudio(), size);
            playback.onAudio(packet.getEncodedAudio(), 0, size);
        } catch (Exception e) {
            log.warn("Audio decrypt/deliver failed seq={} size={}: {}", seq, size, e.toString());
        }
    }

    int nextRead() {
        return buffer.nextRead();
    }

    int missingCount() {
        return buffer.missingCount();
    }
}
