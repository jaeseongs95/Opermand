package io.github.jaeseongs95.portal.store.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.portal.store.TestStorage;

/**
 * S-D0117-06 and S-D0117-01, what the rule makes on a file store with an access control list (an NTFS volume), and that it leaves what exists as it is. The tests change real
 * ACLs of real folders and files, no mock: what Windows does with inherited entries, with the account names and with the entries that are added afterwards is what is
 * asserted. Java SE API: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/attribute/AclFileAttributeView.html
 */
class AclPrivateStorageTest {

    private final AclPrivateStorage rule = new AclPrivateStorage();

    @TempDir
    Path directory;

    @BeforeEach
    void onlyWhereTheFileStoreHasAnAcl() throws IOException {
        TestStorage.requireOrSkip("acl", rule.supports(Files.getFileStore(directory)),
            "the file store of the test folder has no acl view (it is a posix store); a run on a machine with ACLs states -Pportal.test.require=acl and fails instead of skipping");
    }

    private AclFileAttributeView view(Path path) {
        return Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    }

    private Set<UserPrincipal> allowedPrincipals(Path path) throws IOException {
        Set<UserPrincipal> allowed = new HashSet<>();
        for (AclEntry entry : view(path).getAcl()) {
            if (entry.type() == AclEntryType.ALLOW) {
                allowed.add(entry.principal());
            }
        }
        return allowed;
    }

    /** The principals that may be in the list of a private path: its owner and the system account. */
    private Set<UserPrincipal> ownerAndSystem(Path path) throws IOException {
        Set<UserPrincipal> expected = new HashSet<>();
        expected.add(view(path).getOwner());
        for (String name : List.of("NT AUTHORITY\\SYSTEM", "SYSTEM")) {
            try {
                expected.add(path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(name));
                break;
            } catch (IOException e) {
                // The account is known under another name on this machine; the next name is tried.
                continue;
            }
        }
        return expected;
    }

