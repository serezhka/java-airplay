package com.github.serezhka.airplay.server.internal.handler.audio;

@FunctionalInterface
public interface AudioResendRequests {

    AudioResendRequests NOOP = (first, count) -> {
    };

    void requestResend(int firstSeq, int count);
}
