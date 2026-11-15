package io.github.jaeseongs95.portal.ssh.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
