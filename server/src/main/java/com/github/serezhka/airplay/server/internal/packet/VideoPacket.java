package com.github.serezhka.airplay.server.internal.packet;

import lombok.Data;

@Data
public class VideoPacket {

    private final int payloadType;
    private final int payloadSize;

    private final byte[] payload;
}
