package io.github.jaeseongs95.portal.ssh.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
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

    /** Tries to make {@code link} a symbolic link to {@code target}; false where the file store or the account may not (a Windows user token), which {@link #requireOrSkip} turns into a skip or a failure. */
    public static boolean canLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
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

    /**
     * Lets other accounts read and write the folder or the file: group and others bits on a posix store, an ALLOW entry for {@link #outsider} on an acl store. On a folder of an
     * acl store the entry is inheritable (FILE_INHERIT, DIRECTORY_INHERIT), so what is made in the folder by a plain call inherits it, which is how a volume that hands Users or
     * Authenticated Users modify rights to every folder behaves (case S-D0117-06); {@link #assertTheOpenEntryIsInherited} checks that this condition is real.
     */
    public static void openUp(Path path) throws IOException {
        FileStore store = Files.getFileStore(path);
        if (store.supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(Files.isDirectory(path) ? "rwxrwxrwx" : "rw-rw-rw-"));
        } else {
            AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
            List<AclEntry> acl = new ArrayList<>(view.getAcl());
            AclEntry.Builder entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(outsider(path))
                .setPermissions(AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA, AclEntryPermission.READ_DATA, AclEntryPermission.READ_ATTRIBUTES,
                    AclEntryPermission.READ_ACL, AclEntryPermission.SYNCHRONIZE);
            if (Files.isDirectory(path)) {
                entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
            }
            acl.add(0, entry.build());
            view.setAcl(acl);
        }
    }

    /**
     * The precondition of case S-D0117-06 on an acl store: a file made in the opened-up folder by a plain call inherits the entry of {@link #outsider}. Without it a test that
     * asserts "the created path has no entry for others" would pass for an implementation that only adds owner entries to the inherited list. A posix store has nothing to inherit.
     */
    public static void assertTheOpenEntryIsInherited(Path openFolder) throws IOException {
        if (!Files.getFileStore(openFolder).supportsFileAttributeView("acl")) {
            return;
        }
        UserPrincipal outsider = outsider(openFolder);
        Path plain = Files.createFile(openFolder.resolve("made-by-a-plain-call-" + System.nanoTime()));
        try {
            assertThat(Files.getFileAttributeView(plain, AclFileAttributeView.class).getAcl()).as("the plain file in %s inherits the entry for others", openFolder)
                .anyMatch(entry -> entry.principal().equals(outsider));
        } finally {
            Files.delete(plain);
        }
    }

    /**
     * Fails the test when the access control list of the path has an entry for {@link #outsider} (the group that the opened-up parent granted), whether the entry was made for
     * the path or inherited from its parent: an implementation that keeps the inherited list and only adds the owner's entries fails here. A posix store is checked by its bits.
     */
    public static void assertNoEntryForOthers(Path path) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("acl")) {
            UserPrincipal outsider = outsider(path);
            assertThat(Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).getAcl()).as("the list of %s", path)
                .noneMatch(entry -> entry.principal().equals(outsider));
        } else {
            assertOwnerOnly(path);
        }
    }

    /**
     * Makes the existing folder one in which nothing can be made private: its list gets an entry that denies the owner-rights group (the well-known S-1-3-4, the implicit rights
     * of the owner) the right to change the list and the owner, and that entry is INHERIT_ONLY, so it applies to what is made in the folder and not to the folder itself. The
     * second entry is full control for the owner, which includes deleting children. The folder itself is therefore never locked: the account that made it can change its list,
     * undo the denial ({@link #undoTheDenial}) or delete the folder and everything below it without undoing anything ({@link #deleteTree}), which is what keeps a test that dies
     * before its cleanup from leaving a folder that nobody can delete. Acl stores only.
     */
    public static void forbidChangingTheListOfWhatIsMadeIn(Path folder) throws IOException {
        UserPrincipal ownerRights = folder.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("OWNER RIGHTS");
        AclFileAttributeView view = Files.getFileAttributeView(folder, AclFileAttributeView.class);
        List<AclEntry> acl = List.of(
            AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(ownerRights).setPermissions(AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER)
                .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.INHERIT_ONLY).build(),
            AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(view.getOwner()).setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT).build());
        view.setAcl(acl);
    }

    /**
     * Like {@link #forbidChangingTheListOfWhatIsMadeIn} and, in addition, nothing made in the folder can be deleted by the owner: the full control of the owner leaves out DELETE
     * and DELETE_CHILD, so a call that made something and has to remove it again fails to remove it. The folder itself can still be changed by its owner:
     * {@link #undoTheDenial} gives the full control back (DELETE_CHILD included), after which {@link #deleteTree} deletes everything. Acl stores only.
     */
    public static void forbidDeletingWhatIsMadeIn(Path folder) throws IOException {
        UserPrincipal ownerRights = folder.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("OWNER RIGHTS");
        AclFileAttributeView view = Files.getFileAttributeView(folder, AclFileAttributeView.class);
        Set<AclEntryPermission> withoutDelete = EnumSet.complementOf(EnumSet.of(AclEntryPermission.DELETE, AclEntryPermission.DELETE_CHILD));
        List<AclEntry> acl = List.of(
            AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(ownerRights).setPermissions(AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER)
                .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.INHERIT_ONLY).build(),
            AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(view.getOwner()).setPermissions(withoutDelete)
                .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT).build());
        view.setAcl(acl);
    }

    /** Takes the denial of {@link #forbidChangingTheListOfWhatIsMadeIn} away again: the list of the folder is full control for the owner, inheritable. What was made in it before keeps what it inherited. */
    public static void undoTheDenial(Path folder) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(folder, AclFileAttributeView.class);
        view.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(view.getOwner()).setPermissions(EnumSet.allOf(AclEntryPermission.class))
            .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT).build()));
    }

    /** Deletes the folder and everything below it, following no link, and fails the test when something stays: the proof that the account can delete what it made. */
    public static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path folder, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(folder);
                return FileVisitResult.CONTINUE;
            }
        });
        assertThat(Files.exists(root, LinkOption.NOFOLLOW_LINKS)).as("%s is deleted", root).isFalse();
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
                // Written to the standard output of the test (it is in the JUnit XML of the run), so that a CI log shows which system file stood in for a key of another owner.
                System.out.println("S-D0117-02: the file of another owner is " + found.get() + " (owner " + Files.getOwner(found.get()).getName() + ")");
                return found.get();
            }
        }
        throw new IllegalStateException("No readable non-empty text file owned by another account was found near the top of any root of the file system");
    }

    private static boolean onStoreOf(Path entry, FileStore store) {
        try {
            return Files.getFileStore(entry).equals(store);
        } catch (IOException e) {
            return false; // an entry whose file store cannot be read is not a candidate
        }
    }

    private static Optional<Path> foreignFileNear(Path root, UserPrincipal me) {
        FileStore rootStore;
        try {
            rootStore = Files.getFileStore(root);
        } catch (IOException e) {
            return Optional.empty(); // a root that cannot be read (an empty drive) has no such file
        }
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
                if (!onStoreOf(entry, rootStore)) {
                    continue; // a virtual file system (proc, sys, a mounted share) is not a place for a file that stands in for a key
                }
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
