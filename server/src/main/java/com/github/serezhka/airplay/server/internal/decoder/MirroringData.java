package com.github.serezhka.airplay.server.internal.decoder;

public class MirroringData {

    private final int payloadType;
    private final int payloadSize;
    private final byte[] payload;

    public MirroringData(int payloadType, int payloadSize, byte[] payload) {
        this.payloadType = payloadType;
        this.payloadSize = payloadSize;
        this.payload = payload;
    }

    public int getPayloadType() {
        return payloadType;
    }

    public int getPayloadSize() {
        return payloadSize;
    }

    public byte[] getPayload() {
        return payload;
    }
}
