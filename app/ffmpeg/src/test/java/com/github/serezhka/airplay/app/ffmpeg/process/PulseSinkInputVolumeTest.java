package com.github.serezhka.airplay.app.ffmpeg.process;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PulseSinkInputVolumeTest {

    @Test
    void parsesSinkInputIndexForProcessId() {
        String sample = """
                Sink Input #12
                	Driver: PipeWire
                	Owner Module: n/a
                	properties:
                		application.name = "ffplay"
                		application.process.id = "2707421"
                Sink Input #13
                	Driver: PipeWire
                	properties:
                		application.process.id = "999"
                Sink Input #14
                	properties:
                		application.process.id = "2707421"
                """;
        assertEquals(List.of(12, 14), PulseSinkInputVolume.parseSinkInputIndexesForPid(sample, 2707421L));
        assertEquals(List.of(13), PulseSinkInputVolume.parseSinkInputIndexesForPid(sample, 999L));
        assertEquals(List.of(), PulseSinkInputVolume.parseSinkInputIndexesForPid(sample, 1L));
    }
}
