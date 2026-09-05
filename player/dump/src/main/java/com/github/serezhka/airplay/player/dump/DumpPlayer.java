package com.github.serezhka.airplay.player.dump;

import com.github.serezhka.airplay.lib.AudioStreamInfo;
import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.server.AirPlayConsumer;
import com.github.serezhka.airplay.server.ControlExchange;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
public class DumpPlayer implements AirPlayConsumer {

    private static final DateTimeFormatter DIR_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final DumpConfig config;
    private final Clock clock;

    private Path sessionDir;
    private String sessionId = "unknown";
    private Instant startedAt;

    private int protocolSeq;
    private int videoSeq;
    private int audioSeq;
    private int playlistSeq;
    private int playlistContentSeq;
    private int artworkSeq;
    private int metadataSeq;

    private FileChannel videoChannel;
    private FileChannel audioChannel;
    private Path currentVideoFile;
    private Path currentAudioFile;
    private boolean currentAudioIsCaf;
    private String audioExtension = "bin";
    private final List<Integer> alacPacketSizes = new ArrayList<>();

    private VideoStreamInfo videoStreamInfo;
    private AudioStreamInfo audioStreamInfo;
    private String playlistUri;

    private final List<String> videoFiles = new ArrayList<>();
    private final List<String> audioFiles = new ArrayList<>();

    public DumpPlayer() {
        this(new DumpConfig());
    }

    public DumpPlayer(DumpConfig config) {
        this(config, Clock.systemUTC());
    }

    public DumpPlayer(DumpConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    Path sessionDirectory() {
        return sessionDir;
    }

    @Override
    public synchronized void onControlExchange(ControlExchange exchange) {
        if (exchange.sessionId() != null && !exchange.sessionId().isBlank()) {
            sessionId = sanitize(exchange.sessionId(), "unknown");
        }
        ensureSession();
        if (config.isProtocol()) {
            writeProtocol(exchange);
        }
        if (config.isArtwork()) {
            writeArtworkIfPresent(exchange);
        }
        writeSessionJson();
    }

    @Override
    public synchronized void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        this.videoStreamInfo = videoStreamInfo;
        if (videoStreamInfo != null && videoStreamInfo.getStreamConnectionId() != null
                && "unknown".equals(sessionId)) {
            sessionId = sanitize(videoStreamInfo.getStreamConnectionId(), "media");
        }
        ensureSession();
        closeVideo();
        if (!config.isVideo()) {
            writeSessionJson();
            return;
        }
        openNextVideoFile();
        writeSessionJson();
    }

    @Override
    public synchronized void onVideo(byte[] bytes) {
        if (!config.isVideo()) {
            return;
        }
        if (videoChannel == null) {
            ensureSession();
            openNextVideoFile();
        }
        write(videoChannel, bytes);
    }

    @Override
    public synchronized void onVideoSrcDisconnect() {
        closeVideo();
    }

    @Override
    public synchronized void onAudioFormat(AudioStreamInfo audioStreamInfo) {
        this.audioStreamInfo = audioStreamInfo;
        audioExtension = audioExtension(audioStreamInfo);
        ensureSession();
        closeAudio();
        if (!config.isAudio()) {
            writeSessionJson();
            return;
        }
        openNextAudioFile();
        writeSessionJson();
    }

    @Override
    public synchronized void onAudio(byte[] bytes) {
        if (!config.isAudio()) {
            return;
        }
        if (audioChannel == null) {
            ensureSession();
            openNextAudioFile();
        }
        write(audioChannel, bytes);
        if (currentAudioIsCaf && bytes != null && bytes.length > 0) {
            alacPacketSizes.add(bytes.length);
        }
    }

    @Override
    public synchronized void onAudioSrcDisconnect() {
        closeAudio();
    }

    @Override
    public synchronized void onMediaPlaylist(String playlistUri) {
        this.playlistUri = playlistUri;
        ensureSession();
        if (!config.isPlaylist() || playlistUri == null || playlistUri.isBlank()) {
            writeSessionJson();
            return;
        }
        playlistSeq++;
        Path extras = sessionDir.resolve("extras");
        Path uriFile = extras.resolve(String.format(Locale.ROOT, "playlist-%03d.uri.txt", playlistSeq));
        writeBytes(uriFile, playlistUri.getBytes(StandardCharsets.UTF_8));
        writeSessionJson();
    }

    @Override
    public synchronized void onMediaPlaylistContent(String playlistUri, String content) {
        ensureSession();
        if (!config.isPlaylist() || content == null || content.isBlank()) {
            return;
        }
        String kind = playlistUri != null && playlistUri.contains("mediadata") ? "media" : "master";
        playlistContentSeq++;
        writeBytes(sessionDir.resolve("extras")
                .resolve(String.format(Locale.ROOT, "playlist-%03d-%s.m3u8", playlistContentSeq, kind)),
                content.getBytes(StandardCharsets.UTF_8));
        writeSessionJson();
    }

    @PreDestroy
    public synchronized void close() {
        closeVideo();
        closeAudio();
        if (sessionDir != null) {
            writeSessionJson();
        }
    }

