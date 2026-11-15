package io.github.jaeseongs95.portal.ssh.profile;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * {@link PrivateFileRule} for a file store with the {@code acl} view (an NTFS volume), where there are no permission bits. No program is run (2.x PF-05 called
 * {@code icacls.exe}).
 *
 * <p><b>Making a path private.</b> The list of a new folder or file is replaced by full control for the owner and for the system account; the entries of a folder are
 * inheritable. What the parent grants (typically {@code Users} or {@code Authenticated Users}) is not inherited by a path that this rule creates. A path that cannot be made
 * private is removed again and the failure is an {@link IOException}: nothing is left behind with the rights of the parent.
 *
 * <p><b>What exists is not judged.</b> The list of an existing folder or file is neither read nor changed: 2.x refused a path that another account could change and warned
 * about one that another account could read (PF-05), and 3.0 does neither (D-0117).
 *
 * <p>Based on the Java SE API: {@code AclFileAttributeView}, {@code AclEntry}
 * (https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/attribute/AclFileAttributeView.html).
 */
@Component
@Order(2)
public final class AclPrivateFileRule implements PrivateFileRule {

    private static final Logger LOG = LoggerFactory.getLogger(AclPrivateFileRule.class);
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
            protectOrRemove(path, true, created.reversed());
        }
    }

    @Override
    public void createFile(Path file) throws IOException {
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.createFile(file);
        protectOrRemove(file, false, List.of(file));
    }

    private void protectOrRemove(Path path, boolean folder, List<Path> removeOnFailure) throws IOException {
        try {
            protect(path, folder);
        } catch (IOException | RuntimeException e) {
            for (Path created : removeOnFailure) {
                try {
                    Files.deleteIfExists(created);
                } catch (IOException cleanup) {
                    e.addSuppressed(cleanup);
                }
            }
            throw new IOException("The path " + path + " could not be made private to its owner", e);
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

    private AclFileAttributeView view(Path path) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new IOException("The file store of " + path + " has no access control list view");
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
