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
 * view (an NTFS volume). A store with neither is refused by {@link PrivateStorages}. A rule makes a path that it creates private and, where the store can answer
 * that the rights were not kept (a permission-bit store that ignores modes), reads the rights back right after the creation; when the path cannot be made private it
 * removes what it created and refuses. The read-back is of the new path only, never of one that existed. Removing can fail too (a right to delete is missing): the path then
 * stays with the rights it has, the message of the exception names it, and a later run uses an existing folder as it is, without a check of its rights (decision D-0117).
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
     * private fails (the rights cannot be set, or they are not what was asked), the folders this call created are removed again and {@link UnsafeStorageException} is thrown;
     * a folder that cannot be removed stays and is named in the message. A failure to create a folder at all (the disk is full, a name is refused) is an {@link IOException}
     * and is handled differently: the folders that this call made before it and made private stay, because they are owner only and nothing about them is wrong; only a failure to
     * make a folder private removes the folders of the call.
     */
    void createFolder(Path folder) throws IOException;

    /**
     * Opens a file like {@link FileChannel#open(Path, Set, java.nio.file.attribute.FileAttribute[])}. A file is created only with {@code CREATE_NEW} ({@code CREATE} without it
     * is refused with an {@link IllegalArgumentException}), so that the file system, not a check made before the call, decides whether this call created the file: a caller
     * that may meet an existing file opens with {@code CREATE_NEW} first and, when that throws {@link java.nio.file.FileAlreadyExistsException}, opens the existing file without
     * it. A file that this call creates is private to its owner (when it
     * cannot be made private, or its rights are not what was asked, it is removed again, or named in the message when it cannot be removed, and {@link UnsafeStorageException} is thrown). A file that exists is opened as it is, a link is followed.
     */
    FileChannel open(Path file, Set<? extends OpenOption> options) throws IOException;
}