    private void closeVideo() {
        Path closed = currentVideoFile;
        closeQuietly(videoChannel);
        videoChannel = null;
        currentVideoFile = null;
        remuxVideo(closed);
    }

    private void closeAudio() {
        if (audioChannel != null && currentAudioIsCaf) {
            try {
                int frames = audioStreamInfo != null && audioStreamInfo.getSamplesPerFrame() > 0
                        ? audioStreamInfo.getSamplesPerFrame() : 352;
                AlacCaf.finish(audioChannel, alacPacketSizes, frames);
            } catch (IOException e) {
                log.error("Failed to finalize CAF header", e);
            }
        }
        closeQuietly(audioChannel);
        audioChannel = null;
        currentAudioFile = null;
        currentAudioIsCaf = false;
        alacPacketSizes.clear();
    }

    private void openNextVideoFile() {
        videoSeq++;
        currentVideoFile = sessionDir.resolve("media").resolve(String.format(Locale.ROOT, "video-%03d.h264", videoSeq));
        videoChannel = open(currentVideoFile);
        videoFiles.add(relative(currentVideoFile));
    }

    private void openNextAudioFile() {
        audioSeq++;
        alacPacketSizes.clear();
        currentAudioIsCaf = "caf".equals(audioExtension);
        currentAudioFile = sessionDir.resolve("media")
                .resolve(String.format(Locale.ROOT, "audio-%03d.%s", audioSeq, audioExtension));
        audioChannel = open(currentAudioFile);
        if (currentAudioIsCaf && audioChannel != null) {
            try {
                AlacCaf.writePreamble(audioChannel, audioStreamInfo);
            } catch (IOException e) {
                log.error("Failed to write CAF header", e);
                currentAudioIsCaf = false;
            }
        }
        audioFiles.add(relative(currentAudioFile));
    }

    private void ensureSession() {
        if (sessionDir != null) {
            return;
        }
        startedAt = clock.instant();
        Path root = Path.of(config.getDirectory() == null || config.getDirectory().isBlank()
                ? "dumps" : config.getDirectory());
        sessionDir = root.resolve(DIR_TIME.format(startedAt) + "_" + sessionId);
        try {
            Files.createDirectories(sessionDir.resolve("protocol"));
            Files.createDirectories(sessionDir.resolve("media"));
            Files.createDirectories(sessionDir.resolve("extras"));
        } catch (IOException e) {
            log.error("Failed to create dump session directory {}", sessionDir, e);
        }
        log.info("Dump session directory: {}", sessionDir.toAbsolutePath());
    }

    private void writeProtocol(ControlExchange exchange) {
        protocolSeq++;
        String slug = slug(exchange.method() + "_" + exchange.uri());
        String prefix = String.format(Locale.ROOT, "%04d_%s", protocolSeq, slug);
        Path protocolDir = sessionDir.resolve("protocol");
        writeBytes(protocolDir.resolve(prefix + ".request.headers.txt"),
                headersText(exchange.method(), exchange.uri(), exchange.protocol(), exchange.requestHeaders()));
        writeBytes(protocolDir.resolve(prefix + ".request.bin"), exchange.requestBody());
        writeBytes(protocolDir.resolve(prefix + ".response.headers.txt"),
                headersText("HTTP", String.valueOf(exchange.responseStatus()), exchange.protocol(), exchange.responseHeaders()));
        writeBytes(protocolDir.resolve(prefix + ".response.bin"), exchange.responseBody());
        String index = "{\"seq\":" + protocolSeq
                + ",\"timestamp\":" + jsonString(exchange.timestamp() == null ? clock.instant().toString() : exchange.timestamp().toString())
                + ",\"sessionId\":" + jsonString(exchange.sessionId())
                + ",\"protocol\":" + jsonString(exchange.protocol())
                + ",\"method\":" + jsonString(exchange.method())
                + ",\"uri\":" + jsonString(exchange.uri())
                + ",\"requestBytes\":" + exchange.requestBody().length
                + ",\"responseStatus\":" + exchange.responseStatus()
                + ",\"responseBytes\":" + exchange.responseBody().length
                + ",\"prefix\":" + jsonString(prefix)
                + "}\n";
        append(protocolDir.resolve("index.ndjson"), index.getBytes(StandardCharsets.UTF_8));
    }

