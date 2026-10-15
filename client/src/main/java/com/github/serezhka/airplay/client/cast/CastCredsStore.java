package com.github.serezhka.airplay.client.cast;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Persistent controller identity per AirPlay {@code deviceid} (MAC), used for
 * pair-verify reconnect without a new PIN.
 */
public final class CastCredsStore {

    private static final Pattern ENTRY = Pattern.compile(
            "\"([^\"]+)\"\\s*:\\s*\\{([^}]*)\\}", Pattern.DOTALL);
    private static final Pattern PAIRING_ID = Pattern.compile(
            "\"pairing_id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern SEED = Pattern.compile(
            "\"ed25519_seed\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ACCESSORY_ID = Pattern.compile(
            "\"accessory_pairing_id\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern ACCESSORY_LTPK = Pattern.compile(
            "\"accessory_ltpk\"\\s*:\\s*\"([^\"]*)\"");

    private final Path path;

    public CastCredsStore() {
        String override = System.getenv("AIRPLAY_CAST_CREDS");
        if (override != null && !override.isBlank()) {
            this.path = Path.of(override);
        } else {
            this.path = Path.of(System.getProperty("user.home"), ".java-airplay", "cast-creds.json");
        }
    }

    public CastCredsStore(Path path) {
        this.path = path;
    }

    public Path path() {
        return path;
    }

    public Optional<Entry> find(String deviceId) {
        if (deviceId == null || deviceId.isBlank() || !Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            String text = Files.readString(path);
            Matcher entries = ENTRY.matcher(text);
            while (entries.find()) {
                if (!entries.group(1).equalsIgnoreCase(deviceId.trim())) {
                    continue;
                }
                String body = entries.group(2);
                Matcher id = PAIRING_ID.matcher(body);
                Matcher seed = SEED.matcher(body);
                if (!id.find() || !seed.find()) {
                    return Optional.empty();
                }
                Matcher accId = ACCESSORY_ID.matcher(body);
                Matcher accPk = ACCESSORY_LTPK.matcher(body);
                String accessoryPairingId = accId.find() ? accId.group(1) : "";
                String accessoryLtpkB64 = accPk.find() ? accPk.group(1) : "";
                byte[] accessoryLtpk = accessoryLtpkB64.isBlank()
                        ? null
                        : Base64.getDecoder().decode(accessoryLtpkB64);
                return Optional.of(new Entry(
                        deviceId.trim(),
                        id.group(1),
                        Base64.getDecoder().decode(seed.group(1)),
                        accessoryPairingId.isBlank() ? null : accessoryPairingId.getBytes(StandardCharsets.UTF_8),
                        accessoryLtpk));
            }
        } catch (Exception ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** Prefer exact deviceid; otherwise first entry (dev convenience for a single TV). */
    public Optional<Entry> findOrAny(String deviceId) {
        Optional<Entry> exact = find(deviceId);
        if (exact.isPresent()) {
            return exact;
        }
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            // Re-scan file for first usable entry (or matching deviceid).
            String text = Files.readString(path);
            Matcher entries = ENTRY.matcher(text);
            Entry first = null;
            while (entries.find()) {
                String key = entries.group(1);
                Optional<Entry> e = find(key);
                if (e.isEmpty()) {
                    continue;
                }
                if (deviceId != null && !deviceId.isBlank() && key.equalsIgnoreCase(deviceId.trim())) {
                    return e;
                }
                if (first == null) {
                    first = e.get();
                }
            }
            return Optional.ofNullable(first);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public void save(Entry entry) throws Exception {
        Files.createDirectories(path.getParent());
        String accessoryId = entry.accessoryPairingId() == null
                ? ""
                : new String(entry.accessoryPairingId(), StandardCharsets.UTF_8);
        String accessoryLtpk = entry.accessoryLtpk() == null
                ? ""
                : Base64.getEncoder().encodeToString(entry.accessoryLtpk());
        String block = "  \"" + entry.deviceId() + "\": {\n"
                + "    \"pairing_id\": \"" + entry.pairingId() + "\",\n"
                + "    \"ed25519_seed\": \"" + Base64.getEncoder().encodeToString(entry.ed25519Seed()) + "\",\n"
                + "    \"accessory_pairing_id\": \"" + accessoryId + "\",\n"
                + "    \"accessory_ltpk\": \"" + accessoryLtpk + "\"\n"
                + "  }";
        if (!Files.isRegularFile(path)) {
            Files.writeString(path, "{\n" + block + "\n}\n");
            return;
        }
        String text = Files.readString(path);
        Matcher entries = ENTRY.matcher(text);
        StringBuilder rebuilt = new StringBuilder("{\n");
        boolean replaced = false;
        boolean first = true;
        while (entries.find()) {
            String key = entries.group(1);
            if (key.equalsIgnoreCase(entry.deviceId())) {
                if (!first) {
                    rebuilt.append(",\n");
                }
                rebuilt.append(block);
                replaced = true;
            } else {
                if (!first) {
                    rebuilt.append(",\n");
                }
                rebuilt.append("  \"").append(key).append("\": {").append(entries.group(2)).append("}");
            }
            first = false;
        }
        if (!replaced) {
            if (!first) {
                rebuilt.append(",\n");
            }
            rebuilt.append(block);
        }
        rebuilt.append("\n}\n");
        Files.writeString(path, rebuilt.toString());
    }

    public record Entry(
            String deviceId,
            String pairingId,
            byte[] ed25519Seed,
            byte[] accessoryPairingId,
            byte[] accessoryLtpk) {
    }
}
