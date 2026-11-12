package com.github.serezhka.airplay.app.ffmpeg;

import com.github.serezhka.airplay.app.ffmpeg.capture.FfmpegVideoTestSource;
import com.github.serezhka.airplay.client.capture.CaptureSources;
import com.github.serezhka.airplay.client.cast.MirrorCaster;
import com.github.serezhka.airplay.client.discovery.ServiceDiscovery;

import java.util.concurrent.TimeUnit;

/**
 * Cast lavfi testsrc via the FFmpeg CaptureSource to a LAN receiver.
 * <pre>
 *   ./gradlew :app:ffmpeg:ffmpegCastToHost --args='--host 192.168.0.208 --port 7000 --seconds 15'
 * </pre>
 */
public final class FfmpegCastToHostSmokeMain {

    private FfmpegCastToHostSmokeMain() {
    }

    public static void main(String[] args) throws Exception {
        String host = null;
        int port = 7000;
        int seconds = 15;
        String pin = "";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--seconds" -> seconds = Integer.parseInt(args[++i]);
                case "--pin" -> pin = args[++i];
                default -> throw new IllegalArgumentException("unknown " + args[i]);
            }
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("--host required");
        }
        CaptureSources.install(FfmpegVideoTestSource::new);
        try (MirrorCaster caster = new MirrorCaster(false, true, false)) {
            ServiceDiscovery.Info target = new ServiceDiscovery.Info(host, host, port);
            System.out.println("FFMPEG_CAST\t" + target);
            long t0 = System.nanoTime();
            caster.connect(target, pin);
            System.out.println("CONNECTED=" + caster.isConnected()
                    + " setupMs=" + ((System.nanoTime() - t0) / 1_000_000L));
            TimeUnit.SECONDS.sleep(seconds);
            caster.disconnect();
            System.out.println("DISCONNECTED ok");
        }
    }
}
