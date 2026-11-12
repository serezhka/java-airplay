package com.github.serezhka.airplay.client.cast;

import com.github.serezhka.airplay.client.capture.CaptureSource;
import com.github.serezhka.airplay.client.capture.CaptureSources;
import com.github.serezhka.airplay.client.discovery.ServiceDiscovery;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Manual LAN smoke: lavfi testsrc → Annex-B → receiver (video only).
 * <pre>
 *   ./gradlew :client:ffmpegVideotestCastSmoke --args='--host 192.168.0.208 --port 7000 --seconds 8'
 * </pre>
 */
public final class FfmpegVideotestCastSmokeMain {

    private FfmpegVideotestCastSmokeMain() {
    }

    public static void main(String[] args) throws Exception {
        String host = null;
        int port = 7000;
        int seconds = 8;
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

        CaptureSources.install(FfmpegPipeSource::new);
        try (MirrorCaster caster = new MirrorCaster(false, true, false)) {
            ServiceDiscovery.Info target = new ServiceDiscovery.Info(host, host, port);
            System.out.println("CONNECT\t" + target);
            long t0 = System.nanoTime();
            caster.connect(target, pin);
            System.out.println("CONNECTED=" + caster.isConnected()
                    + " setupMs=" + ((System.nanoTime() - t0) / 1_000_000L));
            TimeUnit.SECONDS.sleep(seconds);
            caster.disconnect();
            System.out.println("DISCONNECTED ok");
        }
    }

    /** {@code ffmpeg lavfi testsrc} → raw Annex-B chunks (VideoClient splits NALs). */
    private static final class FfmpegPipeSource implements CaptureSource {
        private final Process process;
        private final Thread reader;
        private final AtomicBoolean closed = new AtomicBoolean();

        FfmpegPipeSource(Consumer<byte[]> onAnnexB) {
            List<String> cmd = List.of(
                    "ffmpeg", "-hide_banner", "-loglevel", "error", "-re",
                    "-f", "lavfi", "-i", "testsrc=size=854x480:rate=30",
                    "-pix_fmt", "yuv420p",
                    "-c:v", "libx264", "-tune", "zerolatency", "-preset", "ultrafast",
                    "-profile:v", "baseline", "-bf", "0", "-g", "30",
                    "-x264-params", "aud=1",
                    "-f", "h264", "-");
            try {
                process = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.PIPE).start();
            } catch (Exception e) {
                throw new IllegalStateException("ffmpeg videotest failed to start", e);
            }
            Thread err = new Thread(() -> drain(process.getErrorStream()), "ffmpeg-cast-stderr");
            err.setDaemon(true);
            err.start();
            reader = new Thread(() -> {
                byte[] buf = new byte[64 * 1024];
                try (InputStream in = process.getInputStream()) {
                    int n;
                    while (!closed.get() && (n = in.read(buf)) >= 0) {
                        if (n > 0) {
                            onAnnexB.accept(Arrays.copyOf(buf, n));
                        }
                    }
                } catch (Exception ignored) {
                    // closed or pipe end
                }
            }, "ffmpeg-cast-stdout");
            reader.setDaemon(true);
            reader.start();
        }

        private static void drain(InputStream in) {
            try (in) {
                in.transferTo(OutputStream.nullOutputStream());
            } catch (Exception ignored) {
                // best-effort
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            process.destroyForcibly();
            try {
                reader.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
