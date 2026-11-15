package io.github.jaeseongs95.portal.ssh.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the product creates for itself (the file of known hosts and its folder) is private to its owner, and what exists is left as it is (decision D-0117; 2.x PF-04 and PF-05
 * refused a profile file that others could use, and 3.0 does not). The tests go through the file system of the machine they run on, whichever kind it is: the rule is chosen
 * by what the file store offers, so a volume with permission bits and an NTFS volume with an access control list are served by the same calls and no test is skipped.
 */
class PrivateFileRuleTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("S-D0117-06: a file made by the creator is empty and private to its owner, and so are the folders that it had to make, even below a folder that others may use")
    void newPathsAreOwnerOnly() throws IOException {
        Path open = Files.createDirectory(directory.resolve("open"));
        TestFiles.openUp(open);
        Path file = open.resolve("outer").resolve("inner").resolve("known_hosts");

        TestFiles.privateFiles().createPrivateFile(file);

        assertThat(file).exists().isEmptyFile();
        TestFiles.assertOwnerOnly(file);
        TestFiles.assertOwnerOnly(file.getParent());
        TestFiles.assertOwnerOnly(file.getParent().getParent());
    }

    @Test
    @DisplayName("S-D0117-01: a file and a folder that exist are left as they are, with their bytes, even when other accounts may use them")
    void existingPathsAreLeftAlone() throws IOException {
        Path folder = Files.createDirectory(directory.resolve("shared"));
        Path file = Files.writeString(folder.resolve("known_hosts"), "kept\n");
        TestFiles.openUp(file);
        TestFiles.openUp(folder);

        TestFiles.privateFiles().createPrivateFile(file);

        assertThat(Files.readString(file)).isEqualTo("kept\n");
    }

    @Test
    @DisplayName("S-D0117-07: a file store that no rule supports is refused with an IOException that says why, and nothing is created")
    void aFileStoreWithoutARuleIsRefused() {
        Path file = directory.resolve("new").resolve("known_hosts");

        assertThatThrownBy(() -> new PrivateFiles(List.of()).createPrivateFile(file)).isInstanceOf(IOException.class).hasMessageContaining("neither permission bits");
        assertThat(file.getParent()).doesNotExist();
    }

    @Test
    @DisplayName("S-D0117-07: a file store that ignores the mode of what is created: the new folders and the new file are removed and the answer is an IOException (the read-back is the value of the test, see the case)")
    void newPathsWithWideRightsAreRemoved() throws IOException {
        TestFiles.requireOrSkip("posix", Files.getFileStore(directory).supportsFileAttributeView("posix"),
            "the file store of the test folder has no posix view (it is an NTFS volume); the Linux CI requires it (-Pportal.test.require=posix) and fails instead of skipping");
        PrivateFiles ignoringModes = new PrivateFiles(List.of(new PosixPrivateFileRule(path -> PosixFilePermissions.fromString("rwxr-xr-x"))));
        Path file = directory.resolve("outer").resolve("known_hosts");

        assertThatThrownBy(() -> ignoringModes.createPrivateFile(file)).isInstanceOf(IOException.class).hasMessageContaining("ignored the mode");

        assertThat(directory.resolve("outer")).doesNotExist();

        Path folder = Files.createDirectory(directory.resolve("existing"));
        Path wideFile = directory.resolve("existing").resolve("known_hosts");
        PosixPrivateFileRule onlyTheFileIsWide = new PosixPrivateFileRule(path -> path.equals(wideFile) ? PosixFilePermissions.fromString("rw-rw-rw-")
            : PosixFilePermissions.fromString("rwx------"));
        assertThatThrownBy(() -> new PrivateFiles(List.of(onlyTheFileIsWide)).createPrivateFile(wideFile)).isInstanceOf(IOException.class).hasMessageContaining("ignored the mode");
        assertThat(wideFile).doesNotExist();
        assertThat(folder).as("a folder that existed is not touched").isDirectory();
    }

    @Test
    @DisplayName("S-D0117-07: when the list of a new folder or file cannot be set, what the call created is removed and the answer is an IOException (a real denial of the right to change the list)")
    void whatCannotBeMadePrivateOnAnAclStoreIsRemoved() throws IOException {
        TestFiles.requireOrSkip("acl", Files.getFileStore(directory).supportsFileAttributeView("acl"),
            "the file store of the test folder has no acl view (it is a posix store); a run on a machine with ACLs states -Pportal.test.require=acl and fails instead of skipping");
        Path parent = Files.createDirectory(directory.resolve("no-write-dac"));
        TestFiles.forbidChangingTheListOfWhatIsMadeIn(parent);
        Path nested = parent.resolve("outer").resolve("known_hosts");

        assertThatThrownBy(() -> TestFiles.privateFiles().createPrivateFile(nested)).isInstanceOf(IOException.class).hasMessageContaining("could not be made private");

        assertThat(parent.resolve("outer")).doesNotExist();

        Path inExisting = parent.resolve("known_hosts");
        assertThatThrownBy(() -> new PrivateFiles(List.of(new AclPrivateFileRule())).createPrivateFile(inExisting)).isInstanceOf(IOException.class)
            .hasMessageContaining("could not be made private");
        assertThat(inExisting).doesNotExist();
    }

    @Test
    @DisplayName("S-D0117-07, S-D0117-01: the read-back is of what the call created: a file and a folder that exist are not read at all, whatever their rights")
    void existingPathsAreNeverReadBack() throws IOException {
        TestFiles.requireOrSkip("posix", Files.getFileStore(directory).supportsFileAttributeView("posix"),
            "the file store of the test folder has no posix view (it is an NTFS volume); the Linux CI requires it (-Pportal.test.require=posix) and fails instead of skipping");
        List<Path> asked = new ArrayList<>();
        PosixPrivateFileRule recording = new PosixPrivateFileRule(path -> {
            asked.add(path);
            return PosixFilePermissions.fromString("rwx------");
        });
        Path file = Files.writeString(Files.createDirectory(directory.resolve("existing")).resolve("known_hosts"), "kept\n");

        new PrivateFiles(List.of(recording)).createPrivateFile(file);
        assertThat(asked).isEmpty();

        Path created = directory.resolve("existing").resolve("second");
        new PrivateFiles(List.of(recording)).createPrivateFile(created);
        assertThat(asked).containsExactly(created);
    }
}
