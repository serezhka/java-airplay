package com.github.serezhka.airplay.app.mse.app;

import com.github.serezhka.airplay.app.mse.cast.BrowserCastAudioSource;
import com.github.serezhka.airplay.app.mse.cast.BrowserCastCaptureSource;
import com.github.serezhka.airplay.client.audio.AudioSourceFactory;
import com.github.serezhka.airplay.client.audio.AudioSources;
import com.github.serezhka.airplay.client.capture.CaptureSourceFactory;
import com.github.serezhka.airplay.client.capture.CaptureSources;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MseCaptureConfig {

    @Bean
    @ConditionalOnProperty(name = "client.video.src", havingValue = "browser")
    public CaptureSourceFactory browserCastCaptureSourceFactory() {
        CaptureSourceFactory factory = BrowserCastCaptureSource::new;
        CaptureSources.install(factory);
        return factory;
    }

    @Bean
    @ConditionalOnProperty(name = "client.audio.src", havingValue = "browser")
    public AudioSourceFactory browserCastAudioSourceFactory() {
        // Cast inject reads this before getDisplayMedia so the tab includes an audio track.
        System.setProperty("airplay.mse.cast.enableAudio", "true");
        AudioSourceFactory factory = BrowserCastAudioSource::new;
        AudioSources.install(factory);
        return factory;
    }

    @Bean
    @ConditionalOnProperty(name = "client.video.src", havingValue = "none", matchIfMissing = true)
    public CaptureSourceFactory noCaptureSourceFactory() {
        // Explicit none: MirrorCaster refuses connect for video; discovery/tray still work.
        CaptureSourceFactory factory = onAnnexB -> {
            throw new IllegalStateException(
                    "client.video.src=none — set client.video.src=browser to cast a navigable Chromium window");
        };
        CaptureSources.install(factory);
        return factory;
    }
}
