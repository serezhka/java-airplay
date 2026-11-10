package com.github.serezhka.airplay.app.common.app.menu;

import com.github.serezhka.airplay.client.discovery.ServiceDiscovery;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalReceiverFilterTest {

    @Test
    void normalizeMacAcceptsColonAndBareHex() {
        assertEquals("1C:86:9A:36:E8:B9", LocalReceiverFilter.normalizeMac("1c:86:9a:36:e8:b9"));
        assertEquals("1C:86:9A:36:E8:B9", LocalReceiverFilter.normalizeMac("1c869a36e8b9"));
    }

    @Test
    void ignoresForeignNameEvenOnLoopback() {
        LocalReceiverFilter filter = new LocalReceiverFilter("JavaAirPlay-MSE");
        assertFalse(filter.isSelf(new ServiceDiscovery.Info(
                "Living Room TV", "127.0.0.1", 7000, false, "AA:BB:CC:DD:EE:FF")));
    }

    @Test
    void matchesOwnNameOnLoopback() {
        LocalReceiverFilter filter = new LocalReceiverFilter("JavaAirPlay-MSE");
        assertTrue(filter.isSelf(new ServiceDiscovery.Info(
                "JavaAirPlay-MSE", "127.0.0.1", 7000, false, "00:00:00:00:00:00")));
    }

    @Test
    void ignoresBlankServerName() {
        LocalReceiverFilter filter = new LocalReceiverFilter("  ");
        assertFalse(filter.isSelf(new ServiceDiscovery.Info(
                "JavaAirPlay-MSE", "127.0.0.1", 7000)));
    }
}
