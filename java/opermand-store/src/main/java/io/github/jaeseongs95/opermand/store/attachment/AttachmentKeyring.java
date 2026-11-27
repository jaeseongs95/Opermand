package io.github.jaeseongs95.opermand.store.attachment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.opermand.app.storage.PrivateStorages;
import io.github.jaeseongs95.opermand.store.StorageRoots;

/**
 * File-backed AES-256 keyring for operation-body attachments (stage 4 design B-15).
 *
 * <p>Format: UTF-8 text file starting with {@code # PAT1-KEYRING v1}, followed by one line per key:
 * {@code <keyId> <ACTIVE|RETIRED> <createdAtIso> <base64-32-byte-key>}.
 *
 * <p>Invariants:
 * <ul>
 *   <li>Created private to its owner via {@link PrivateStorages}, written to a temp file + {@code force(true)} + {@code REPLACE_EXISTING} move (no {@code ATOMIC_MOVE}, D-0150).</li>
 *   <li>Exactly one key is {@code ACTIVE} at any time; key rotation demotes the previous active key to {@code RETIRED} and retains it permanently so history remains decryptable forever (C2-1).</li>
 * </ul>
 */
@Component
public class AttachmentKeyring {

    public static final String DEFAULT_FILE_NAME = "attachment.keys";
    private static final String HEADER = "# PAT1-KEYRING v1";
    private static final int KEY_BYTES = 32;
    private static final int KEY_ID_RANDOM_BYTES = 8;

    private final Path keyFile;
    private final PrivateStorages storages;
    private final SecureRandom random;
    private final Clock clock;

    private final Map<String, KeyRecord> keys = new LinkedHashMap<>();
    private KeyRecord active;
    private boolean loaded;

    @Autowired
    public AttachmentKeyring(StorageRoots roots, PrivateStorages storages, SecureRandom random, Clock clock, ObjectProvider<AttachmentKeyLocation> overrideLocation) {
        this(resolvePath(roots, overrideLocation), storages, random, clock);
    }

    public AttachmentKeyring(Path keyFile, PrivateStorages storages, SecureRandom random, Clock clock) {
        this.keyFile = Objects.requireNonNull(keyFile, "keyFile").toAbsolutePath();
        this.storages = Objects.requireNonNull(storages, "storages");
        this.random = Objects.requireNonNull(random, "random");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    private static Path resolvePath(StorageRoots roots, ObjectProvider<AttachmentKeyLocation> overrideLocation) {
        Objects.requireNonNull(roots, "roots");
        AttachmentKeyLocation override = overrideLocation == null ? null : overrideLocation.getIfAvailable();
        return override != null ? override.file() : roots.keyring().resolve(DEFAULT_FILE_NAME);
    }

    public Path keyFile() {
        return keyFile;
    }

    /** Returns the currently active AES-256 key, creating the keyring file on first use if it does not yet exist. */
    public synchronized KeyEntry activeKey() {
        ensureLoaded(true);
        return new KeyEntry(active.keyId(), active.secretKey());
    }

    /** Looks up an active or retired key by id. Reloads from disk once on a cache miss in case an external rotation occurred. */
    public synchronized Optional<SecretKey> findKey(String keyId) {
        Objects.requireNonNull(keyId, "keyId");
        ensureLoaded(false);
        KeyRecord found = keys.get(keyId);
        if (found != null) {
            return Optional.of(found.secretKey());
        }
        if (Files.exists(keyFile)) {
            reloadFromDisk();
            found = keys.get(keyId);
            if (found != null) {
                return Optional.of(found.secretKey());
            }
        }
        return Optional.empty();
    }

    /** Rotates to a fresh active AES-256 key while permanently keeping all existing keys as {@code RETIRED}. */
    public synchronized KeyEntry rotate() {
        ensureLoaded(true);
        List<KeyRecord> updated = new ArrayList<>(keys.size() + 1);
        for (KeyRecord existing : keys.values()) {
            updated.add(existing.status() == KeyStatus.ACTIVE
                ? new KeyRecord(existing.keyId(), KeyStatus.RETIRED, existing.createdAt(), existing.rawKey())
                : existing);
        }
        KeyRecord fresh = generateActive();
        updated.add(fresh);
        persist(updated);
        applyLoaded(updated);
        return new KeyEntry(fresh.keyId(), fresh.secretKey());
    }

    private void ensureLoaded(boolean createIfMissing) {
        if (loaded) {
            return;
        }
        if (Files.exists(keyFile)) {
            reloadFromDisk();
            return;
        }
        if (!createIfMissing) {
            return;
        }
        KeyRecord initial = generateActive();
        persist(List.of(initial));
        applyLoaded(List.of(initial));
    }

    private void reloadFromDisk() {
        try {
            storages.requireRegularFile(keyFile);
            List<String> lines = Files.readAllLines(keyFile, StandardCharsets.UTF_8);
            List<KeyRecord> parsed = parse(lines);
            applyLoaded(parsed);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read attachment keyring " + keyFile, e);
        }
    }

    private List<KeyRecord> parse(List<String> lines) {
        List<KeyRecord> parsed = new ArrayList<>();
        boolean seenHeader = false;
        int activeCount = 0;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (!seenHeader && HEADER.equals(line)) {
                seenHeader = true;
                continue;
            }
            if (line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length != 4) {
                throw new IllegalStateException("Malformed attachment keyring entry in " + keyFile);
            }
            String id = parts[0];
            if (id.isEmpty() || id.getBytes(StandardCharsets.UTF_8).length > 255) {
                throw new IllegalStateException("Invalid key id length in " + keyFile);
            }
            KeyStatus status = KeyStatus.valueOf(parts[1]);
            Instant createdAt = Instant.parse(parts[2]);
            byte[] keyBytes = Base64.getDecoder().decode(parts[3]);
            if (keyBytes.length != KEY_BYTES) {
                throw new IllegalStateException("Attachment key " + id + " must be 32 bytes (AES-256)");
            }
            if (status == KeyStatus.ACTIVE) {
                activeCount++;
            }
            parsed.add(new KeyRecord(id, status, createdAt, keyBytes));
        }
        if (!seenHeader || activeCount != 1) {
            throw new IllegalStateException("Attachment keyring " + keyFile + " must have header and exactly one ACTIVE key (found " + activeCount + ")");
        }
        return parsed;
    }