    private void writeArtworkIfPresent(ControlExchange exchange) {
        String contentType = exchange.requestContentType();
        if (contentType == null || exchange.requestBody().length == 0) {
            return;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        Path extras = sessionDir.resolve("extras");
        if (type.startsWith("image/jpeg") || type.contains("image/jpg")) {
            artworkSeq++;
            writeBytes(extras.resolve(String.format(Locale.ROOT, "artwork-%03d.jpg", artworkSeq)), exchange.requestBody());
        } else if (type.startsWith("image/png")) {
            artworkSeq++;
            writeBytes(extras.resolve(String.format(Locale.ROOT, "artwork-%03d.png", artworkSeq)), exchange.requestBody());
        } else if (type.contains("dmap")) {
            metadataSeq++;
            writeBytes(extras.resolve(String.format(Locale.ROOT, "metadata-%03d.dmap", metadataSeq)), exchange.requestBody());
        }
    }

    private void remuxVideo(Path h264) {
        if (h264 == null || !Files.isRegularFile(h264)) {
            return;
        }
        try {
            if (Files.size(h264) < 64) {
                return;
            }
        } catch (IOException e) {
            return;
        }
        Path mp4 = h264.resolveSibling(h264.getFileName().toString().replace(".h264", ".mp4"));
        int fps = Math.max(1, config.getVideoFps());
        try {
            Process process = new ProcessBuilder(
                    "ffmpeg", "-y", "-loglevel", "error",
                    "-fflags", "+genpts",
                    "-f", "h264",
                    "-framerate", String.valueOf(fps),
                    "-i", h264.toAbsolutePath().toString(),
                    "-c:v", "copy",
                    "-movflags", "+faststart",
                    mp4.toAbsolutePath().toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return;
            }
            if (process.exitValue() == 0 && Files.isRegularFile(mp4)) {
                String relativeMp4 = relative(mp4);
                if (!videoFiles.contains(relativeMp4)) {
                    videoFiles.add(relativeMp4);
                    writeSessionJson();
                }
            }
        } catch (Exception e) {
            log.debug("ffmpeg remux skipped for {}: {}", h264, e.getMessage());
        }
    }

    private void writeSessionJson() {
        if (sessionDir == null) {
            return;
        }
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"sessionId\": ").append(jsonString(sessionId)).append(",\n");
        json.append("  \"startedAt\": ").append(jsonString(startedAt == null ? null : startedAt.toString())).append(",\n");
        json.append("  \"video\": {\n");
        if (videoStreamInfo != null) {
            json.append("    \"streamConnectionId\": ").append(jsonString(videoStreamInfo.getStreamConnectionId())).append(",\n");
        }
        json.append("    \"fps\": ").append(Math.max(1, config.getVideoFps())).append(",\n");
        json.append("    \"files\": ").append(jsonArray(videoFiles)).append("\n");
        json.append("  },\n");
        json.append("  \"audio\": {\n");
        if (audioStreamInfo != null) {
            json.append("    \"compressionType\": ").append(jsonString(String.valueOf(audioStreamInfo.getCompressionType()))).append(",\n");
            json.append("    \"audioFormat\": ").append(jsonString(String.valueOf(audioStreamInfo.getAudioFormat()))).append(",\n");
            json.append("    \"samplesPerFrame\": ").append(audioStreamInfo.getSamplesPerFrame()).append(",\n");
        }
        json.append("    \"files\": ").append(jsonArray(audioFiles)).append("\n");
        json.append("  },\n");
        json.append("  \"playlistUri\": ").append(jsonString(playlistUri)).append("\n");
        json.append("}\n");
        writeBytes(sessionDir.resolve("session.json"), json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private FileChannel open(Path file) {
        try {
            Files.createDirectories(file.getParent());
            return FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            log.error("Failed to open dump file {}", file, e);
            return null;
        }
    }

    private void write(FileChannel channel, byte[] bytes) {
        if (channel == null || bytes == null || bytes.length == 0) {
            return;
        }
        try {
            channel.write(ByteBuffer.wrap(bytes));
        } catch (IOException e) {
            log.error("Failed to write dump data", e);
        }
    }

    private void writeBytes(Path file, byte[] bytes) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes == null ? new byte[0] : bytes,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            log.error("Failed to write {}", file, e);
        }
    }

    private void append(Path file, byte[] bytes) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Failed to append {}", file, e);
        }
    }

    private void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException e) {
            log.debug("Failed to close dump channel", e);
        }
    }

    private String relative(Path file) {
        return sessionDir.relativize(file).toString().replace('\\', '/');
    }

    private static String audioExtension(AudioStreamInfo info) {
        if (info == null || info.getCompressionType() == null) {
            return "bin";
        }
        return switch (info.getCompressionType()) {
            case ALAC -> "caf";
            case AAC, AAC_ELD -> "aac";
            case LPCM -> "pcm";
            case OPUS -> "opus";
        };
    }

    private static byte[] headersText(String method, String uri, String protocol, Map<String, String> headers) {
        StringBuilder text = new StringBuilder();
        text.append(method).append(' ').append(uri);
        if (protocol != null && !protocol.isBlank()) {
            text.append(' ').append(protocol);
        }
        text.append('\n');
        if (headers != null) {
            headers.forEach((name, value) -> text.append(name).append(": ").append(value).append('\n'));
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String slug(String value) {
        String slug = value == null ? "request" : value.replaceAll("[^A-Za-z0-9]+", "_");
        slug = slug.replaceAll("^_+|_+$", "");
        if (slug.isBlank()) {
            slug = "request";
        }
        return slug.length() > 60 ? slug.substring(0, 60) : slug;
    }

    private static String sanitize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String cleaned = value.replaceAll("[^A-Za-z0-9._-]+", "_");
        return cleaned.isBlank() ? fallback : cleaned;
    }

    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private static String jsonArray(List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(", ");
            }
            json.append(jsonString(values.get(i)));
        }
        return json.append("]").toString();
    }
}
