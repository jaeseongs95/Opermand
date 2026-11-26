package io.github.jaeseongs95.portal.store.attachment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.portal.app.attachment.AttachmentUnreadableException;
import io.github.jaeseongs95.portal.app.storage.PrivateStorages;
import io.github.jaeseongs95.portal.domain.attachment.AttachmentId;
import io.github.jaeseongs95.portal.domain.attachment.AttachmentRole;
import io.github.jaeseongs95.portal.store.StorageRoots;

/**
 * Disk layout and owner-only file operations for encrypted operation-body attachments (stage 4 design B-15).
 *
 * <p>Files live at {@code <attachments>/<id[0..2]>/<id>} with zero path metadata in the database. Writes go to a temporary file in the same bucket created private to
 * the owner via {@link PrivateStorages}, are {@code force(true)}d, and are moved into place with {@code REPLACE_EXISTING} (never {@code ATOMIC_MOVE}, D-0150).
 */
@Component
public class AttachmentFiles {

    private static final String QUARANTINE_FOLDER = "quarantine";
    private static final Pattern BUCKET_NAME = Pattern.compile("[a-f0-9]{2}");
    private static final Pattern ID_NAME = Pattern.compile("[a-f0-9]{48}");

    private final StorageRoots roots;
    private final PrivateStorages storages;
    private final SecureRandom random;

    public AttachmentFiles(StorageRoots roots, PrivateStorages storages, SecureRandom random) {
        this.roots = Objects.requireNonNull(roots, "roots");
        this.storages = Objects.requireNonNull(storages, "storages");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** Path of the encrypted blob derived exclusively from {@code id}. */
    public Path pathOf(AttachmentId id) {
        Objects.requireNonNull(id, "id");
        return roots.attachments().resolve(id.bucket()).resolve(id.value());
    }

    /** Path where an orphaned blob is quarantined. */
    public Path quarantinePathOf(AttachmentId id) {
        Objects.requireNonNull(id, "id");
        return roots.attachments().resolve(QUARANTINE_FOLDER).resolve(id.value());
    }

    /** Writes {@code ciphertext} to a private temp file, calls {@code force(true)}, and moves it into place without {@code ATOMIC_MOVE} (D-0150). */
    public void write(AttachmentId id, byte[] ciphertext) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ciphertext, "ciphertext");
        Path root = roots.attachments();
        Path bucket = root.resolve(id.bucket());
        Path target = bucket.resolve(id.value());
        Path tmp = null;
        try {
            storages.createFolder(root);
            storages.requireFolder(root);
            storages.createFolder(bucket);
            storages.requireFolder(bucket);
            byte[] suffix = new byte[8];
            random.nextBytes(suffix);
            tmp = bucket.resolve(".tmp-" + id.value() + "-" + HexFormat.of().formatHex(suffix));
            try (FileChannel channel = storages.open(tmp, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
                ByteBuffer buffer = ByteBuffer.wrap(ciphertext);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            tmp = null;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write attachment file " + id.value(), e);
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

    /** Reads all bytes of the attachment blob; missing file raises {@link AttachmentUnreadableException.Reason#MISSING_FILE}. */
    public byte[] read(AttachmentId id, AttachmentRole role) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(role, "role");
        Path file = pathOf(id);
        try {
            if (!Files.exists(file)) {
                throw new AttachmentUnreadableException(id, role, AttachmentUnreadableException.Reason.MISSING_FILE,
                    "Attachment file is missing: " + id.value());
            }
            return Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            throw new AttachmentUnreadableException(id, role, AttachmentUnreadableException.Reason.MISSING_FILE,
                "Attachment file is missing: " + id.value(), e);
        } catch (IOException e) {
            throw new AttachmentUnreadableException(id, role, AttachmentUnreadableException.Reason.IO_ERROR,
                "Attachment file could not be read: " + id.value(), e);
        }
    }

    /** Best-effort deletion for an uncommitted prepared attachment file after a DB rollback. */
    public void deleteIfExists(AttachmentId id) {
        Objects.requireNonNull(id, "id");
        try {
            Files.deleteIfExists(pathOf(id));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to discard prepared attachment " + id.value(), e);
        }
    }

    /** Scans valid bucket folders for attachment files whose last-modified time is at or before {@code cutoff}. */
    public Set<AttachmentId> storedFilesOlderThan(Instant cutoff) {
        Objects.requireNonNull(cutoff, "cutoff");
        Path root = roots.attachments();
        if (!Files.isDirectory(root)) {
            return Set.of();
        }
        Set<AttachmentId> found = new HashSet<>();
        try (Stream<Path> buckets = Files.list(root)) {
            for (Path bucket : buckets.toList()) {
                Path bucketName = bucket.getFileName();
                if (bucketName == null || !BUCKET_NAME.matcher(bucketName.toString()).matches() || !Files.isDirectory(bucket)) {
                    continue;
                }
                try (Stream<Path> entries = Files.list(bucket)) {
                    for (Path entry : entries.toList()) {
                        Path entryName = entry.getFileName();
                        if (entryName == null || !ID_NAME.matcher(entryName.toString()).matches() || !Files.isRegularFile(entry)) {
                            continue;
                        }
                        Instant modified = Files.getLastModifiedTime(entry).toInstant();
                        if (!modified.isAfter(cutoff)) {
                            found.add(new AttachmentId(entryName.toString()));
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan attachment directory " + root, e);
        }
        return Set.copyOf(found);
    }

    /** Moves an orphaned file into {@code <attachments>/quarantine/<id>} without permanent deletion. */
    public void quarantine(AttachmentId id) {
        Objects.requireNonNull(id, "id");
        Path source = pathOf(id);
        Path quarantineDir = roots.attachments().resolve(QUARANTINE_FOLDER);
        Path destination = quarantineDir.resolve(id.value());
        try {
            if (!Files.exists(source)) {
                return;
            }
            storages.createFolder(quarantineDir);
            storages.requireFolder(quarantineDir);
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        } catch (NoSuchFileException ignored) {
            // Already removed or quarantined.
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to quarantine orphan attachment " + id.value(), e);
        }
    }
}
