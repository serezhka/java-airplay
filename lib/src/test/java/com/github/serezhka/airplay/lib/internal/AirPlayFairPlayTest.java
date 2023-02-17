package com.github.serezhka.airplay.lib.internal;

import com.dd.plist.BinaryPropertyListWriter;
import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import com.github.serezhka.airplay.lib.AirPlay;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AirPlayFairPlayTest {

    private final AirPlay airPlay = new AirPlay();

    /**
     * Actually don't know how fairplay works
     */
    @Test
    void fairPlayTest() throws Exception {

        // /fp-setup 1 request
        byte[] fairPlaySetup1Request = new byte[]{70, 80, 76, 89, 3, 1, 1, 0, 0, 0, 0, 4, 2, 0, 0, -69};
        ByteArrayOutputStream fairPlaySetup1Response = new ByteArrayOutputStream(144);
        airPlay.fairPlaySetup(new ByteArrayInputStream(fairPlaySetup1Request), fairPlaySetup1Response);
        byte[] fairPlaySetup1ResponseBytes = new byte[]{70, 80, 76, 89, 3, 1, 2, 0, 0, 0, 0, -126, 2, 0, 15, -97, 63, -98, 10, 37, 33, -37, -33, 49, 42, -78, -65, -78, -98, -115, 35, 43, 99, 118, -88, -56, 24, 112, 29, 34, -82, -109, -40, 39, 55, -2, -81, -99, -76, -3, -12, 28, 45, -70, -99, 31, 73, -54, -86, -65, 101, -111, -84, 31, 123, -58, -9, -32, 102, 61, 33, -81, -32, 21, 101, -107, 62, -85, -127, -12, 24, -50, -19, 9, 90, -37, 124, 61, 14, 37, 73, 9, -89, -104, 49, -44, -100, 57, -126, -105, 52, 52, -6, -53, 66, -58, 58, 28, -39, 17, -90, -2, -108, 26, -118, 109, 74, 116, 59, 70, -61, -89, 100, -98, 68, -57, -119, 85, -28, -99, -127, 85, 0, -107, 73, -60, -30, -9, -93, -10, -43, -70};
        assertArrayEquals(fairPlaySetup1ResponseBytes, fairPlaySetup1Response.toByteArray());

        // /fp-setup 2 request
        ByteArrayOutputStream fairPlaySetup2Response = new ByteArrayOutputStream(32);
        byte[] fairPlaySetup2Request = new byte[]{70, 80, 76, 89, 3, 1, 3, 0, 0, 0, 0, -104, 0, -113, 26, -100, -40, -92, -10, 52, 109, 20, 120, 6, -62, -67, -118, 75, -47, -71, -109, -45, -61, 106, -95, 1, 36, -104, -7, 78, -1, -13, 70, 123, -49, 27, 49, -104, 98, 92, -94, 69, -114, 62, -48, 30, -35, 53, -25, 41, 53, 125, -7, 75, -128, -51, 10, -50, 35, 84, -42, -116, -29, 127, 94, 24, -16, -49, -46, 109, 65, 103, 21, 63, -64, -76, 54, 35, 22, 111, 8, -58, 111, -45, 1, 56, 14, -80, -98, -97, -115, -24, 59, -46, -82, -57, -92, 1, -15, -5, -67, -13, 46, 10, -43, 81, -24, 121, 63, -25, -63, 25, 35, 51, -103, -91, 53, 76, -59, 67, 7, 30, -68, -50, -32, -84, -123, 34, -82, 27, -85, 51, -44, 65, -60, 120, -11, 99, -50, -3, 66, 117, -5, 85, 90, 58, -29, 58, -40, -71, -7, -108, -7, -75};
        airPlay.fairPlaySetup(new ByteArrayInputStream(fairPlaySetup2Request), fairPlaySetup2Response);
        byte[] fairPlaySetup2ResponseBytes = new byte[]{70, 80, 76, 89, 3, 1, 4, 0, 0, 0, 0, 20, -60, 120, -11, 99, -50, -3, 66, 117, -5, 85, 90, 58, -29, 58, -40, -71, -7, -108, -7, -75};
        assertArrayEquals(fairPlaySetup2ResponseBytes, fairPlaySetup2Response.toByteArray());

        // RSTP SETUP 1 request
        byte[] encryptedAesKey = new byte[]{70, 80, 76, 89, 1, 2, 1, 0, 0, 0, 0, 60, 0, 0, 0, 0, 63, 121, 70, -69, 3, -8, 117, -13, 83, 72, 105, -51, -11, -43, -1, 17, 0, 0, 0, 16, 24, -109, 13, 105, -32, -125, -73, -128, 21, 29, -31, 72, -41, 112, -36, -75, 57, 110, 71, -72, -25, -59, 102, 22, 19, -43, 35, 74, -20, 86, 15, 16, 126, 5, 15, -45};
        NSDictionary rtspSetup1Request = new NSDictionary();
        rtspSetup1Request.put("ekey", encryptedAesKey);
        rtspSetup1Request.put("eiv", "91IdM6RTh4keicMei2GfQA==".getBytes(StandardCharsets.UTF_8));
        byte[] rtspSetup1RequestBytes = BinaryPropertyListWriter.writeToArray(rtspSetup1Request);
        airPlay.rtspSetup(new ByteArrayInputStream(rtspSetup1RequestBytes));

        // RSTP SETUP 2 request
        long streamConnectionID = -3907568444900622110L;
        NSArray streams = new NSArray(1);
        NSDictionary dataStream = new NSDictionary();
        dataStream.put("type", 110);
        dataStream.put("streamConnectionID", streamConnectionID);
        streams.setValue(0, dataStream);
        NSDictionary rtspSetup2Request = new NSDictionary();
        rtspSetup2Request.put("streams", streams);
        byte[] rtspSetup2RequestBytes = BinaryPropertyListWriter.writeToArray(rtspSetup2Request);
        airPlay.rtspSetup(new ByteArrayInputStream(rtspSetup2RequestBytes));

        // Decrypt payload
        byte[] sharedSecret = new byte[]{-5, -67, -104, 31, 49, 40, -76, 40, -116, 105, 45, -47, 125, -94, 117, -104, -54, -47, -50, 6, 122, 1, -38, -114, -88, -85, -128, 2, 116, -119, -90, 123};
        FairPlayVideoDecryptor fairPlayVideoDecryptor = new FairPlayVideoDecryptor(airPlay.getFairPlayAesKey(), sharedSecret, Long.toUnsignedString(streamConnectionID));

        Path payloadFile = Paths.get(AirPlayFairPlayTest.class.getResource("/encrypted_payload").toURI());
        byte[] payload = Files.readAllBytes(payloadFile);

        fairPlayVideoDecryptor.decrypt(payload);
        int nc_len = (payload[3] & 0xFF) | ((payload[2] & 0xFF) << 8) | ((payload[1] & 0xFF) << 16) | ((payload[0] & 0xFF) << 24);

        assertEquals(payload.length - 4, nc_len, "Decrypted payload is corrupted!");
    }
}
