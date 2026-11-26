package io.github.jaeseongs95.portal.store.artifact;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.portal.app.artifact.ArtifactPayloadStore;
import io.github.jaeseongs95.portal.app.artifact.ChunkDigest;
import io.github.jaeseongs95.portal.app.storage.DiskSpace;
import io.github.jaeseongs95.portal.app.storage.PayloadRead;
import io.github.jaeseongs95.portal.app.storage.PayloadWrite;
import io.github.jaeseongs95.portal.app.storage.PrivateStorages;
import io.github.jaeseongs95.portal.app.storage.UnsafeStorageException;
import io.github.jaeseongs95.portal.domain.error.ArtifactErrorCode;
import io.github.jaeseongs95.portal.domain.error.RequestRefusedException;
import io.github.jaeseongs95.portal.domain.id.ArtifactId;
import io.github.jaeseongs95.portal.store.StorageRoots;
import io.github.jaeseongs95.portal.store.attachment.AttachmentKeyring;
import io.github.jaeseongs95.portal.store.attachment.ChunkedAeadCodec;
import io.github.jaeseongs95.portal.store.attachment.CorruptedEncryptedPayloadException;

/**
 * {@link ArtifactPayloadStore} over one folder, {@code StorageRoots#artifacts()}: the bytes of an artifact are stored encrypted at rest as {@code <id>.part} using
 * {@link ChunkedAeadCodec} ({@code PAE1}, 16 KiB fixed chunks with AES-256-GCM). Every method does file I/O, so it is called outside a transaction (Principles 4.4).
 *
 * <p>What is created is private to its owner ({@link PrivateStorages}); what exists is used as it is (decision D-0117). An append acquires a file lock, verifies the
 * logical plaintext offset, re-encrypts any trailing partial chunk with a fresh random IV, and calls {@code FileChannel#force(true)} before returning.
 */
@Component
public class FileArtifactPayloadStore implements ArtifactPayloadStore, RetainedPayloads {

    private static final String SUFFIX = ".part";
    private static final Pattern STORED_NAME = Pattern.compile("[a-f0-9]{48}\\.part");
    private static final String QUARANTINE_FOLDER = "orphans";

    private final Path root;
    private final PrivateStorages storages;
    private final ChunkedAeadCodec codec;

