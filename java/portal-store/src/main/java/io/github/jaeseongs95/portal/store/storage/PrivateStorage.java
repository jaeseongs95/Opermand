package io.github.jaeseongs95.portal.store.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileStore;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.Set;

/**
 * The rule that creates a folder or a file so that only its owner can use it, on one kind of file store. A file store offers one of two ways to say who may touch a file,
 * and the rule is chosen by what the file store <em>offers</em> ({@link FileStore#supportsFileAttributeView(String)}), never by the name of the operating system
 * (Principles 1.1): {@link PosixPrivateStorage} for a store with the {@code posix} view (permission bits), {@link AclPrivateStorage} for a store with the {@code acl}
 * view (an NTFS volume). A store with neither is refused by {@link PrivateStorages}. A path that a rule creates is never left with wide rights: each rule makes the path
 * private and, where the store can answer that the rights were not kept (a permission-bit store that ignores modes), reads the rights back right after the creation and
 * removes what it created and refuses when they are wide. The read-back is of the new path only, never of one that existed.
 *
 * <p>The rule is about <em>creating</em>. Reading or using what already exists does not look at who may use it, who owns it or whether it is reached by a link: the
 * owner of the installation trusts the machine (decision D-0117, the one trust boundary of this product). What is refused is a path that is not a regular file or a
 * folder, which {@link PrivateStorages} checks.
 *
 * <p>The three stores that write sensitive bytes (artifacts, command outputs, the audit file) use the same rule through {@link PrivateStorages}; none of them holds a
 * copy of it.
 */
public interface PrivateStorage {

    /** True when this rule can make a path private on the file store. */
    boolean supports(FileStore store);

    /**
     * Creates the folder, with its missing parents, so that only its owner can use it. A folder that exists is left as it is. When making a folder that this call created
     * private fails (the rights cannot be set, or they are not what was asked), the folders this call created are removed again and {@link UnsafeStorageException} is thrown:
     * nothing is left behind with wide rights. A failure to create a folder at all is an {@link IOException}.
     */
    void createFolder(Path folder) throws IOException;

    /**
     * Opens a file like {@link FileChannel#open(Path, Set, java.nio.file.attribute.FileAttribute[])}; a file that this call creates is private to its owner (when it
     * cannot be made private, or its rights are not what was asked, it is removed again and {@link UnsafeStorageException} is thrown). A file that exists is opened as it is, a link is followed.
     */
    FileChannel open(Path file, Set<? extends OpenOption> options) throws IOException;
}
