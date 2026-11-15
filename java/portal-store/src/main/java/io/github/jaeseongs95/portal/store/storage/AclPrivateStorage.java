package io.github.jaeseongs95.portal.store.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
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
 *
 * <p><b>Making a path private.</b> The access control list of a new folder or file is replaced by two entries: full control for its owner and for the system account
 * ({@link AclFileAttributeView#setAcl}). The entries of a folder are inheritable, so the files that are made in it start private too; every file is also made private
 * explicitly right after it is created. What the volume's parent folder grants (typically {@code Users} or {@code Authenticated Users}, which a volume other than the system
 * volume gives by default) is therefore not inherited by a path that this rule creates. The ACL of a new file is set after it is created, because the owner (the account the
 * process runs as) is known only from the created file; it has no bytes yet at that moment. A path that cannot be made private is removed again and the refusal is
 * {@link UnsafeStorageException}: nothing is left behind with the rights of the parent.
 *
 * <p><b>What exists is not judged.</b> The ACL of an existing folder or file is neither read nor changed: an entry for another principal, another owner or a link is not a
 * reason to refuse it (decision D-0117).
 *
 * <p>Based on the Java SE API: {@code AclFileAttributeView}, {@code AclEntry} and {@code UserPrincipalLookupService}
 * (https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/attribute/AclFileAttributeView.html,
 * https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/attribute/AclEntry.html,
 * https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/attribute/UserPrincipalLookupService.html).
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
            Files.createDirectory(path);
            created.add(path);
            try {
                protect(path, true);
            } catch (IOException | RuntimeException e) {
                removeAll(created.reversed(), e);
                throw refusal(path, e);
            }
        }
    }

    @Override
    public FileChannel open(Path file, Set<? extends OpenOption> options) throws IOException {
        boolean existed = Files.exists(file, LinkOption.NOFOLLOW_LINKS);
        FileChannel channel = FileChannel.open(file, options);
        if (existed) {
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
            removeAll(List.of(file), e);
            throw refusal(file, e);
        }
        return channel;
    }

    private UnsafeStorageException refusal(Path path, Exception cause) {
        return cause instanceof UnsafeStorageException unsafe ? unsafe : new UnsafeStorageException(path.toString(), "it could not be made private to its owner", cause);
    }

    private void removeAll(List<Path> paths, Exception failure) {
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
        }
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

    /** The principal of the first of the names that the machine knows; a name that it does not know is the normal case on a system in another language. */
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
