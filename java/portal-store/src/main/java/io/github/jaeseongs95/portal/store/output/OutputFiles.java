package io.github.jaeseongs95.portal.store.output;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.portal.app.output.OutputId;
import io.github.jaeseongs95.portal.domain.error.ExecutionErrorCode;
import io.github.jaeseongs95.portal.domain.error.RequestRefusedException;
import io.github.jaeseongs95.portal.store.StorageRoots;
import io.github.jaeseongs95.portal.store.storage.PrivateStorages;
import io.github.jaeseongs95.portal.store.storage.UnsafeStorageException;

/**
 * The files of the output folder (design 8.5): where the three payload files of an output are, and how they are created and opened. The folder and the files that this class
 * creates are private to their owner on every kind of file store: how that is made belongs to {@link PrivateStorages}, which picks the rule by what the file store offers
 * (permission bits, or an access control list on an NTFS volume) and refuses a file store with neither. What exists is used as it is (decision D-0117): who else may use it,
 * who owns it and whether it is reached by a link are not asked; a file that is not a regular file and a folder that is not a folder are refused. This class holds no copy
 * of the rule. A refusing or failing file system is a {@link RequestRefusedException} with {@link ExecutionErrorCode#OUTPUT_CAPTURE_FAILED} (the output has no code of its
 * own for an unusable store yet) and the cause attached.
 */
@Component
public class OutputFiles {

    /** The payload files of one output: the spool of raw frames, the temporary file of {@code finish}, the finished renderings. */
    public enum PayloadKind {
        STREAM(".stream"), PART(".part"), OUT(".out");

        private final String suffix;

        PayloadKind(String suffix) {
            this.suffix = suffix;
        }

        String suffix() {
            return suffix;
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(OutputFiles.class);
    private static final Pattern STORED_NAME = Pattern.compile("([a-f0-9]{48})\\.(?:out|part|stream)");
    private static final String QUARANTINE_FOLDER = "quarantine";

    private final Path root;
    private final PrivateStorages storages;

    public OutputFiles(StorageRoots roots, PrivateStorages storages) {
        this.root = Objects.requireNonNull(roots, "roots").outputs().toAbsolutePath();
        this.storages = Objects.requireNonNull(storages, "storages");
    }

    /** The output folder, created private to its owner when it does not exist yet; a path that is not a folder is refused. A folder that exists is used as it is. */
    public Path directory() {
        try {
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                storages.createFolder(root);
            }
            storages.requireFolder(root);
            return root;
        } catch (UnsafeStorageException e) {
            throw unsafe(root, e);
        } catch (IOException e) {
            throw failure(e);
        }
    }

    public Path path(OutputId id, PayloadKind kind) {
        return directory().resolve(id.value() + kind.suffix());
    }

    /** True when the name exists, whether it is a file or a link (a link is not followed). */
    public boolean exists(Path file) {
        return Files.exists(file, LinkOption.NOFOLLOW_LINKS);
    }

    /** The free bytes of the file store of the output folder. */
    public long usableBytes() {
        try {
            return Files.getFileStore(directory()).getUsableSpace();
        } catch (IOException e) {
            throw failure(e);
        }
    }

    /** Refuses a file that is not a regular file (a folder, a device, a link whose target is missing). A link to a regular file is followed; who may use the file is not asked. */
    public void requireRegular(Path file) {
        try {
            storages.requireRegularFile(file);
        } catch (UnsafeStorageException e) {
            throw unsafe(file, e);
        }
    }

    /** Opens the file for appending (created owner only when it is new); an existing file must be a regular file. */
    public FileChannel openAppend(Path file) {
        return open(file, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND));
    }

    /** Opens the file for writing from the start, dropping what an earlier crash left in it. */
    public FileChannel openFresh(Path file) {
        return open(file, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
    }

    /** Opens a regular file for reading. */
    public FileChannel openRead(Path file) {
        requireRegular(file);
        try {
            return FileChannel.open(file, StandardOpenOption.READ);
        } catch (IOException e) {
            throw failure(e);
        }
    }

    /** The size of a regular file. */
    public long sizeOf(Path file) {
        requireRegular(file);
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw failure(e);
        }
    }

    /** Renames {@code part} to {@code target} in one step and forces the folder entry to disk where the file store allows it. */
    public void moveIntoPlace(Path part, Path target) {
        try {
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw failure(e);
        }
        syncDirectory();
    }

    /**
     * Forces the folder entries to disk. Java has no portable directory fsync: a folder can be opened and forced only where the file store has the posix view, so
     * elsewhere the atomic rename alone is what the file system offers.
     */
    public void syncDirectory() {
        try {
            if (supportsPosix(directory())) {
                try (FileChannel folder = FileChannel.open(directory(), StandardOpenOption.READ)) {
                    folder.force(true);
                }
            }
        } catch (IOException e) {
            throw failure(e);
        }
    }

    /** Deletes the file or the link (the link's target is not touched); a missing file is not an error. */
    public void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw failure(e);
        }
    }

    /** Moves the file into the quarantine folder inside the output folder, under a name that is not taken yet. */
    public void quarantine(Path file) {
        try {
            Path folder = directory().resolve(QUARANTINE_FOLDER);
            storages.createFolder(folder);
            Path target = folder.resolve(file.getFileName().toString());
            for (int copy = 1; Files.exists(target, LinkOption.NOFOLLOW_LINKS); copy++) {
                target = folder.resolve(file.getFileName().toString() + "." + copy);
            }
            Files.move(file, target);
            LOG.warn("Moved the payload {} of an output without a manifest to the quarantine folder", file.getFileName());
        } catch (UnsafeStorageException e) {
            throw unsafe(file, e);
        } catch (IOException e) {
            throw failure(e);
        }
    }

    /** The ids of the outputs that have at least one payload file in the folder. */
    public Set<OutputId> storedIds() {
        Set<OutputId> ids = new HashSet<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory())) {
            for (Path entry : entries) {
                Matcher name = STORED_NAME.matcher(entry.getFileName().toString());
                if (name.matches()) {
                    ids.add(new OutputId(name.group(1)));
                }
            }
        } catch (IOException e) {
            throw failure(e);
        }
        return ids;
    }

    private FileChannel open(Path file, Set<? extends OpenOption> options) {
        try {
            if (exists(file)) {
                requireRegular(file);
            }
            directory();
            return storages.open(file, options);
        } catch (UnsafeStorageException e) {
            throw unsafe(file, e);
        } catch (IOException e) {
            throw failure(e);
        }
    }

    /** Only the fsync of the folder depends on the posix view: Java can open and force a folder there and nowhere else. This is not a protection rule. */
    private boolean supportsPosix(Path path) throws IOException {
        return Files.getFileStore(path).supportsFileAttributeView("posix");
    }

    private RequestRefusedException unsafe(Path file, UnsafeStorageException cause) {
        LOG.warn("The storage {} cannot be used: {}", file.getFileName(), cause.getMessage());
        return new RequestRefusedException(ExecutionErrorCode.OUTPUT_CAPTURE_FAILED, cause);
    }

    private RequestRefusedException failure(IOException cause) {
        return new RequestRefusedException(ExecutionErrorCode.OUTPUT_CAPTURE_FAILED, cause);
    }
}
