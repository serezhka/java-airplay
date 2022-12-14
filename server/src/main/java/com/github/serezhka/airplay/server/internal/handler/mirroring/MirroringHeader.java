package com.github.serezhka.airplay.server.internal.handler.mirroring;

import io.netty.buffer.ByteBuf;

class MirroringHeader {
    private final int payloadSize;
    private final short payloadType;
    private final short payloadOption;

    private int widthSource;
    private int heightSource;
    private int width;
    private int height;

    MirroringHeader(ByteBuf header) {
        this.payloadSize = (int) header.readUnsignedIntLE();
        this.payloadType = (short) (header.readUnsignedShortLE() & 0xff);
        this.payloadOption = (short) header.readUnsignedShortLE();

        if (payloadType == 1) {
            header.readerIndex(40);
            widthSource = (int) header.readFloatLE();
            heightSource = (int) header.readFloatLE();
            header.readerIndex(56);
            width = (int) header.readFloatLE();
            height = (int) header.readFloatLE();
        }
    }

    public int getPayloadSize() {
        return payloadSize;
    }

    public short getPayloadType() {
        return payloadType;
    }

    public short getPayloadOption() {
        return payloadOption;
    }

    public int getWidthSource() {
        return widthSource;
    }

    public void setWidthSource(int widthSource) {
        this.widthSource = widthSource;
    }

    public int getHeightSource() {
        return heightSource;
    }

    public void setHeightSource(int heightSource) {
        this.heightSource = heightSource;
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        this.height = height;
    }
}
