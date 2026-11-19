package io.github.jaeseongs95.portal.app.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * {@link PrivateStorage} for a file store with the {@code acl} view (an NTFS volume), where there are no permission bits.
 * Replaces the ACL of newly created folders/files with full control for the owner and the system account; existing paths are neither read nor changed (decision D-0117).
 */
@Component
@Order(2)
public final class AclPrivateStorage implements PrivateStorage {

    private static final Logger LOG = LoggerFactory.getLogger(AclPrivateStorage.class);
    private static final String ACL_VIEW = "acl";
    private static final List<String> SYSTEM_NAMES = List.of("NT AUTHORITY\\SYSTEM", "SYSTEM");

    @Override
    public boolean supports(FileStore store) {
        return store.supportsFileAttributeView(ACL_VIEW);
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
                Files.createDirectory(path);
            } catch (FileAlreadyExistsException | AccessDeniedException notCreated) {
                if (Files.isDirectory(path)) {
                    continue;
                }
                throw notCreated;
            }
            created.add(path);
            try {
                protect(path, true);
            } catch (IOException | RuntimeException e) {
                throw refusal(path, e, removeAll(created.reversed(), e));
            }
        }
    }

    @Override
    public FileChannel open(Path file, Set<? extends OpenOption> options) throws IOException {
        if (options.contains(StandardOpenOption.CREATE)) {
            throw new IllegalArgumentException("A file is created with CREATE_NEW, so that the file system decides whether this call created it: " + options);
        }
        boolean created = options.contains(StandardOpenOption.CREATE_NEW);
        FileChannel channel = FileChannel.open(file, options);
        if (!created) {
            return channel;
        }
        try {
            protect(file, false);
        } catch (IOException | RuntimeException e) {
            try {
                channel.close();
            } catch (IOException closing) {
                e.addSuppressed(closing);
            }
            throw refusal(file, e, removeAll(List.of(file), e));
        }
        return channel;
    }

    private UnsafeStorageException refusal(Path path, Exception cause, List<Path> left) {
        String reason = "it could not be made private to its owner";
        if (!left.isEmpty()) {
            reason += "; " + left + " could not be removed again and is left with the rights it inherited (a later run uses an existing folder as it is, it does not look at its rights)";
        }
        return new UnsafeStorageException(path.toString(), reason, cause);
    }

    private List<Path> removeAll(List<Path> paths, Exception failure) {
        List<Path> left = new ArrayList<>();
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
                left.add(path);
            }
        }
        return left;
    }

    private void protect(Path path, boolean folder) throws IOException {
        AclFileAttributeView view = view(path);
        List<AclEntry> acl = new ArrayList<>();
        acl.add(fullControl(view.getOwner(), folder));
        firstKnown(path.getFileSystem(), SYSTEM_NAMES).ifPresent(system -> acl.add(fullControl(system, folder)));
        view.setAcl(acl);
    }

    private AclEntry fullControl(UserPrincipal principal, boolean inheritable) {
        AclEntry.Builder entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(principal).setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (inheritable) {
            entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        }
        return entry.build();
    }

    private AclFileAttributeView view(Path path) {
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new UnsafeStorageException(path.toString(), "the file store has no access control list view");
        }
        return view;
    }

    private Optional<UserPrincipal> firstKnown(FileSystem system, List<String> names) {
        for (String name : names) {
            try {
                return Optional.of(system.getUserPrincipalLookupService().lookupPrincipalByName(name));
            } catch (UserPrincipalNotFoundException e) {
                LOG.debug("The account {} is not known to this machine", name, e);
            } catch (IOException e) {
                LOG.warn("The account {} could not be looked up", name, e);
            }
        }
        return Optional.empty();
    }
}