    private void applyLoaded(List<KeyRecord> records) {
        keys.clear();
        active = null;
        for (KeyRecord record : records) {
            if (keys.put(record.keyId(), record) != null) {
                throw new IllegalStateException("Duplicate key id " + record.keyId() + " in " + keyFile);
            }
            if (record.status() == KeyStatus.ACTIVE) {
                active = record;
            }
        }
        if (active == null) {
            throw new IllegalStateException("Attachment keyring has no ACTIVE key");
        }
        loaded = true;
    }

    private KeyRecord generateActive() {
        byte[] idBytes = new byte[KEY_ID_RANDOM_BYTES];
        random.nextBytes(idBytes);
        String keyId = "k-" + HexFormat.of().formatHex(idBytes);
        byte[] rawKey = new byte[KEY_BYTES];
        random.nextBytes(rawKey);
        return new KeyRecord(keyId, KeyStatus.ACTIVE, clock.instant(), rawKey);
    }

    private void persist(List<KeyRecord> records) {
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER).append('\n');
        for (KeyRecord record : records) {
            sb.append(record.keyId()).append(' ')
                .append(record.status().name()).append(' ')
                .append(record.createdAt().toString()).append(' ')
                .append(Base64.getEncoder().encodeToString(record.rawKey())).append('\n');
        }
        byte[] payload = sb.toString().getBytes(StandardCharsets.UTF_8);
        Path parent = keyFile.getParent();
        Path tmp = null;
        try {
            if (parent != null) {
                storages.createFolder(parent);
                storages.requireFolder(parent);
            }
            byte[] suffix = new byte[8];
            random.nextBytes(suffix);
            tmp = (parent != null ? parent : Path.of(".")).resolve(".tmp-keyring-" + HexFormat.of().formatHex(suffix));
            try (FileChannel channel = storages.open(tmp, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
                ByteBuffer buffer = ByteBuffer.wrap(payload);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            // No ATOMIC_MOVE (D-0150): Windows cross-volume / AV-scanner safe replacement after fsync.
            Files.move(tmp, keyFile, StandardCopyOption.REPLACE_EXISTING);
            tmp = null;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to persist attachment keyring " + keyFile, e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // Best-effort temp cleanup.
                }
            }
        }
    }

    public record KeyEntry(String keyId, SecretKey secretKey) {
        public KeyEntry {
            Objects.requireNonNull(keyId, "keyId");
            Objects.requireNonNull(secretKey, "secretKey");
        }
    }

    private enum KeyStatus {
        ACTIVE, RETIRED
    }

    private static final class KeyRecord {
        private final String keyId;
        private final KeyStatus status;
        private final Instant createdAt;
        private final byte[] rawKey;

        private KeyRecord(String keyId, KeyStatus status, Instant createdAt, byte[] rawKey) {
            this.keyId = keyId;
            this.status = status;
            this.createdAt = createdAt;
            this.rawKey = rawKey;
        }

        String keyId() {
            return keyId;
        }

        KeyStatus status() {
            return status;
        }

        Instant createdAt() {
            return createdAt;
        }

        byte[] rawKey() {
            return rawKey;
        }

        SecretKey secretKey() {
            return new SecretKeySpec(rawKey, "AES");
        }
    }
}
