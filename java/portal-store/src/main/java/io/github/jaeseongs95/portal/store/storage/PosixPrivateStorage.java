package io.github.jaeseongs95.portal.store.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Set;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * {@link PrivateStorage} for a file store with the {@code posix} view: a folder that this rule creates is {@code rwx------} (its missing parents too, each one is created with
 * the attribute), a file {@code rw-------} (2.x {@code privateDirectory}/{@code privateFile}, {@code artifacts.mjs:11-14}). The process umask can only narrow these modes.
 *
 * <p><b>The result is read back, for what this call created only.</b> The JDK answers that a file store has the {@code posix} view whatever is mounted: a mount that ignores
 * modes (a volume of another file system type, a share without permission checks) accepts the attribute and creates the path with wide rights. So right after it creates a
 * folder or a file, the rule reads the permissions of that path, and when the group or others have any access it removes what this call created and throws
 * {@link UnsafeStorageException} (the common design, version 11). When a path cannot even be removed again (a right to delete is missing) it stays with its wide rights and
 * the message names it; the next run uses an existing folder as it is and does not look at its rights, so a path left behind this way is used without a check. A path that
 * existed before the call is neither read nor changed: who may use it is not asked (decision D-0117), and this is a check of the creation, not of the reading.
 *
 * <p>Based on the Java SE API: {@code java.nio.file.attribute.PosixFilePermissions}
 * (https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/attribute/PosixFilePermissions.html).
 */
@Component
@Order(1)
public final class PosixPrivateStorage implements PrivateStorage {

    private static final String POSIX_VIEW = "posix";
    private static final Set<PosixFilePermission> GROUP_AND_OTHERS = Set.of(PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE);
    private static final FileAttribute<Set<PosixFilePermission>> FOLDER_MODE = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
    private static final FileAttribute<Set<PosixFilePermission>> FILE_MODE = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

    /** How the rule reads the permissions of the path it has just created. */
    @FunctionalInterface
    interface PermissionReader {

        Set<PosixFilePermission> permissionsOf(Path path) throws IOException;
    }

    private final PermissionReader reader;

    /** The rule of the product: the permissions are what the file store reports. */
    public PosixPrivateStorage() {
        this(path -> Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS));
    }

    /**
     * A rule whose read-back is the given reader. A test uses it to stand in for a file store that ignores modes (what the store reports for a new path is then a value of
     * the test, see case S-D0117-07).
     */
    PosixPrivateStorage(PermissionReader reader) {
        this.reader = reader;
    }

    @Override
    public boolean supports(FileStore store) {
        return store.supportsFileAttributeView(POSIX_VIEW);
    }

    @Override
    public void createFolder(Path folder) throws IOException {
        Deque<Path> missing = new ArrayDeque<>();
        for (Path path = folder.toAbsolutePath(); path != null && !Files.exists(path, LinkOption.NOFOLLOW_LINKS); path = path.getParent()) {
            missing.push(path);
        }
        List<Path> created = new ArrayList<>();
        for (Path path : missing) {
            try {
                Files.createDirectory(path, FOLDER_MODE);
            } catch (FileAlreadyExistsException | AccessDeniedException notCreated) { // FileAlreadyExistsException, and possibly an access denial for a path that appeared in between
                if (Files.isDirectory(path)) {
                    continue; // somebody made it between the check and the call: it is not ours, and it is not judged
                }
                throw notCreated;
            }
            created.add(path);
            requireOwnerOnly(path, created.reversed());
        }
    }

    @Override
    public FileChannel open(Path file, Set<? extends OpenOption> options) throws IOException {
        if (options.contains(StandardOpenOption.CREATE)) {
            throw new IllegalArgumentException("A file is created with CREATE_NEW, so that the file system decides whether this call created it: " + options);
        }
        boolean created = options.contains(StandardOpenOption.CREATE_NEW);
        FileChannel channel = FileChannel.open(file, options, FILE_MODE);
        if (!created) {
            return channel;
        }
        try {
            requireOwnerOnly(file, List.of(file));
        } catch (UnsafeStorageException e) {
            try {
                channel.close();
            } catch (IOException closing) {
                e.addSuppressed(closing);
            }
            throw e;
        }
        return channel;
    }

    /** Reads back what this call has just created; when the group or others have access, or the permissions cannot be read, removes {@code removeOnFailure} and refuses. */
    private void requireOwnerOnly(Path created, List<Path> removeOnFailure) {
        String reason;
        Exception cause = null;
        try {
            Set<PosixFilePermission> permissions = reader.permissionsOf(created);
            if (Collections.disjoint(permissions, GROUP_AND_OTHERS)) {
                return;
            }
            reason = "the file store ignored the mode of the new path: its permissions are " + PosixFilePermissions.toString(permissions) + " and it cannot be made private to its owner";
        } catch (IOException | RuntimeException e) {
            reason = "the permissions of the new path could not be read back, so it cannot be shown to be private to its owner";
            cause = e;
        }
        List<Path> left = new ArrayList<>();
        List<IOException> failures = new ArrayList<>();
        for (Path path : removeOnFailure) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException cleanup) {
                failures.add(cleanup);
                left.add(path);
            }
        }
        if (!left.isEmpty()) {
            reason += "; " + left + " could not be removed again and is left with its wide rights (a later run uses an existing folder as it is, it does not look at its rights)";
        }
        UnsafeStorageException refusal = new UnsafeStorageException(created.toString(), reason, cause);
        failures.forEach(refusal::addSuppressed);
        throw refusal;
    }
}
