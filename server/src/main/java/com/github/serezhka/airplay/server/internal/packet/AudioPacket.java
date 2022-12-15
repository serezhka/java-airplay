package com.github.serezhka.airplay.server.internal.packet;

import lombok.Builder;
import lombok.Data;

import java.util.function.Consumer;

@Data
@Builder
public class AudioPacket {

    private final byte[] encodedAudio = new byte[480 * 4];

    private boolean available;
    private int flag;
    private int type;
    private int sequenceNumber;
    private long timestamp;
    private long ssrc;
    private int encodedAudioSize;

    public AudioPacket available(boolean available) {
        this.available = available;
        return this;
    }

    public AudioPacket flag(int flag) {
        this.flag = flag;
        return this;
    }

    public AudioPacket type(int type) {
        this.type = type;
        return this;
    }

    public AudioPacket sequenceNumber(int sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
        return this;
    }

    public AudioPacket timestamp(long timestamp) {
        this.timestamp = timestamp;
        return this;
    }

    public AudioPacket ssrc(long ssrc) {
        this.ssrc = ssrc;
        return this;
    }

    public AudioPacket encodedAudioSize(int encodedAudioSize) {
        this.encodedAudioSize = encodedAudioSize;
        return this;
    }

    public AudioPacket encodedAudio(Consumer<byte[]> writer) {
        writer.accept(encodedAudio);
        return this;
    }
}
