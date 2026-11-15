package io.github.jaeseongs95.portal.ssh.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;

/**
 * What the file tests of the profile and host key packages need to stand in for the product's wiring: the {@link PrivateFiles} with both rules, a way to open a path to
 * other accounts (the product does not refuse what exists, decision D-0117, so the tests make wide paths on purpose), the check that a path that the product created is
 * private to its owner (a test tool: the product does not look at who may use an existing path), and a file that another account owns. Both file store kinds are served by
 * the same calls, so a test runs on a volume with permission bits and on an NTFS volume without a condition (the choice is made by what the file store offers, as in the
 * product).
 */
public final class TestFiles {

    private static final Set<PosixFilePermission> GROUP_AND_OTHERS = Set.of(PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE);
    private static final Set<AclEntryPermission> DATA_ACCESS = EnumSet.of(AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA,
        AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER);

    private TestFiles() {
    }

    /**
     * A test that needs an ability of the file system (case S-D0117-09): {@code posix} (permission bits), {@code links} (symbolic links) or {@code acl}. Without the property
     * {@code portal.test.require} naming the ability, a machine that lacks it skips the test with the message; with the property the same test fails, so a run that states what
     * its machine offers (the Linux CI step: {@code -Pportal.test.require=posix,links}) can never pass by skipping. The property is the Gradle one, handed to the test JVM by
     * the build.
     */
    public static void requireOrSkip(String ability, boolean available, String skipMessage) {
        String required = System.getProperty("portal.test.require", "");
        if (List.of(required.split(",")).contains(ability)) {
            assertThat(available).as("this run requires the ability '%s' of the file system, and the machine lacks it (%s)", ability, skipMessage).isTrue();
        } else {
            Assumptions.assumeTrue(available, skipMessage);
        }
    }

    /** The creator with both rules, in the order of the product. */
    public static PrivateFiles privateFiles() {
        return new PrivateFiles(List.of(new PosixPrivateFileRule(), new AclPrivateFileRule()));
    }

    /**
     * A principal that the product must not let in, looked up by the names of the well-known groups that every Windows has. Fails the test when the machine knows none
     * of them, so no check passes by not running.
     */
    public static UserPrincipal outsider(Path anyPath) throws IOException {
        UserPrincipalLookupService lookup = anyPath.getFileSystem().getUserPrincipalLookupService();
        IllegalStateException none = new IllegalStateException("The machine knows none of the well-known groups Everyone, Users, Authenticated Users");
        for (String name : List.of("Everyone", "BUILTIN\\Users", "Users", "Authenticated Users")) {
            try {
                return lookup.lookupPrincipalByName(name);
            } catch (UserPrincipalNotFoundException e) {
                none.addSuppressed(e); // the next well-known name; when none is known the failure carries every miss
            }
        }
        throw none;
    }

    /** Lets other accounts read and write the folder or the file: group and others bits on a posix store, an ALLOW entry for {@link #outsider} on an acl store. */
    public static void openUp(Path path) throws IOException {
        FileStore store = Files.getFileStore(path);
        if (store.supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(Files.isDirectory(path) ? "rwxrwxrwx" : "rw-rw-rw-"));
        } else {
            AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
            List<AclEntry> acl = new ArrayList<>(view.getAcl());
            acl.add(0, AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(outsider(path))
                .setPermissions(AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA, AclEntryPermission.READ_DATA, AclEntryPermission.READ_ATTRIBUTES,
                    AclEntryPermission.READ_ACL, AclEntryPermission.SYNCHRONIZE)
                .build());
            view.setAcl(acl);
        }
    }

    /**
     * Makes the existing folder one whose inheritable entry denies the owner the right to change the access control list (the well-known owner-rights group, S-1-3-4), so that nothing
     * made in it can be made private. Acl stores only.
     */
    public static void forbidChangingTheListOfWhatIsMadeIn(Path folder) throws IOException {
        UserPrincipal ownerRights = folder.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("OWNER RIGHTS");
        AclFileAttributeView view = Files.getFileAttributeView(folder, AclFileAttributeView.class);
        // The list is exactly two entries, so that what is made in the folder inherits the same two on every machine: the denial, and full control for the owner (which keeps the
        // owner able to delete what a failed call must remove again; the owner-rights entry takes away only the implicit rights of the owner, not the entries of the list).
        List<AclEntry> acl = List.of(
            AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(ownerRights).setPermissions(AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER)
                .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT).build(),
            AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(view.getOwner()).setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT).build());
        view.setAcl(acl);
    }

    /**
     * Fails the test when somebody besides the owner (and, on an acl store, the system account and the Administrators group) may use the path: group or others bits on a
     * posix store, an ALLOW entry that gives data access or the right to change the list or the owner to another principal on an acl store.
     */
    public static void assertOwnerOnly(Path path) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)).as("the permissions of %s", path).doesNotContainAnyElementsOf(GROUP_AND_OTHERS);
            return;
        }
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        assertThat(view).as("the acl view of %s", path).isNotNull();
        Set<UserPrincipal> allowed = new HashSet<>();
        allowed.add(view.getOwner());
        for (String name : List.of("NT AUTHORITY\\SYSTEM", "SYSTEM", "BUILTIN\\Administrators", "Administrators")) {
            try {
                allowed.add(path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(name));
            } catch (UserPrincipalNotFoundException e) {
                continue; // the account is known under another name on this machine; the other names are tried
            }
        }
        for (AclEntry entry : view.getAcl()) {
            if (entry.type() == AclEntryType.ALLOW && entry.permissions().stream().anyMatch(DATA_ACCESS::contains)) {
                assertThat(allowed).as("the entry %s of %s", entry, path).contains(entry.principal());
            }
        }
    }

    /**
     * A readable, non-empty UTF-8 text file (under 100 kB) that the account of the test does not own, found near the top of the roots of the file system of the machine (a
     * system file: owned by root on Linux, by the system on Windows). Fails the test when none is found, so the test never passes by not running.
     */
    public static Path foreignOwnedFile() throws IOException {
        Path probe = Files.createTempFile("portal-owner-probe", ".tmp");
        UserPrincipal me;
        try {
            me = Files.getOwner(probe);
        } finally {
            Files.delete(probe);
        }
        for (Path root : FileSystems.getDefault().getRootDirectories()) {
            Optional<Path> found = foreignFileNear(root, me);
            if (found.isPresent()) {
                return found.get();
            }
        }
        throw new IllegalStateException("No readable non-empty text file owned by another account was found near the top of any root of the file system");
    }

    private static Optional<Path> foreignFileNear(Path root, UserPrincipal me) {
        Deque<Path> folders = new ArrayDeque<>(List.of(root));
        for (int visited = 0; !folders.isEmpty() && visited < 400; visited++) {
            Path folder = folders.poll();
            List<Path> entries;
            try (Stream<Path> listed = Files.list(folder)) {
                entries = listed.toList();
            } catch (IOException e) {
                continue; // a folder that the account may not list is not where the file is; the next folder is tried
            }
            for (Path entry : entries) {
                if (isForeignReadableFile(entry, me)) {
                    return Optional.of(entry);
                }
                if (folder.getNameCount() < 2 && Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    folders.add(entry);
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isForeignReadableFile(Path entry, UserPrincipal me) {
        try {
            return Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(entry) && Files.size(entry) > 0 && Files.size(entry) < 100_000
                && !me.equals(Files.getOwner(entry)) && !Files.readString(entry, StandardCharsets.UTF_8).isEmpty();
        } catch (IOException e) {
            return false; // an entry whose size, owner or text cannot be read is not a candidate
        }
    }
}
