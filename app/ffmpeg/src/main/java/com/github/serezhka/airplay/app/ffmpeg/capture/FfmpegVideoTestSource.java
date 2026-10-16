package com.github.serezhka.airplay.app.ffmpeg.capture;

import com.github.serezhka.airplay.client.capture.CaptureSource;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * {@code lavfi testsrc} → libx264 Annex-B on stdout (baseline, no B-frames, AUD).
 */
@Slf4j
public final class FfmpegVideoTestSource implements CaptureSource {

    private final Process process;
    private final Thread reader;
    private final AtomicBoolean closed = new AtomicBoolean();

    public FfmpegVideoTestSource(Consumer<byte[]> onAnnexB) {
        List<String> cmd = List.of(
                "ffmpeg",
                "-hide_banner",
                "-loglevel", "error",
                "-re",
                "-f", "lavfi",
                "-i", "testsrc=size=1280x720:rate=30",
                // testsrc is rgb24; baseline libx264 needs 4:2:0 or encode opens fail → empty stdout / black TV.
                "-pix_fmt", "yuv420p",
                "-c:v", "libx264",
                "-tune", "zerolatency",
                "-preset", "ultrafast",
                "-profile:v", "baseline",
                "-bf", "0",
                "-g", "30",
                "-x264-params", "aud=1",
                "-f", "h264",
                "-");
        try {
            process = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.PIPE).start();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start ffmpeg videotest capture", e);
        }
        Thread err = new Thread(() -> drain(process.getErrorStream()), "ffmpeg-videotest-stderr");
        err.setDaemon(true);
        err.start();
        AnnexBAccessUnitSplitter splitter = new AnnexBAccessUnitSplitter(onAnnexB);
        reader = new Thread(() -> readLoop(splitter), "ffmpeg-videotest-stdout");
        reader.setDaemon(true);
        reader.start();
        log.info("FFmpeg videotest capture started");
    }

    private void readLoop(AnnexBAccessUnitSplitter splitter) {
        byte[] chunk = new byte[8192];
        try (InputStream in = process.getInputStream()) {
            int n;
            while (!closed.get() && (n = in.read(chunk)) >= 0) {
                if (n > 0) {
                    splitter.accept(chunk, 0, n);
                }
            }
            splitter.flush();
        } catch (Exception e) {
            if (!closed.get()) {
                log.debug("ffmpeg videotest read ended: {}", e.toString());
            }
        }
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
        process.destroy();
        try {
            reader.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        process.destroyForcibly();
    }
}
