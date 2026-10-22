package com.github.serezhka.airplay.app.gstreamer.app;

import com.github.serezhka.airplay.app.gstreamer.capture.GstAudioTestSource;
import com.github.serezhka.airplay.app.gstreamer.capture.GstVideoTestSource;
import com.github.serezhka.airplay.client.audio.AudioSourceFactory;
import com.github.serezhka.airplay.client.audio.AudioSources;
import com.github.serezhka.airplay.client.capture.CaptureSourceFactory;
import com.github.serezhka.airplay.client.capture.CaptureSources;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GstreamerCaptureConfig {

    @Bean
    @ConditionalOnProperty(name = "client.video.src", havingValue = "videotest", matchIfMissing = true)
    public CaptureSourceFactory videoTestCaptureSourceFactory() {
        CaptureSourceFactory factory = GstVideoTestSource::new;
        CaptureSources.install(factory);
        return factory;
    }

    @Bean
    @ConditionalOnProperty(name = "client.video.src", havingValue = "screencapture")
    public CaptureSourceFactory screenCaptureSourceFactory() {
        CaptureSourceFactory factory = consumer -> {
            throw new UnsupportedOperationException(
                    "client.video.src=screencapture is not implemented yet (GStreamer)");
        };
        CaptureSources.install(factory);
        return factory;
    }

    @Bean
    @ConditionalOnProperty(name = "client.audio.src", havingValue = "audiotest", matchIfMissing = true)
    public AudioSourceFactory audioTestSourceFactory() {
        AudioSourceFactory factory = GstAudioTestSource::new;
        AudioSources.install(factory);
        return factory;
    }
}
