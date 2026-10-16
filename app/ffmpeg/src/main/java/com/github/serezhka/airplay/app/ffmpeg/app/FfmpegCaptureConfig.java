package com.github.serezhka.airplay.app.ffmpeg.app;

import com.github.serezhka.airplay.app.ffmpeg.capture.FfmpegAudioTestSource;
import com.github.serezhka.airplay.app.ffmpeg.capture.FfmpegVideoTestSource;
import com.github.serezhka.airplay.client.audio.AudioSourceFactory;
import com.github.serezhka.airplay.client.audio.AudioSources;
import com.github.serezhka.airplay.client.capture.CaptureSourceFactory;
import com.github.serezhka.airplay.client.capture.CaptureSources;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FfmpegCaptureConfig {

    @Bean
    @ConditionalOnProperty(name = "client.src", havingValue = "videotest", matchIfMissing = true)
    public CaptureSourceFactory videoTestCaptureSourceFactory() {
        CaptureSourceFactory factory = FfmpegVideoTestSource::new;
        CaptureSources.install(factory);
        return factory;
    }

    @Bean
    @ConditionalOnProperty(name = "client.src", havingValue = "screencapture")
    public CaptureSourceFactory screenCaptureSourceFactory() {
        CaptureSourceFactory factory = consumer -> {
            throw new UnsupportedOperationException(
                    "client.src=screencapture is not implemented yet (FFmpeg)");
        };
        CaptureSources.install(factory);
        return factory;
    }

    @Bean
    public AudioSourceFactory audioSourceFactory() {
        AudioSourceFactory factory = FfmpegAudioTestSource::new;
        AudioSources.install(factory);
        return factory;
    }
}
