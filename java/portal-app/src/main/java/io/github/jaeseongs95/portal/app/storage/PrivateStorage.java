package io.github.jaeseongs95.portal.app.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileStore;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.Set;

/**
 * The rule that creates a folder or a file so that only its owner can use it, on one kind of file store. A file store offers one of two ways to say who may touch a file,
 * and the rule is chosen by what the file store <em>offers</em> ({@link FileStore#supportsFileAttributeView(String)}), never by the name of the operating system:
 * {@link PosixPrivateStorage} for a store with the {@code posix} view (permission bits), {@link AclPrivateStorage} for a store with the {@code acl} view (an NTFS volume).
 * A store with neither is refused by {@link PrivateStorages}.
 *
 * <p>The rule is about <em>creating</em>. Reading or using what already exists does not look at who may use it, who owns it or whether it is reached by a link: the
 * owner of the installation trusts the machine (decision D-0117). Both {@code portal-store} and {@code portal-ssh} share this single implementation (SSH_PORTAL-D-0128).
 */
public interface PrivateStorage {

    /** True when this rule can make a path private on the file store. */
    boolean supports(FileStore store);

    /**
     * Creates the folder, with its missing parents, so that only its owner can use it. A folder that exists is left as it is. When making a folder that this call created
     * private fails, the folders this call created are removed again and {@link UnsafeStorageException} is thrown.
     */
    void createFolder(Path folder) throws IOException;

    /**
     * Opens a file like {@link FileChannel#open(Path, Set, java.nio.file.attribute.FileAttribute[])}. A file is created only with {@code CREATE_NEW} ({@code CREATE} without it
     * is refused with an {@link IllegalArgumentException}), so that the file system decides whether this call created the file.
     */
    FileChannel open(Path file, Set<? extends OpenOption> options) throws IOException;
}
