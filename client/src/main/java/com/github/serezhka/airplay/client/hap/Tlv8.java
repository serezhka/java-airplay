package com.github.serezhka.airplay.client.hap;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HAP TLV8 encode/decode. Values longer than 255 bytes are split across repeated type tags.
 */
public final class Tlv8 {

    private Tlv8() {
    }

    public static byte[] encode(Map<Integer, byte[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<Integer, byte[]> e : entries.entrySet()) {
            int type = e.getKey();
            byte[] value = e.getValue() == null ? new byte[0] : e.getValue();
            if (value.length == 0) {
                out.write(type);
                out.write(0);
                continue;
            }
            int offset = 0;
            while (offset < value.length) {
                int chunk = Math.min(255, value.length - offset);
                out.write(type);
                out.write(chunk);
                out.write(value, offset, chunk);
                offset += chunk;
            }
        }
        return out.toByteArray();
    }

    /** Convenience builder preserving insertion order. */
    public static byte[] of(Object... typeAndValues) {
        if (typeAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("expected type/value pairs");
        }
        Map<Integer, byte[]> map = new LinkedHashMap<>();
        for (int i = 0; i < typeAndValues.length; i += 2) {
            int type = ((Number) typeAndValues[i]).intValue();
            Object v = typeAndValues[i + 1];
            byte[] bytes;
            if (v instanceof byte[] b) {
                bytes = b;
            } else if (v instanceof Number n) {
                bytes = new byte[]{(byte) (n.intValue() & 0xFF)};
            } else {
                throw new IllegalArgumentException("unsupported TLV value: " + v);
            }
            map.put(type, bytes);
        }
        return encode(map);
    }

    public static Map<Integer, byte[]> decode(byte[] data) {
        Map<Integer, byte[]> out = new LinkedHashMap<>();
        int i = 0;
        while (i + 1 < data.length) {
            int type = data[i] & 0xFF;
            int len = data[i + 1] & 0xFF;
            i += 2;
            if (i + len > data.length) {
                throw new IllegalArgumentException("truncated TLV at type=0x" + Integer.toHexString(type));
            }
            byte[] chunk = new byte[len];
            System.arraycopy(data, i, chunk, 0, len);
            i += len;
            byte[] prev = out.get(type);
            if (prev == null) {
                out.put(type, chunk);
            } else {
                byte[] merged = new byte[prev.length + chunk.length];
                System.arraycopy(prev, 0, merged, 0, prev.length);
                System.arraycopy(chunk, 0, merged, prev.length, chunk.length);
                out.put(type, merged);
            }
        }
        return out;
    }

    public static byte[] require(Map<Integer, byte[]> tlv, int type) {
        byte[] v = tlv.get(type);
        if (v == null) {
            throw new IllegalStateException("missing TLV type 0x" + Integer.toHexString(type));
        }
        return v;
    }

    public static int requireByte(Map<Integer, byte[]> tlv, int type) {
        byte[] v = require(tlv, type);
        if (v.length != 1) {
            throw new IllegalStateException("TLV type 0x" + Integer.toHexString(type)
                    + " expected 1 byte, got " + v.length);
        }
        return v[0] & 0xFF;
    }
}
