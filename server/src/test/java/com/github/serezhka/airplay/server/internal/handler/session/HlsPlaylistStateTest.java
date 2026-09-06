package com.github.serezhka.airplay.server.internal.handler.session;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HlsPlaylistStateTest {

    @Test
    void extractMediaUrisFromSampleMasterPlaylist() throws Exception {
        var listBase64 = "I0VYVE0zVQojRVhULVgtSU5ERVBFTkRFTlQtU0VHTUVOVFMKI0VYVC1YLU1FRElBOlVSST0ibWxobHM6Ly9sb2NhbGhvc3QvaXRhZy8yMzMvbWVkaWFkYXRhLm0zdTgiLFRZUEU9QVVESU8sR1JPVVAtSUQ9IjIzMyIsREVGQVVMVD1ZRVMsQVVUT1NFTEVDVD1ZRVMsTkFNRT0iRGVmYXVsdCIKI0VYVC1YLU1FRElBOlVSST0ibWxobHM6Ly9sb2NhbGhvc3QvaXRhZy8yMzQvbWVkaWFkYXRhLm0zdTgiLFRZUEU9QVVESU8sR1JPVVAtSUQ9IjIzNCIsREVGQVVMVD1ZRVMsQVVUT1NFTEVDVD1ZRVMsTkFNRT0iRGVmYXVsdCIKI0VYVC1YLVNUUkVBTS1JTkY6QkFORFdJRFRIPTEyMDk4NjIsQ09ERUNTPSJhdmMxLjRENDAxRSxtcDRhLjQwLjIiLFJFU09MVVRJT049NjQweDM2MCxBVURJTz0iMjM0IixGUkFNRS1SQVRFPTMwLFZJREVPLVJBTkdFPVNEUixDTE9TRUQtQ0FQVElPTlM9Tk9ORQptbGhsczovL2xvY2FsaG9zdC9pdGFnLzIzMC9tZWRpYWRhdGEubTN1OAojRVhULVgtU1RSRUFNLUlORjpCQU5EV0lEVEg9NTQ2MjM5LENPREVDUz0iYXZjMS40RDQwMTUsbXA0YS40MC41IixSRVNPTFVUSU9OPTQyNngyNDAsQVVESU89IjIzMyIsRlJBTUUtUkFURT0zMCxWSURFTy1SQU5HRT1TRFIsQ0xPU0VELUNBUFRJT05TPU5PTkUKbWxobHM6Ly9sb2NhbGhvc3QvaXRhZy8yMjkvbWVkaWFkYXRhLm0zdTgKI0VYVC1YLVNUUkVBTS1JTkY6QkFORFdJRFRIPTYzMDIzOSxDT0RFQ1M9ImF2YzEuNEQ0MDE1LG1wNGEuNDAuMiIsUkVTT0xVVElPTj00MjZ4MjQwLEFVRElPPSIyMzQiLEZSQU1FLVJBVEU9MzAsVklERU8tUkFOR0U9U0RSLENMT1NFRC1DQVBUSU9OUz1OT05FCm1saGxzOi8vbG9jYWxob3N0L2l0YWcvMjI5L21lZGlhZGF0YS5tM3U4CiNFWFQtWC1TVFJFQU0tSU5GOkJBTkRXSURUSD0xNTY4NzI2LENPREVDUz0iYXZjMS40RDQwMUYsbXA0YS40MC4yIixSRVNPTFVUSU9OPTE5MjB4MTA4MCxBVURJTy0iMjM0IixGUkFNRS1SQVRFPTYwLFZJREVPLVJBTkdFPVNEUixDTE9TRUQtQ0FQVElPTlM9Tk9ORQptbGhsczovL2xvY2FsaG9zdC9pdGFnLzMxMS9tZWRpYWRhdGEubTN1OAo=";
        var master = new String(Base64.getDecoder().decode(listBase64));

        List<String> uris = HlsPlaylistState.extractMediaUris(master);

        assertFalse(uris.isEmpty());
        assertTrue(uris.stream().anyMatch(uri -> uri.contains("/itag/233/mediadata.m3u8")));
        assertTrue(uris.stream().anyMatch(uri -> uri.contains("/itag/234/mediadata.m3u8")));
    }

    @Test
    void recordMasterIfChangedDetectsIdenticalAndChangedBodies() {
        var hls = new HlsPlaylistState("mlhls://localhost/master.m3u8", "http://localhost/playlist/master.m3u8?session=x");
        String masterV1 = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv1.m3u8\n";
        String masterV2 = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv2.m3u8\n";

        assertTrue(hls.recordMasterIfChanged(masterV1));
        assertFalse(hls.recordMasterIfChanged(masterV1));
        assertTrue(hls.recordMasterIfChanged(masterV2));
    }
}
