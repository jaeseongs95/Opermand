package io.github.jaeseongs95.portal.store.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Picks the {@link PrivateStorage} rule that the file store of a path supports and applies it; the strategy beans are injected as a {@code List}, so a rule for another
 * kind of file store is a new bean and no change here. The rules are tried in their {@code @Order} (permission bits first). A file store that no rule supports is
 * refused with {@link UnsafeStorageException}: a path is never created with wide rights because the store cannot narrow them.
 *
 * <p>This class also holds what is the same for every rule and for every store that uses it, and it is the whole of what is checked when a path is used: it must be a
 * regular file, or a folder. A link is followed, and who may use the path or who owns it is not asked (decision D-0117: the owner trusts the machine; what is protected is
 * what the product creates).
 */
@Component
public class PrivateStorages {

    private final List<PrivateStorage> rules;

    public PrivateStorages(List<PrivateStorage> rules) {
        this.rules = List.copyOf(rules);
    }

    /** Creates the folder private to its owner; an existing folder is left as it is. */
    public void createFolder(Path folder) throws IOException {
        ruleFor(folder).createFolder(folder);
    }

    /** Opens the file; a file that this call creates is private to its owner. */
    public FileChannel open(Path file, Set<? extends OpenOption> options) throws IOException {
        return ruleFor(file).open(file, options);
    }

    /** The path must be a folder (or a link to one). Its rights and its owner are not looked at. */
    public void requireFolder(Path folder) {
        if (!Files.isDirectory(folder)) {
            throw new UnsafeStorageException(folder.toString(), "not a folder");
        }
    }

    /** The path must be a regular file (or a link to one); a folder, a device or a link whose target is missing is refused. Its rights and its owner are not looked at. */
    public void requireRegularFile(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new UnsafeStorageException(file.toString(), "not a regular file");
        }
    }

    /** The rule of the file store that holds the path, or of the nearest folder above it that exists. */
    public PrivateStorage ruleFor(Path path) throws IOException {
        FileStore store = Files.getFileStore(nearestExisting(path));
        for (PrivateStorage rule : rules) {
            if (rule.supports(store)) {
                return rule;
            }
        }
        throw new UnsafeStorageException(path.toString(), "the file store " + store + " supports neither permission bits (posix) nor an access control list (acl), so a path "
            + "cannot be made private to its owner");
    }

    private Path nearestExisting(Path path) {
        Path existing = path.toAbsolutePath();
        while (existing.getParent() != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        return existing;
    }
}
