package io.github.jaeseongs95.portal.ssh.profile;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
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
 * {@link PrivateFileRule} for a file store with the {@code posix} view: a new folder is {@code rwx------} (its missing parents too, each one is created with the attribute) and
 * a new file {@code rw-------}. The process umask can only narrow these modes. What exists is not changed and not judged: 2.x refused a file or a folder that the group or
 * others could use with the mask {@code 0o077} (PF-04, vendor config/loader.js:67-74), and 3.0 does not (D-0117).
 *
 * <p><b>The result is read back, for what this call created only.</b> The JDK answers that a file store has the {@code posix} view whatever is mounted, and a mount that ignores
 * modes creates the path with wide rights. So right after it creates a folder or a file, the rule reads the permissions of that path, and when the group or others have any
 * access it removes what this call created and fails with an {@link IOException}: nothing is left behind with wide rights. A path that existed before the call is neither read
 * nor changed.
 *
 * <p>Based on the Java SE API: {@code PosixFilePermissions}
 * (https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/attribute/PosixFilePermissions.html).
 */
@Component
@Order(1)
public final class PosixPrivateFileRule implements PrivateFileRule {

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
    public PosixPrivateFileRule() {
        this(path -> Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS));
    }

    /** A rule whose read-back is the given reader: a test stands in for a file store that ignores modes with it (case S-D0117-07). */
    PosixPrivateFileRule(PermissionReader reader) {
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
            } catch (FileAlreadyExistsException e) {
                if (Files.isDirectory(path)) {
                    continue; // somebody made it between the check and the call: it is not ours, and it is not judged
                }
                throw e;
            }
            created.add(path);
            requireOwnerOnly(path, created.reversed());
        }
    }

    @Override
    public void createFile(Path file) throws IOException {
        if (Files.exists(file)) {
            return;
        }
        Files.createFile(file, FILE_MODE);
        requireOwnerOnly(file, List.of(file));
    }

    /** Reads back what this call has just created; when the group or others have access, or the permissions cannot be read, removes {@code removeOnFailure} and fails. */
    private void requireOwnerOnly(Path created, List<Path> removeOnFailure) throws IOException {
        IOException refusal;
        try {
            Set<PosixFilePermission> permissions = reader.permissionsOf(created);
            if (Collections.disjoint(permissions, GROUP_AND_OTHERS)) {
                return;
            }
            refusal = new IOException("The file store ignored the mode of the new path " + created + ": its permissions are " + PosixFilePermissions.toString(permissions)
                + " and it cannot be made private to its owner");
        } catch (IOException | RuntimeException e) {
            refusal = new IOException("The permissions of the new path " + created + " could not be read back, so it cannot be shown to be private to its owner", e);
        }
        for (Path path : removeOnFailure) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException cleanup) {
                refusal.addSuppressed(cleanup);
            }
        }
        throw refusal;
    }
}