    private void write(Path file, String text) throws IOException {
        try (FileChannel channel = rule.open(file, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            channel.write(ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    @DisplayName("S-D0117-06, RC-A-178: a folder made by the rule is private: its list allows the owner and the system account and nobody else")
    void aNewFolderIsOwnerOnly() throws IOException {
        Path folder = directory.resolve("private");

        rule.createFolder(folder);

        assertThat(allowedPrincipals(folder)).isNotEmpty().isSubsetOf(ownerAndSystem(folder));
        TestStorage.assertOwnerOnly(folder);
        assertThat(allowedPrincipals(folder)).doesNotContain(TestStorage.outsider(folder));
    }

    @Test
    @DisplayName("S-D0117-06: the missing parents of a folder are private too, even below a folder whose rights let others in")
    void newParentsAreOwnerOnlyBelowAnOpenFolder() throws IOException {
        Path open = Files.createDirectory(directory.resolve("open"));
        TestStorage.openUp(open);
        Path folder = open.resolve("outer").resolve("inner");

        rule.createFolder(folder);

        TestStorage.assertOwnerOnly(folder);
        TestStorage.assertOwnerOnly(folder.getParent());
        assertThat(allowedPrincipals(folder.getParent())).doesNotContain(TestStorage.outsider(folder));
    }

    @Test
    @DisplayName("S-D0117-01: a folder that exists is left as it is by createFolder, whoever may use it")
    void anExistingFolderIsNotChanged() throws IOException {
        Path existing = Files.createDirectory(directory.resolve("existing"));
        TestStorage.openUp(existing);
        List<AclEntry> before = view(existing).getAcl();

        rule.createFolder(existing);

        assertThat(view(existing).getAcl()).isEqualTo(before);
    }

    @Test
    @DisplayName("S-D0117-06, RC-A-178: a file made by the rule is private even in a folder whose rights let others in; its bytes are written and read by the owner")
    void aNewFileIsOwnerOnlyEvenWhereTheFolderIsOpen() throws IOException {
        Path open = Files.createDirectory(directory.resolve("open"));
        TestStorage.openUp(open);
        Path file = open.resolve("payload.part");

        write(file, "payload");

        assertThat(allowedPrincipals(file)).isNotEmpty().isSubsetOf(ownerAndSystem(file));
        TestStorage.assertOwnerOnly(file);
        assertThat(Files.readAllBytes(file)).isEqualTo("payload".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("S-D0117-06: what is made in a folder of the rule inherits a private list (the inherited entries are the owner's)")
    void whatIsMadeInAPrivateFolderInheritsItsList() throws IOException {
        Path folder = directory.resolve("inherits");
        rule.createFolder(folder);

        Path child = Files.createFile(folder.resolve("made-by-a-plain-call"));
        Path subfolder = Files.createDirectory(folder.resolve("sub"));

        assertThat(allowedPrincipals(child)).isSubsetOf(ownerAndSystem(child));
        assertThat(allowedPrincipals(subfolder)).isSubsetOf(ownerAndSystem(subfolder));
        TestStorage.assertOwnerOnly(child);
        TestStorage.assertOwnerOnly(subfolder);
    }

    /** A folder in which nothing can be made private, and that the account can still change and delete (see {@link TestStorage#forbidChangingTheListOfWhatIsMadeIn}). */
    private Path folderThatForbidsChangingTheList() throws IOException {
        Path parent = Files.createDirectory(directory.resolve("no-write-dac"));
        TestStorage.forbidChangingTheListOfWhatIsMadeIn(parent);
        return parent;
    }

    @Test
    @DisplayName("S-D0117-07: when the list of a new folder cannot be set, every folder that the call created is removed and the answer is UnsafeStorageException (a real denial of the right to change the list); the denial is not undone and the account still deletes the folder it made")
    void aFolderWhoseListCannotBeSetIsRemoved() throws IOException {
        Path parent = folderThatForbidsChangingTheList();
        try {
            Path inner = parent.resolve("outer").resolve("inner");

            assertThatThrownBy(() -> rule.createFolder(inner)).isInstanceOf(UnsafeStorageException.class).hasMessageContaining("could not be made private");

            assertThat(Files.exists(parent.resolve("outer"), LinkOption.NOFOLLOW_LINKS)).isFalse();
        } finally {
            TestStorage.deleteTree(parent); // without undoing the denial: what a test that dies before its cleanup has to be able to rely on
        }
    }

    @Test
    @DisplayName("S-D0117-07: when the list of a new file cannot be set, the file is closed and removed and the answer is UnsafeStorageException (a real denial of the right to change the list)")
    void aFileWhoseListCannotBeSetIsRemoved() throws IOException {
        Path parent = folderThatForbidsChangingTheList();
        try {
            Path file = parent.resolve("payload.part");

            assertThatThrownBy(() -> rule.open(file, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))).isInstanceOf(UnsafeStorageException.class);

            assertThat(Files.exists(file, LinkOption.NOFOLLOW_LINKS)).isFalse();
        } finally {
            TestStorage.deleteTree(parent);
        }
    }

    @Test
    @DisplayName("S-D0117-07: the folder that forbids changing the list is never locked itself: with the denial still in place, the account deletes it with what was made below it (the proof for a test that dies before its cleanup)")
    void theAccountDeletesAFolderThatStillHasTheDenial() throws IOException {
        Path parent = folderThatForbidsChangingTheList();
        Path child = Files.createDirectory(parent.resolve("outer"));
        Files.writeString(Files.createDirectory(child.resolve("inner")).resolve("payload.part"), "bytes");
        assertThatThrownBy(() -> TestStorage.undoTheDenial(child)).as("what is made below the folder has the denial").isInstanceOf(IOException.class);

        TestStorage.deleteTree(parent);

        assertThat(Files.exists(parent, LinkOption.NOFOLLOW_LINKS)).isFalse();
    }

    @Test
    @DisplayName("S-D0117-07: when the denial is undone the same call makes the folder private, and the folder and what is below it are deleted afterwards (the control of the denial tests)")
    void theCallWorksWhenTheDenialIsUndone() throws IOException {
        Path parent = folderThatForbidsChangingTheList();
        TestStorage.undoTheDenial(parent);
        try {
            Path inner = parent.resolve("outer").resolve("inner");

            rule.createFolder(inner);

            TestStorage.assertOwnerOnly(inner);
        } finally {
            TestStorage.deleteTree(parent);
        }
    }

    @Test
    @DisplayName("S-D0117-01: an entry for Everyone (or Users) on an existing file is left as it is and the file is opened, written and read")
    void anExistingFileThatOthersCanUseIsOpenedAsItIs() throws IOException {
        Path folder = directory.resolve("folder");
        rule.createFolder(folder);
        Path file = folder.resolve("payload.part");
        write(file, "bytes");
        TestStorage.openUp(file);
        List<AclEntry> before = view(file).getAcl();

        try (FileChannel channel = rule.open(file, Set.of(StandardOpenOption.WRITE, StandardOpenOption.APPEND))) {
            channel.write(ByteBuffer.wrap("+more".getBytes(StandardCharsets.UTF_8)));
        }

        assertThat(view(file).getAcl()).isEqualTo(before);
        assertThat(Files.readString(file)).isEqualTo("bytes+more");
    }
}
