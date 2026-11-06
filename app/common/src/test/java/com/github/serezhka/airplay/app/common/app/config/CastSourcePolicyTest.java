package com.github.serezhka.airplay.app.common.app.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CastSourcePolicyTest {

    @Test
    void nativePlayerRejectsBrowser() {
        assertThrows(IllegalStateException.class,
                () -> CastSourcePolicy.NATIVE_PLAYER.validate("browser", "none"));
        assertThrows(IllegalStateException.class,
                () -> CastSourcePolicy.NATIVE_PLAYER.validate("videotest", "browser"));
    }

    @Test
    void nativePlayerAcceptsDefaults() {
        assertDoesNotThrow(() -> CastSourcePolicy.NATIVE_PLAYER.validate("videotest", "audiotest"));
    }

    @Test
    void msePlayerAcceptsBrowser() {
        assertDoesNotThrow(() -> CastSourcePolicy.MSE_PLAYER.validate("browser", "browser"));
        assertDoesNotThrow(() -> CastSourcePolicy.MSE_PLAYER.validate("browser", "none"));
    }

    @Test
    void msePlayerRejectsVideotest() {
        assertThrows(IllegalStateException.class,
                () -> CastSourcePolicy.MSE_PLAYER.validate("videotest", "none"));
    }
}
