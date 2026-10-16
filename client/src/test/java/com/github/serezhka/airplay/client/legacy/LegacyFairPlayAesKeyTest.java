package com.github.serezhka.airplay.client.legacy;

import com.dd.plist.BinaryPropertyListWriter;
import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import com.github.serezhka.airplay.client.crypto.FairPlayVideoEncryptor;
import com.github.serezhka.airplay.protocol.FairPlaySession;
import com.github.serezhka.airplay.protocol.fairplay.FairPlayVideoDecryptor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Client must encrypt with the same plaintext AES key the receiver unwraps from fixture ekey.
 * A hardcoded AES_KEY constant previously drifted and broke decrypt on the receiver.
 */
class LegacyFairPlayAesKeyTest {

    private static final long STREAM_CONNECTION_ID = -3907568444900622110L;

    @Test
    void unwrapMatchesReceiverAfterSameFpSetup() throws Exception {
        byte[] clientKey = LegacyFairPlayHandshake.unwrapAesKey(LegacyFairPlayHandshake.FIXTURE_EKEY);

        FairPlaySession server = new FairPlaySession();
        server.fairPlaySetup(new ByteArrayInputStream(new byte[]{
                70, 80, 76, 89, 3, 1, 1, 0, 0, 0, 0, 4, 2, 0, 0, -69}), new ByteArrayOutputStream());
        server.fairPlaySetup(new ByteArrayInputStream(fpSetup2()), new ByteArrayOutputStream());

        NSDictionary phase1 = new NSDictionary();
        phase1.put("ekey", LegacyFairPlayHandshake.FIXTURE_EKEY);
        phase1.put("eiv", new byte[16]);
        server.rtspSetup(new ByteArrayInputStream(BinaryPropertyListWriter.writeToArray(phase1)));

        NSArray streams = new NSArray(1);
        NSDictionary stream = new NSDictionary();
        stream.put("type", 110);
        stream.put("streamConnectionID", STREAM_CONNECTION_ID);
        streams.setValue(0, stream);
        NSDictionary phase2 = new NSDictionary();
        phase2.put("streams", streams);
        server.rtspSetup(new ByteArrayInputStream(BinaryPropertyListWriter.writeToArray(phase2)));

        assertArrayEquals(server.getFairPlayAesKey(), clientKey);
    }

    @Test
    void encryptDecryptRoundtripWithLiveSharedSecret() throws Exception {
        byte[] aesKey = LegacyFairPlayHandshake.unwrapAesKey(LegacyFairPlayHandshake.FIXTURE_EKEY);
        byte[] sharedSecret = new byte[32];
        new SecureRandom().nextBytes(sharedSecret);
        String sid = Long.toUnsignedString(STREAM_CONNECTION_ID);

        byte[] payload = new byte[64];
        payload[0] = 0;
        payload[1] = 0;
        payload[2] = 0;
        payload[3] = 60;
        for (int i = 4; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        byte[] wire = payload.clone();
        new FairPlayVideoEncryptor(aesKey, sharedSecret, sid).encrypt(wire);
        new FairPlayVideoDecryptor(aesKey, sharedSecret, sid).decrypt(wire);
        assertTrue(Arrays.equals(payload, wire));
    }

    private static byte[] fpSetup2() {
        return new byte[]{
                70, 80, 76, 89, 3, 1, 3, 0, 0, 0, 0, -104, 0, -113, 26, -100, -40, -92, -10, 52, 109, 20, 120, 6,
                -62, -67, -118, 75, -47, -71, -109, -45, -61, 106, -95, 1, 36, -104, -7, 78, -1, -13, 70, 123,
                -49, 27, 49, -104, 98, 92, -94, 69, -114, 62, -48, 30, -35, 53, -25, 41, 53, 125, -7, 75, -128,
                -51, 10, -50, 35, 84, -42, -116, -29, 127, 94, 24, -16, -49, -46, 109, 65, 103, 21, 63, -64, -76,
                54, 35, 22, 111, 8, -58, 111, -45, 1, 56, 14, -80, -98, -97, -115, -24, 59, -46, -82, -57, -92,
                1, -15, -5, -67, -13, 46, 10, -43, 81, -24, 121, 63, -25, -63, 25, 35, 51, -103, -91, 53, 76,
                -59, 67, 7, 30, -68, -50, -32, -84, -123, 34, -82, 27, -85, 51, -44, 65, -60, 120, -11, 99, -50,
                -3, 66, 117, -5, 85, 90, 58, -29, 58, -40, -71, -7, -108, -7, -75};
    }
}
