package com.github.serezhka.airplay.lib.internal;

import java.io.*;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class OmgHaxConst {

    static final byte[] z_key = {26, 100, -7, 96, 108, -29, 1, -87, 84, 72, 27, -44, -85, -127, -4, -58};
    static final byte[] x_key = {-114, -70, 7, -52, -74, 90, -10, 32, 51, -49, -8, 66, -27, -43, 90, 125};
    static final byte[] t_key = {-48, 4, -87, 97, 107, -92, 0, -121, 104, -117, 95, 21, 21, 53, -39, -87};
    static final byte[] index_mangle = {1, 2, 4, 8, 16, 32, 64, -128, 27, 54, 108};
    static final byte[] default_sap = {0, 3, 0, 0, 0, 0, 0, 0, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 3, 2, 83, 0, 1, -52, 52, 42, 94, 91, 26, 103, 115, -62, 14, 33, -72, 34, 77, -8, 98, 72, 24, 100, -17, -127, 10, -82, 46, 55, 3, -56, -127, -100, 35, 83, -99, -27, -11, -41, 73, -68, 91, 122, 38, 108, 73, 98, -125, -50, 127, 3, -109, 122, -31, -10, 22, -34, 12, 21, -1, 51, -116, -54, -1, -80, -98, -86, -69, -28, 15, 93, 95, 85, -113, -71, 127, 23, 49, -8, -9, -38, 96, -96, -20, 101, 121, -61, 62, -87, -125, 18, -61, -74, 113, 53, -90, 105, 79, -8, 35, 5, -39, -70, 92, 97, 95, -94, 84, -46, -79, -125, 69, -125, -50, -28, 45, 68, 38, -56, 53, -89, -91, -10, -56, 66, 28, 13, -93, -15, -57, 0, 80, -14, -27, 23, -8, -48, -6, 119, -115, -5, -126, -115, 64, -57, -114, -108, 30, 30, 30};
    static final byte[] initial_session_key = {-36, -36, -13, -71, 11, 116, -36, -5, -122, 127, -9, 96, 22, 114, -112, 81};
    static final byte[] static_source_1 = {-6, -100, -83, 77, 75, 104, 38, -116, 127, -13, -120, -103, -34, -110, 46, -107, 30};
    static final byte[] static_source_2 = {-20, 78, 39, 94, -3, -14, -24, 48, -105, -82, 112, -5, -32, 0, 63, 28, 57, -128, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 16, 9, 0, 0, 0, 0, 0, 0};

    static final byte[][] message_key = {
            {29, 36, 3, 64, -36, -82, -57, -88, 38, 124, 32, -103, 93, 126, -119, 46, -94, 88, -81, -66, -72, 7, -102, 47, -121, 119, -45, -50, 55, 62, 27, 22, 65, 79, 78, -66, 98, 90, 0, 119, -58, -21, -38, 75, -105, 26, 97, -115, 49, 50, 28, -94, 120, -101, 102, 114, 96, -108, 68, -122, -53, 9, -67, 58, 119, 87, -63, 114, 97, 29, 50, -57, -123, -47, -17, -27, 77, -107, 11, -16, -40, 24, -25, 74, -36, 119, -54, 85, 40, 50, -109, 42, 123, 62, 58, -44, -105, -3, 125, 109, -107, 113, 39, -100, 119, 106, 124, -43, -65, -99, 14, -14, 15, 85, -111, 41, -49, -86, 88, 28, 122, -25, -53, -117, 32, 7, 83, -86, 89, 64, 59, 3, -66, 51, 71, 71, 90, 79, -122, 49, -115, 48, -7, 28},
            {-15, -94, 4, 122, -82, -23, -40, -65, -44, -64, 107, 119, -63, 5, -116, -103, -87, -3, 61, 68, -18, 123, 108, 40, 66, 49, 99, -121, 109, -46, 109, 72, -52, 78, -109, 49, 123, 39, 20, -4, 45, 113, 93, -28, -80, -7, 75, -126, 118, 82, -43, 2, 108, -74, -49, 87, -2, -78, -65, -73, 48, 86, 123, -101, 62, 62, -80, 71, 16, 99, -24, 114, 28, 56, 45, 121, -60, 119, 60, -47, -19, 2, 67, 3, 92, -68, 87, -98, 67, 2, 103, -95, -101, -116, -13, 84, -28, 70, -31, 28, 79, -36, -9, -97, -12, 73, 118, 79, 19, -106, -122, -49, -15, 122, 1, -84, -28, -43, 50, 91, 93, 125, -18, -54, -65, 118, -5, 80, -41, -20, -100, -93, -10, 46, -66, -101, -57, -56, 15, -14, -73, 59, -34, -118},
            {24, 110, -45, 115, 94, -23, 90, -113, 102, 63, -15, -72, 74, 98, -39, -64, -46, 8, 19, 97, -53, -13, -83, -90, 38, 77, 58, 123, 6, -75, 81, 86, -2, 102, 10, -40, 58, -86, 71, 73, -45, 124, -61, 104, 112, -48, -106, -128, 106, 5, -112, -17, -81, 67, 66, -60, 46, 80, 76, -106, 19, -75, 46, 76, -128, -94, -115, 35, -18, -30, 94, 120, -12, 61, 101, -54, 113, 79, 104, -98, 75, 67, 88, 123, 71, -106, 64, -127, -118, -104, 108, 4, 51, 15, 47, 28, 51, -114, -22, -95, 79, -88, 55, -109, 23, 29, -115, 24, -87, 106, 27, 7, 124, -74, 8, 88, 31, 18, 0, -6, 55, 77, 127, -70, -91, 0, 107, 114, 120, -100, 51, -24, 65, 7, -73, -63, 103, -101, 118, -69, -35, -111, 61, 61},
            {71, 105, -97, 8, -72, -126, -5, -95, -107, -27, 111, 65, 121, 30, 12, -74, -95, -54, 17, 10, -30, -121, 44, 126, 57, -68, -104, -91, 30, -78, -6, 31, -18, 115, 66, -41, -87, 9, 66, -64, -17, -60, 68, 12, 15, 111, -105, 9, 8, -68, 102, 49, 51, -1, -54, 126, -75, -23, 125, 119, -104, -64, -46, 106, -3, 47, 11, 108, -99, -85, -86, 120, 76, 118, -34, 33, -65, -12, 58, 40, 42, -60, 116, -76, -87, 27, -102, 56, 33, 76, -21, -67, 114, 81, -90, 21, -44, -98, 23, -13, -108, 38, 109, 7, 95, -110, -86, -92, 78, -14, -51, 63, 2, 79, 5, 53, -29, 88, -33, -126, 126, 106, 23, -16, 95, 107, -36, -23, 58, -49, 4, -77, 1, 68, -121, -41, -68, -83, 61, 116, -106, 116, -93, -103}};

    static final byte[][] message_iv = {
            {87, 82, -15, -73, 84, -99, -113, -121, 12, 16, 72, 90, 96, -120, -54, -37},
            {-33, 123, 21, 99, -16, 5, 88, 119, 82, -87, 4, 2, -71, -93, -110, -107},
            {104, -75, 70, 17, -5, 4, -34, 103, 108, -106, -114, -5, -116, -99, -80, -55},
            {39, 7, -117, 33, 35, 54, 30, 122, -36, -99, 11, 17, 83, 84, 105, 13}};

    static final byte[] table_s1;
    static final byte[] table_s2;
    static final byte[] table_s3;
    static final byte[] table_s4;
    static final int[] table_s5;
    static final int[] table_s6;
    static final int[] table_s7;
    static final int[] table_s8;
    static final int[] table_s9;
    static final byte[] table_s10;

    static {
        try {
            table_s1 = readBytes(OmgHaxConst.class.getResource("/table_s1"));
            table_s2 = readBytes(OmgHaxConst.class.getResource("/table_s2"));
            table_s3 = readBytes(OmgHaxConst.class.getResource("/table_s3"));
            table_s4 = readBytes(OmgHaxConst.class.getResource("/table_s4"));
            table_s5 = readInts(OmgHaxConst.class.getResource("/table_s5"));
            table_s6 = readInts(OmgHaxConst.class.getResource("/table_s6"));
            table_s7 = readInts(OmgHaxConst.class.getResource("/table_s7"));
            table_s8 = readInts(OmgHaxConst.class.getResource("/table_s8"));
            table_s9 = readInts(OmgHaxConst.class.getResource("/table_s9"));
            table_s10 = readBytes(OmgHaxConst.class.getResource("/table_s10"));
        } catch (Exception e) {
            throw new RuntimeException("Init failed", e);
        }
    }

    private static byte[] readBytes(URL url) throws IOException {
        try (InputStream is = url.openStream();
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {

            int nRead;
            byte[] data = new byte[1024];

            while ((nRead = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }

            return buffer.toByteArray();
        }
    }

    /**
     * Files.newBufferedReader(Paths.get(OmgHaxConst.class.getClassLoader().getResource("table_s5").toURI()))
     * .lines().map(Long::decode).mapToInt(Long::intValue).toArray();
     * Doesn't work !!! throws java.nio.file.FileSystemNotFoundException
     * jar:file:/../java-airplay-lib/build/libs/java-airplay-lib-1.0-SNAPSHOT-all.jar!/table_s1
     */
    private static int[] readInts(URL url) throws IOException {
        try (InputStream is = url.openStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {

            List<String> tmp = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                tmp.add(line);
            }

            return tmp.stream().map(Long::decode).mapToInt(Long::intValue).toArray();
        }
    }
}
