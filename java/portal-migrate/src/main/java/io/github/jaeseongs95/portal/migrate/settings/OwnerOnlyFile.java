package io.github.jaeseongs95.portal.migrate.settings;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Creates a file that only its owner can use, with a way that works on both kinds of file system: permission bits ({@code rw-------} for files, {@code rwx------} for newly
 * created parent folders) where the file store offers the {@code posix} view, an access control list with one entry (full control for the owner) where it offers the
 * {@code acl} view (NTFS). The way is chosen by what the file store offers, never by the name of the operating system. A file store that offers neither refuses the file:
 * secrets are never written with wide rights.
 *
 * <p>The content is written to a fresh {@code CREATE_NEW} staging file in the same folder, restricted before the first byte is written, {@code force(true)}d, and moved into
 * place without {@code ATOMIC_MOVE} (decision D-0150) and without overwriting an existing path ({@link FileAlreadyExistsException}, checked with
 * {@link LinkOption#NOFOLLOW_LINKS}). Any newly created staging file or empty parent folders are cleaned up on failure.
 */
public final class OwnerOnlyFile {

    private static final Set<PosixFilePermission> OWNER_FILE = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> OWNER_DIR = PosixFilePermissions.fromString("rwx------");

    private final SecureRandom random = new SecureRandom();

    /**
     * Creates the file with the content, private to its owner.
     *
     * @throws FileAlreadyExistsException when something is already at the path (including a broken symbolic link; it is left as it is)
     * @throws IOException when the file cannot be made private or written; nothing newly created is left behind
     */
    public void createNew(Path file, byte[] content) throws IOException {
        Path absolute = file.toAbsolutePath();
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(absolute.toString());
        }
        Path folder = absolute.getParent();
        FileStore store = fileStoreOf(folder);
        boolean posix = store.supportsFileAttributeView("posix");
        boolean acl = !posix && store.supportsFileAttributeView("acl");
        if (!posix && !acl) {
            throw new IOException("the file store " + store + " supports neither permission bits nor an access control list, so a file private to its owner cannot be made there");
        }
        List<Path> createdFolders = createPrivateFolders(folder, posix);
        Path staging = null;
        boolean moved = false;
        try {
            staging = freshStagingPath(folder);
            try (FileChannel channel = openPrivateNew(staging, posix)) {
                ByteBuffer rest = ByteBuffer.wrap(content);
                while (rest.hasRemaining()) {
                    channel.write(rest);
                }
                channel.force(true);
            }
            if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException(absolute.toString());
            }
            // No ATOMIC_MOVE (D-0150) and no REPLACE_EXISTING so an existing target is never overwritten.
            Files.move(staging, absolute);
            moved = true;
        } finally {
            if (!moved) {
                if (staging != null) {
                    try {
                        Files.deleteIfExists(staging);
                    } catch (IOException ignored) {
                        // Best-effort cleanup of staging file.
                    }
                }
                for (int i = createdFolders.size() - 1; i >= 0; i--) {
                    try {
                        Files.deleteIfExists(createdFolders.get(i));
                    } catch (IOException ignored) {
                        // Best-effort cleanup of newly created empty folders.
                    }
                }
            }
        }
    }

    private FileChannel openPrivateNew(Path staging, boolean posix) throws IOException {
        if (posix) {
            FileChannel channel = FileChannel.open(staging, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(OWNER_FILE));
            if (!OWNER_FILE.containsAll(Files.getPosixFilePermissions(staging, LinkOption.NOFOLLOW_LINKS))) {
                channel.close();
                throw new IOException("the file store did not keep the owner only permissions of a new file");
            }
            return channel;
        }
        FileChannel channel = FileChannel.open(staging, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            restrictAcl(staging);
            return channel;
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    private List<Path> createPrivateFolders(Path folder, boolean posix) throws IOException {
        if (folder == null || Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        Deque<Path> missing = new ArrayDeque<>();
        Path cursor = folder;
        while (cursor != null && !Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
            missing.addFirst(cursor);
            cursor = cursor.getParent();
        }
        List<Path> created = new ArrayList<>();
        try {
            for (Path dir : missing) {
                if (posix) {
                    Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(OWNER_DIR));
                    if (!OWNER_DIR.containsAll(Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS))) {
                        created.add(dir);
                        throw new IOException("the file store did not keep the owner only permissions of a new folder");
                    }
                } else {
                    Files.createDirectory(dir);
                    restrictAcl(dir);
                }
                created.add(dir);
            }
            return created;
        } catch (IOException | RuntimeException e) {
            for (int i = created.size() - 1; i >= 0; i--) {
                try {
                    Files.deleteIfExists(created.get(i));
                } catch (IOException ignored) {
                    // Best-effort rollback of newly created folders.
                }
            }
            throw e;
        }
    }

    private void restrictAcl(Path path) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        UserPrincipal owner = view.getOwner();
        // The list is replaced, not added to: what the folder would hand down is not kept (the tests read the list back).
        view.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
    }

    private Path freshStagingPath(Path folder) {
        byte[] suffix = new byte[8];
        random.nextBytes(suffix);
        return folder.resolve(".portal-migrate-" + HexFormat.of().formatHex(suffix) + ".tmp");
    }

    private FileStore fileStoreOf(Path folder) throws IOException {
        Path existing = folder == null ? Path.of(".").toAbsolutePath() : folder;
        while (existing.getParent() != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        return Files.getFileStore(existing);
    }
}
