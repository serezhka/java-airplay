package com.github.serezhka.airplay.server.internal.handler.audio;

import java.util.Locale;
import java.util.Optional;

/**
 * Parses classic RTSP {@code RTP-Info} used by RECORD/FLUSH:
 * {@code seq=&lt;u16&gt;;rtptime=&lt;u32&gt;} (either field may be absent).
 */
public final class AudioRtpInfo {

    private final Integer seq;
    private final Long rtpTime;

    private AudioRtpInfo(Integer seq, Long rtpTime) {
        this.seq = seq;
        this.rtpTime = rtpTime;
    }

    public static AudioRtpInfo empty() {
        return new AudioRtpInfo(null, null);
    }

    public static AudioRtpInfo of(Integer seq, Long rtpTime) {
        return new AudioRtpInfo(seq, rtpTime);
    }

    public static AudioRtpInfo parse(String header) {
        if (header == null || header.isBlank()) {
            return empty();
        }
        Integer seq = null;
        Long rtpTime = null;
        for (String part : header.split(";")) {
            String piece = part.trim();
            int eq = piece.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = piece.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = piece.substring(eq + 1).trim();
            try {
                if ("seq".equals(key)) {
                    seq = Integer.parseUnsignedInt(value) & 0xFFFF;
                } else if ("rtptime".equals(key)) {
                    rtpTime = Long.parseUnsignedLong(value) & 0xFFFF_FFFFL;
                }
            } catch (NumberFormatException ignored) {
                // leave that field unset
            }
        }
        return new AudioRtpInfo(seq, rtpTime);
    }

    public Optional<Integer> seq() {
        return Optional.ofNullable(seq);
    }

    public Optional<Long> rtpTime() {
        return Optional.ofNullable(rtpTime);
    }

    public boolean isEmpty() {
        return seq == null && rtpTime == null;
    }

    @Override
    public String toString() {
        return "AudioRtpInfo{seq=" + seq + ", rtpTime=" + rtpTime + '}';
    }
}
