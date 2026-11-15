package io.github.jaeseongs95.portal.store.storage;

import static org.assertj.core.api.Assertions.assertThat;

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

import org.junit.jupiter.api.Assumptions;
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
        Assumptions.assumeTrue(rule.supports(Files.getFileStore(directory)),
            "the file store of the test folder has no acl view (it is a posix store); runs on the Windows CI (Windows CI에서 실행)");
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
