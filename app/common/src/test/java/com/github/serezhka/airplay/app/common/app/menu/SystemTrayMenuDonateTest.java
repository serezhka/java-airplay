package com.github.serezhka.airplay.app.common.app.menu;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemTrayMenuDonateTest {

    @AfterEach
    void clearOverride() {
        System.clearProperty("airplay.donate-url");
    }

    @Test
    void defaultDonateUrlIsStripePaymentLink() {
        System.clearProperty("airplay.donate-url");
        String url = SystemTrayMenu.donateUrl();
        assertTrue(url.startsWith("https://donate.stripe.com/"), url);
        assertEquals(SystemTrayMenu.DONATE_URL_DEFAULT, url);
    }

    @Test
    void donateUrlHonorsSystemPropertyOverride() {
        System.setProperty("airplay.donate-url", " https://example.com/give ");
        assertEquals("https://example.com/give", SystemTrayMenu.donateUrl());
    }
}
