package io.github.jaeseongs95.portal.migrate.settings;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Creates a file that only its owner can use, with a way that works on both kinds of file system: permission bits ({@code rw-------}) where the file store offers the
 * {@code posix} view, an access control list with one entry (full control for the owner) where it offers the {@code acl} view (NTFS). The way is chosen by what the file store
 * offers, never by the name of the operating system. A file store that offers neither refuses the file: secrets are never written with wide rights.
 *
 * <p>The content is written to a file of its own in the same folder, which is made private before the first byte is written and moved to its name afterwards, so that the
 * name never shows a half-written file and the move never replaces a file that is there ({@link java.nio.file.FileAlreadyExistsException}).
 *
 * <p>ceiling: this is the same rule as {@code portal-app}'s {@code PrivateStorages}, which this module may not use (rule A-DIR: migrate needs only store and domain); it is
 * short because it only creates one file. When the rule moves to a module that migrate may use, this class goes.
 */
public final class OwnerOnlyFile {

    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    /**
     * Creates the file with the content, private to its owner.
     *
     * @throws java.nio.file.FileAlreadyExistsException when something is at the path (it is left as it is)
     * @throws IOException when the file cannot be made private or written; nothing is left at the path
     */
    public void createNew(Path file, byte[] content) throws IOException {
        Path folder = file.toAbsolutePath().getParent();
        Files.createDirectories(folder);
        Path staging = Files.createTempFile(folder, ".portal-migrate-", ".tmp");
        try {
            restrict(staging);
            try (FileChannel channel = FileChannel.open(staging, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer rest = ByteBuffer.wrap(content);
                while (rest.hasRemaining()) {
                    channel.write(rest);
                }
                channel.force(true);
            }
            Files.move(staging, file);
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    private void restrict(Path path) throws IOException {
        FileStore store = Files.getFileStore(path);
        if (store.supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, OWNER_ONLY);
            if (!OWNER_ONLY.containsAll(Files.getPosixFilePermissions(path))) {
                throw new IOException("the file store did not keep the owner only permissions of a new file");
            }
        } else if (store.supportsFileAttributeView("acl")) {
            AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
            UserPrincipal owner = view.getOwner();
            // The list is replaced, not added to: what the folder would hand down is not kept (the tests read the list back).
            view.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
        } else {
            throw new IOException("the file store " + store + " supports neither permission bits nor an access control list, so a file private to its owner cannot be made there");
        }
    }
}
