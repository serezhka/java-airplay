package com.github.serezhka.airplay.client.discovery;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AirplayMdnsProbeTest {

    @Test
    void parsesSamsungStyleAirPlayAnswer() {
        // Synthetic mDNS answer: PTR + SRV + TXT for 65" QLED (quote in instance label).
        byte[] pkt = buildSamsungishAnswer();
        List<AirplayMdnsProbe.Found> found = AirplayMdnsProbe.parseResponse(pkt, pkt.length, "192.168.0.101");
        assertFalse(found.isEmpty(), "expected at least one AirPlay instance");
        AirplayMdnsProbe.Found tv = found.getFirst();
        assertTrue(tv.name().contains("QLED"), tv.name());
        assertEquals(7000, tv.port());
        assertEquals("192.168.0.101", tv.address());
        assertEquals("1C:86:9A:36:E8:B9", tv.deviceId());
    }

    private static byte[] buildSamsungishAnswer() {
        ByteBuffer buf = ByteBuffer.allocate(512);
        buf.putShort((short) 0); // id
        buf.putShort((short) 0x8400); // response
        buf.putShort((short) 0); // qd
        buf.putShort((short) 3); // an: PTR SRV TXT
        buf.putShort((short) 0);
        buf.putShort((short) 0);

        // PTR _airplay._tcp.local -> 65" QLED._airplay._tcp.local
        writeName(buf, "_airplay", "_tcp", "local");
        buf.putShort((short) 12);
        buf.putShort((short) 1);
        buf.putInt(120);
        byte[] ptrRdata = nameBytes("65\" QLED", "_airplay", "_tcp", "local");
        buf.putShort((short) ptrRdata.length);
        buf.put(ptrRdata);

        // SRV 65" QLED._airplay._tcp.local port 7000 target Samsung.local
        writeName(buf, "65\" QLED", "_airplay", "_tcp", "local");
        buf.putShort((short) 33);
        buf.putShort((short) 1);
        buf.putInt(120);
        byte[] target = nameBytes("Samsung", "local");
        buf.putShort((short) (6 + target.length));
        buf.putShort((short) 0); // priority
        buf.putShort((short) 0); // weight
        buf.putShort((short) 7000);
        buf.put(target);

        // TXT with deviceid + features
        writeName(buf, "65\" QLED", "_airplay", "_tcp", "local");
        buf.putShort((short) 16);
        buf.putShort((short) 1);
        buf.putInt(120);
        byte[] t1 = txt("deviceid=1C:86:9A:36:E8:B9");
        byte[] t2 = txt("features=0x7F8AD0,0x38BCF46");
        byte[] t3 = txt("flags=0x244");
        buf.putShort((short) (t1.length + t2.length + t3.length));
        buf.put(t1);
        buf.put(t2);
        buf.put(t3);

        byte[] out = new byte[buf.position()];
        buf.flip();
        buf.get(out);
        return out;
    }

    private static void writeName(ByteBuffer buf, String... labels) {
        for (String label : labels) {
            byte[] b = label.getBytes(StandardCharsets.UTF_8);
            buf.put((byte) b.length);
            buf.put(b);
        }
        buf.put((byte) 0);
    }

    private static byte[] nameBytes(String... labels) {
        ByteBuffer buf = ByteBuffer.allocate(128);
        writeName(buf, labels);
        byte[] out = new byte[buf.position()];
        buf.flip();
        buf.get(out);
        return out;
    }

    private static byte[] txt(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[b.length + 1];
        out[0] = (byte) b.length;
        System.arraycopy(b, 0, out, 1, b.length);
        return out;
    }
}
