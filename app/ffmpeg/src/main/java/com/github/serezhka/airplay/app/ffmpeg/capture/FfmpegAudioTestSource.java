package com.github.serezhka.airplay.app.ffmpeg.capture;

import com.github.serezhka.airplay.client.audio.AudioSource;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * {@code lavfi sine} → AAC-LC ADTS on stdout for audio cast smoke tests.
 */
@Slf4j
public final class FfmpegAudioTestSource implements AudioSource {

    private final Process process;
    private final Thread reader;
    private final AtomicBoolean closed = new AtomicBoolean();

    public FfmpegAudioTestSource(Consumer<byte[]> onAdtsFrame) {
        List<String> cmd = List.of(
                "ffmpeg",
                "-hide_banner",
                "-loglevel", "error",
                "-f", "lavfi",
                "-i", "sine=frequency=440:sample_rate=44100",
                "-ac", "2",
                "-c:a", "aac",
                "-b:a", "128k",
                "-f", "adts",
                "-");
        try {
            process = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.PIPE).start();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start ffmpeg audio test capture", e);
        }
        Thread err = new Thread(() -> drain(process.getErrorStream()), "ffmpeg-audiotest-stderr");
        err.setDaemon(true);
        err.start();
        reader = new Thread(() -> readAdts(onAdtsFrame), "ffmpeg-audiotest-stdout");
        reader.setDaemon(true);
        reader.start();
        log.info("FFmpeg audio test source started (AAC-LC ADTS sine)");
    }

    private void readAdts(Consumer<byte[]> onAdtsFrame) {
        byte[] buf = new byte[4096];
        int len = 0;
        try (InputStream in = process.getInputStream()) {
            while (!closed.get()) {
                if (len < 7) {
                    int n = in.read(buf, len, buf.length - len);
                    if (n < 0) {
                        break;
                    }
                    len += n;
                    continue;
                }
                if ((buf[0] & 0xFF) != 0xFF || (buf[1] & 0xF0) != 0xF0) {
                    System.arraycopy(buf, 1, buf, 0, len - 1);
                    len--;
                    continue;
                }
                int frameLen = ((buf[3] & 0x03) << 11) | ((buf[4] & 0xFF) << 3) | ((buf[5] & 0xE0) >> 5);
                if (frameLen < 7 || frameLen > buf.length) {
                    System.arraycopy(buf, 1, buf, 0, len - 1);
                    len--;
                    continue;
                }
                while (len < frameLen) {
                    int n = in.read(buf, len, buf.length - len);
                    if (n < 0) {
                        return;
                    }
                    len += n;
                }
                byte[] frame = new byte[frameLen];
                System.arraycopy(buf, 0, frame, 0, frameLen);
                System.arraycopy(buf, frameLen, buf, 0, len - frameLen);
                len -= frameLen;
                onAdtsFrame.accept(frame);
            }
        } catch (Exception e) {
            if (!closed.get()) {
                log.debug("ffmpeg audiotest read ended: {}", e.toString());
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
