package com.github.serezhka.airplay.app.common.test;

import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import com.github.serezhka.airplay.server.Playback;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;

/**
 * Shared Annex-B / audio fixtures and feed helpers for player integration tests.
 * Audio regen notes: {@code fixtures/audio/README.md}.
 */
public final class PlaybackFixture {

    private static final int FRAME_COUNT = 60;
    private static final long FRAME_DELAY_MILLIS = 33;

    public static byte[] h264() {
        return resourceBytes("/test-pattern.h264");
    }

    /**
     * Annex-B NAL units from {@link #h264()}, each slice including its start code.
     * Bench feeds must not split mid-NAL (native decoders SIGSEGV); ffplay stdin must
     * not receive the whole clip per tick (pipe back-pressure stalls the run).
     */
    public static List<byte[]> annexBNalUnits() {
        byte[] blob = h264();
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < blob.length; i++) {
            if (annexBStartCodeLength(blob, i) != 0) {
                starts.add(i);
            }
        }
        if (starts.isEmpty()) {
            return List.of(blob);
        }
        List<byte[]> nals = new ArrayList<>(starts.size());
        for (int s = 0; s < starts.size(); s++) {
            int from = starts.get(s);
            int to = s + 1 < starts.size() ? starts.get(s + 1) : blob.length;
            nals.add(Arrays.copyOfRange(blob, from, to));
        }
        return nals;
    }

    /** Stereo AAC-LC ADTS sine at 44.1 kHz ({@code fixtures/audio/sine-44100-aac-lc.adts}). */
    public static byte[] aacLcAdts() {
        return resourceBytes("/fixtures/audio/sine-44100-aac-lc.adts");
    }

    /**
     * Length-prefixed ({@code u32 BE} + payload) raw ALAC frames
     * ({@code fixtures/audio/sine-44100-alac.lp}).
     */
    public static List<byte[]> alacFrames() {
        return lengthPrefixedFrames("/fixtures/audio/sine-44100-alac.lp");
    }

    /**
     * Length-prefixed raw AAC-ELD frames ({@code fixtures/audio/sine-44100-aac-eld.lp}).
     */
    public static List<byte[]> aacEldFrames() {
        return lengthPrefixedFrames("/fixtures/audio/sine-44100-aac-eld.lp");
    }

    public static AudioStreamInfo aacLcStreamInfo() {
        return new AudioStreamInfo.AudioStreamInfoBuilder()
                .compressionType(AudioStreamInfo.CompressionType.AAC)
                .audioFormat(AudioStreamInfo.AudioFormat.AAC_LC_44100_2)
                .samplesPerFrame(1024)
                .build();
    }

    public static AudioStreamInfo alacStreamInfo() {
        return new AudioStreamInfo.AudioStreamInfoBuilder()
                .compressionType(AudioStreamInfo.CompressionType.ALAC)
                .audioFormat(AudioStreamInfo.AudioFormat.ALAC_44100_16_2)
                .samplesPerFrame(4096)
                .build();
    }

    public static AudioStreamInfo aacEldStreamInfo() {
        return new AudioStreamInfo.AudioStreamInfoBuilder()
                .compressionType(AudioStreamInfo.CompressionType.AAC_ELD)
                .audioFormat(AudioStreamInfo.AudioFormat.AAC_ELD_44100_2)
                .samplesPerFrame(480)
                .build();
    }

    public static void play(Playback player) throws InterruptedException {
        playVideo(player, FRAME_COUNT);
    }

    public static void playVideo(Playback player, int frameCount) throws InterruptedException {
        byte[] video = h264();
        int chunkSize = Math.max(1, (video.length + frameCount - 1) / frameCount);
        for (int offset = 0, frames = 0; offset < video.length && frames < frameCount; offset += chunkSize, frames++) {
            int length = Math.min(chunkSize, video.length - offset);
            byte[] chunk = new byte[length];
            System.arraycopy(video, offset, chunk, 0, length);
            player.onVideo(chunk);
            Thread.sleep(FRAME_DELAY_MILLIS);
        }
    }

    /** Push ADTS frames in ~20 ms chunks (rough AAC-LC frame pacing). */
    public static void playAacLc(Playback player) throws InterruptedException {
        byte[] audio = aacLcAdts();
        int chunk = Math.min(256, Math.max(64, audio.length / 40));
        for (int offset = 0; offset < audio.length; offset += chunk) {
            int length = Math.min(chunk, audio.length - offset);
            player.onAudio(audio, offset, length);
            Thread.sleep(20);
        }
    }

    /** Push raw ALAC AUs (~frame duration at 44.1 kHz / 4096). */
    public static void playAlac(Playback player) throws InterruptedException {
        playRawAudioFrames(player, alacFrames(), 90);
    }

    /** Push raw AAC-ELD AUs (~11 ms at 44.1 kHz / 480). */
    public static void playAacEld(Playback player) throws InterruptedException {
        playRawAudioFrames(player, aacEldFrames(), 11);
    }

    private static void playRawAudioFrames(Playback player, List<byte[]> frames, long delayMs)
            throws InterruptedException {
        for (byte[] frame : frames) {
            player.onAudio(frame);
            Thread.sleep(delayMs);
        }
    }

    private static List<byte[]> lengthPrefixedFrames(String path) {
        byte[] blob = resourceBytes(path);
        ByteBuffer buf = ByteBuffer.wrap(blob).order(ByteOrder.BIG_ENDIAN);
        List<byte[]> frames = new ArrayList<>();
        while (buf.remaining() >= 4) {
            int len = buf.getInt();
            if (len <= 0 || len > buf.remaining()) {
                throw new IllegalStateException("Corrupt length-prefixed fixture " + path
                        + " at " + buf.position() + " len=" + len);
            }
            byte[] frame = new byte[len];
            buf.get(frame);
            frames.add(frame);
        }
        if (frames.isEmpty()) {
            throw new IllegalStateException("Empty length-prefixed fixture " + path);
        }
        return frames;
    }

    public static void startMirror(Playback player) {
        player.onVideoFormat(new VideoStreamInfo("playback-test"));
    }

    public static void stopMirror(Playback player) {
        player.onVideoSrcDisconnect();
    }

    /**
     * Local VOD fixture: MPEG-TS + {@code mediadata.m3u8} (needs {@code ffmpeg} on PATH).
     *
     * @param seconds media duration; use ≥15 so pause/seek tests do not race ENDLIST
     */
    public static HlsFixture generateHlsPlaylist(Path dir, int seconds) throws Exception {
        Files.createDirectories(dir);
        Path media = dir.resolve("smoke.ts").toAbsolutePath().normalize();
        Path playlist = dir.resolve("mediadata.m3u8").toAbsolutePath().normalize();
        Process p = new ProcessBuilder(
                "ffmpeg", "-y",
                "-f", "lavfi", "-i", "testsrc=duration=" + seconds + ":size=320x240:rate=30",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=" + seconds,
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-tune", "zerolatency",
                "-c:a", "aac", "-ac", "2", "-ar", "44100",
                "-shortest", "-f", "mpegts", media.toString()
        ).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        boolean finished = p.waitFor(45, TimeUnit.SECONDS);
        if (!finished || p.exitValue() != 0) {
            throw new IllegalStateException("ffmpeg failed generating HLS fixture: " + output);
        }
        Files.writeString(playlist, """
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-TARGETDURATION:%d
                #EXTINF:%d.0,
                smoke.ts
                #EXT-X-ENDLIST
                """.formatted(seconds + 1, seconds));
        return new HlsFixture(playlist.toUri().toString(), media.toUri().toString(), seconds);
    }

    /** {@link #generateHlsPlaylist} result — playlist for GST; media URI is a reliable ffplay latch. */
    public record HlsFixture(String playlistUri, String mediaUri, int durationSeconds) {
    }

    public static boolean ffmpegAvailable() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static void awaitTrue(BooleanSupplier condition, long timeoutMs, String message)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError(message);
    }

    public static void awaitPosition(DoubleSupplier position, double min, long timeoutMs)
            throws InterruptedException {
        awaitTrue(() -> position.getAsDouble() >= min, timeoutMs,
                "position never reached " + min + ", last=" + position.getAsDouble());
    }

    public static void awaitNear(DoubleSupplier position, double target, double slack, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            double pos = position.getAsDouble();
            if (Math.abs(pos - target) <= slack || pos >= target - 0.1) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("seek did not land near " + target + ", last=" + position.getAsDouble());
    }

    private static int annexBStartCodeLength(byte[] blob, int offset) {
        if (offset + 4 <= blob.length && blob[offset] == 0 && blob[offset + 1] == 0 && blob[offset + 2] == 0
                && blob[offset + 3] == 1) {
            return 4;
        }
        if (offset + 3 <= blob.length && blob[offset] == 0 && blob[offset + 1] == 0 && blob[offset + 2] == 1) {
            return 3;
        }
        return 0;
    }

    private static byte[] resourceBytes(String path) {
        try (InputStream input = PlaybackFixture.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(input, "Missing fixture " + path).readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read fixture " + path, e);
        }
    }

    private PlaybackFixture() {
    }
}