    @Autowired
    public FileArtifactPayloadStore(StorageRoots roots, PrivateStorages storages, ChunkedAeadCodec codec) {
        this.root = Objects.requireNonNull(roots, "roots").artifacts();
        this.storages = Objects.requireNonNull(storages, "storages");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    /** Convenience constructor for unit tests without Spring: derives a keyring under {@code roots.keyring()}. */
    public FileArtifactPayloadStore(StorageRoots roots, PrivateStorages storages) {
        this(roots, storages, defaultCodec(roots, storages));
    }

    private static ChunkedAeadCodec defaultCodec(StorageRoots roots, PrivateStorages storages) {
        SecureRandom random = new SecureRandom();
        AttachmentKeyring keyring = new AttachmentKeyring(roots.keyring().resolve(AttachmentKeyring.DEFAULT_FILE_NAME), storages, random, Clock.systemUTC());
        return new ChunkedAeadCodec(keyring, random);
    }

    @Override
    public PayloadWrite append(ArtifactId id, long offset, byte[] data) {
        if (data.length < 1 || data.length > ChunkDigest.MAX_CHUNK_BYTES) {
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_INVALID_CHUNK);
        }
        if (offset < 0) {
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_SEQUENCE_OR_SIZE);
        }
        Path folder = preparedRoot();
        Path file = fileOf(folder, id);
        try {
            boolean exists = Files.exists(file, LinkOption.NOFOLLOW_LINKS);
            if (exists) {
                storages.requireRegularFile(file);
            } else if (offset != 0) {
                // A file is created by its first chunk only; creating it for a later one would leave an empty file behind a refusal.
                throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_CHUNK_CONFLICT);
            }
            try (FileChannel channel = openForWrite(file, !exists); FileLock lock = channel.tryLock()) {
                if (lock == null) {
                    throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_BUSY);
                }
                long currentPlain = codec.plaintextLength(channel, ChunkedAeadCodec.Scope.ARTIFACT, id.value());
                if (currentPlain != offset) {
                    throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_CHUNK_CONFLICT);
                }
                long updatedPlain = codec.append(channel, ChunkedAeadCodec.Scope.ARTIFACT, id.value(), offset, data);
                return new PayloadWrite(updatedPlain);
            }
        } catch (OverlappingFileLockException e) {
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_BUSY, e);
        } catch (FileAlreadyExistsException e) {
            // Two first chunks of the same artifact arrive together: both saw no file, one created it, and this one lost the creation (defect D-0139).
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_BUSY, e);
        } catch (UnsafeStorageException e) {
            throw unsafe(e);
        } catch (CorruptedEncryptedPayloadException e) {
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_STORAGE_IO_ERROR, e);
        } catch (IOException e) {
            throw ioFailure(e);
        }
    }

    @Override
    public void truncate(ArtifactId id, long length) {
        if (length < 0) {
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_SEQUENCE_OR_SIZE);
        }
        Path file = existingFile(id);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE); FileLock lock = channel.tryLock()) {
            if (lock == null) {
                throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_BUSY);
            }
            long currentPlain = codec.plaintextLength(channel, ChunkedAeadCodec.Scope.ARTIFACT, id.value());
            if (length > currentPlain) {
                throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_SEQUENCE_OR_SIZE);
            }
            codec.truncate(channel, ChunkedAeadCodec.Scope.ARTIFACT, id.value(), length);
        } catch (OverlappingFileLockException e) {
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_BUSY, e);
        } catch (CorruptedEncryptedPayloadException e) {
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_STORAGE_IO_ERROR, e);
        } catch (IOException e) {
            throw ioFailure(e);
        }
    }

    @Override
    public long length(ArtifactId id) {
        Path file = existingFile(id);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            return codec.plaintextLength(channel, ChunkedAeadCodec.Scope.ARTIFACT, id.value());
        } catch (CorruptedEncryptedPayloadException e) {
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_STORAGE_IO_ERROR, e);
        } catch (IOException e) {
            throw ioFailure(e);
        }
    }

    @Override
    public PayloadRead open(ArtifactId id) {
        Path file = existingFile(id);
        try {
            FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
            ChunkedAeadCodec.DecryptedStream stream = codec.openDecryptingChannel(channel, ChunkedAeadCodec.Scope.ARTIFACT, id.value());
            return new PayloadRead(stream.channel(), stream.plaintextLength());
        } catch (CorruptedEncryptedPayloadException e) {
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_STORAGE_IO_ERROR, e);
        } catch (IOException e) {
            throw ioFailure(e);
        }
    }

    /** Deletes the file; a file that is not there is already deleted. A symbolic link is removed as a link, its target is not touched; one name of a hard-linked file is removed. */
    @Override
    public void delete(ArtifactId id) {
        Path file = fileOf(preparedRoot(), id);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw ioFailure(e);
        }
    }

    /**
     * Moves the file to {@code orphans/} next to the payloads and keeps every byte; nothing in the store deletes that folder's files. Never uses {@code ATOMIC_MOVE}
     * (defect D-0150); instead retries on {@code FileAlreadyExistsException} with the next free suffix.
     */
    @Override
    public void quarantine(ArtifactId id) {
        Path folder = preparedRoot();
        Path file = fileOf(folder, id);
        try {
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_NOT_FOUND);
            }
            Path quarantine = folder.resolve(QUARANTINE_FOLDER);
            storages.createFolder(quarantine);
            for (int attempt = 0; ; attempt++) {
                Path candidate = attempt == 0 ? quarantine.resolve(id.value() + SUFFIX) : quarantine.resolve(id.value() + "." + attempt + SUFFIX);
                try {
                    Files.move(file, candidate);
                    return;
                } catch (FileAlreadyExistsException ignored) {
                    // Another file already holds this quarantine name; try the next suffix without overwriting.
                }
            }
        } catch (UnsafeStorageException e) {
            throw unsafe(e);
        } catch (IOException e) {
            throw ioFailure(e);
        }
    }

    @Override
    public Set<ArtifactId> stored() {
        Path folder = preparedRoot();
        Set<ArtifactId> found = new HashSet<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder, "*" + SUFFIX)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (STORED_NAME.matcher(name).matches()) {
                    storages.requireRegularFile(entry);
                    found.add(new ArtifactId(name.substring(0, name.length() - SUFFIX.length())));
                }
            }
        } catch (UnsafeStorageException e) {
            throw unsafe(e);
        } catch (IOException e) {
            throw ioFailure(e);
        }
        return Set.copyOf(found);
    }

    /** The number and the logical sizes of the regular files in {@code orphans/}; a link is not followed and not counted, a missing folder is zero. */
    @Override
    public Held held() {
        Path quarantine = root.resolve(QUARANTINE_FOLDER);
        if (!Files.isDirectory(quarantine, LinkOption.NOFOLLOW_LINKS)) {
            return new Held(0L, 0L);
        }
        long files = 0L;
        long total = 0L;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(quarantine, "*" + SUFFIX)) {
            for (Path entry : entries) {
                BasicFileAttributes attributes = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isRegularFile()) {
                    files++;
                    String name = entry.getFileName().toString();
                    String idPart = name.length() >= 48 ? name.substring(0, 48) : name;
                    total += codec.plaintextLengthOrRawSize(entry, ChunkedAeadCodec.Scope.ARTIFACT, idPart);
                }
            }
        } catch (IOException e) {
            throw ioFailure(e);
        }
        return new Held(files, total);
    }

    @Override
    public DiskSpace usable() {
        Path folder = preparedRoot();
        try {
            return new DiskSpace(Files.getFileStore(folder).getUsableSpace());
        } catch (IOException e) {
            throw ioFailure(e);
        }
    }

    /** Raw size on disk of an existing payload file; used by startup corruption classification when a file is shorter than a valid PAE1 header. */
    long rawFileSize(ArtifactId id) {
        Path file = existingFile(id);
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw ioFailure(e);
        }
    }

    private Path preparedRoot() {
        try {
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                storages.createFolder(root);
            }
            storages.requireFolder(root);
            return root;
        } catch (UnsafeStorageException e) {
            throw unsafe(e);
        } catch (IOException e) {
            throw ioFailure(e);
        }
    }

    private Path existingFile(ArtifactId id) {
        Path file = fileOf(preparedRoot(), id);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_NOT_FOUND);
        }
        try {
            storages.requireRegularFile(file);
        } catch (UnsafeStorageException e) {
            throw unsafe(e);
        }
        return file;
    }

    private Path fileOf(Path folder, ArtifactId id) {
        return folder.resolve(id.value() + SUFFIX);
    }

    private FileChannel openForWrite(Path file, boolean create) throws IOException {
        Set<OpenOption> options = create
            ? Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE)
            : Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE);
        return storages.open(file, options);
    }

    private RequestRefusedException unsafe(UnsafeStorageException cause) {
        return new RequestRefusedException(ArtifactErrorCode.ARTIFACT_STORAGE_UNSAFE, cause);
    }

    private RequestRefusedException ioFailure(IOException cause) {
        return new RequestRefusedException(ArtifactErrorCode.ARTIFACT_STORAGE_IO_ERROR, cause);
    }
}
