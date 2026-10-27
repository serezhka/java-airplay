package com.github.serezhka.airplay.app.mse.cast;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * JPEG image2pipe → libx264 Annex-B. Used when the cast tab is HTTPS (in-page
 * {@code ws://127.0.0.1} is blocked as mixed content).
 */
@Slf4j
final class FfmpegH264Pipe implements AutoCloseable {

    private final Process process;
    private final OutputStream stdin;
    private final Thread stdout;
    private final Thread stderr;
    private final AtomicBoolean closed = new AtomicBoolean();

    FfmpegH264Pipe(Consumer<byte[]> onAnnexB) throws IOException {
        List<String> cmd = List.of(
                "ffmpeg",
                "-hide_banner",
                "-loglevel", "error",
                "-f", "image2pipe",
                "-framerate", "15",
                "-i", "-",
                "-an",
                "-c:v", "libx264",
                "-tune", "zerolatency",
                "-preset", "ultrafast",
                "-pix_fmt", "yuv420p",
                "-profile:v", "baseline",
                "-bf", "0",
                "-g", "30",
                "-x264-params", "aud=1",
                "-f", "h264",
                "-");
        process = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.PIPE).start();
        stdin = process.getOutputStream();
        AnnexBAccessUnitSplitter splitter = new AnnexBAccessUnitSplitter(onAnnexB);
        stdout = new Thread(() -> readLoop(process.getInputStream(), splitter), "mse-cast-ffmpeg-out");
        stdout.setDaemon(true);
        stdout.start();
        stderr = new Thread(() -> drain(process.getErrorStream()), "mse-cast-ffmpeg-err");
        stderr.setDaemon(true);
        stderr.start();
        log.info("MSE cast ffmpeg H.264 pipe started");
    }

    synchronized void writeJpeg(byte[] jpeg) throws IOException {
        if (closed.get() || jpeg == null || jpeg.length == 0) {
            return;
        }
        stdin.write(jpeg);
        stdin.flush();
    }

    private void readLoop(InputStream in, AnnexBAccessUnitSplitter splitter) {
        byte[] chunk = new byte[8192];
        try {
            int n;
            while (!closed.get() && (n = in.read(chunk)) >= 0) {
                if (n > 0) {
                    splitter.accept(chunk, 0, n);
                }
            }
            splitter.flush();
        } catch (Exception e) {
            if (!closed.get()) {
                log.debug("ffmpeg stdout ended: {}", e.toString());
            }
        }
    }

    private static void drain(InputStream in) {
        try (in) {
            in.transferTo(OutputStream.nullOutputStream());
        } catch (Exception ignored) {
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            stdin.close();
        } catch (Exception ignored) {
        }
        process.destroy();
        try {
            stdout.join(2000);
            stderr.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        process.destroyForcibly();
    }
}
