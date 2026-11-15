package io.github.jaeseongs95.portal.ssh.profile;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Path;

/**
 * The rule that creates a folder or a file so that only its owner can use it, on one kind of file store. A file store offers one of two ways to say who may use a path, and
 * the rule is chosen by what the store <em>offers</em> ({@link FileStore#supportsFileAttributeView(String)}), never by the name of the operating system:
 * {@link PosixPrivateFileRule} for permission bits, {@link AclPrivateFileRule} for an access control list (an NTFS volume). The product runs no program to set an ACL
 * ({@code icacls} of 2.x is not used).
 *
 * <p>The rule is about <em>creating</em>. What exists is used as it is: who else may use it, who owns it and whether it is reached by a link are not asked (decision D-0117,
 * the owner of the installation trusts the machine).
 *
 * <p>The product keeps its own copy of this rule next to the one of the store module, because an SSH adapter does not use the store module (module table, rule A-DIR).
 */
public interface PrivateFileRule {

    /** True when this rule can make a path private on the file store. */
    boolean supports(FileStore store);

    /**
     * Creates the folder, with its missing parents, so that only its owner can use it. A folder that exists is left as it is. A folder that cannot be made private is
     * removed again, with the folders that this call made before it, and the failure is an {@link IOException}. A failure to create a folder at all (the disk is full, a name is
     * refused) is an {@link IOException} too, but it is handled differently: the folders that this call made before it and made private stay, because they are owner only and
     * nothing about them is wrong.
     */
    void createFolder(Path folder) throws IOException;

    /**
     * Creates an empty file that only its owner can use. A file that exists is left as it is. A file that cannot be made private is removed again and the failure is an
     * {@link IOException}.
     */
    void createFile(Path file) throws IOException;
}
