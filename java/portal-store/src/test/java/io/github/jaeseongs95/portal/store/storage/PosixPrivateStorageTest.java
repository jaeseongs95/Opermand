package io.github.jaeseongs95.portal.store.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.portal.store.TestStorage;

/** What the rule makes on a file store with permission bits (a Linux or macOS volume), and that it leaves what exists as it is. */
class PosixPrivateStorageTest {

    private final PosixPrivateStorage rule = new PosixPrivateStorage();

    @TempDir
    Path directory;

    @BeforeEach
    void onlyWhereTheFileStoreHasPermissionBits() throws IOException {
        Assumptions.assumeTrue(rule.supports(Files.getFileStore(directory)),
            "the file store of the test folder has no posix view (it is an NTFS volume); runs on the Linux CI (Linux CI에서 실행)");
    }

    @Test
    @DisplayName("S-D0117-06, RC-A-178: a folder made by the rule is rwx------ with its new parents, and a file made by it is rw-------")
    void newPathsAreOwnerOnly() throws IOException {
        Path folder = directory.resolve("outer").resolve("private");
        rule.createFolder(folder);
        Path file = folder.resolve("payload.part");
        try (FileChannel channel = rule.open(file, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            assertThat(channel.size()).isZero();
        }

        assertThat(Files.getPosixFilePermissions(folder)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.getPosixFilePermissions(folder.getParent())).as("the parent that the rule created").isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        TestStorage.assertOwnerOnly(file);
    }

    @Test
    @DisplayName("S-D0117-01: a folder or a file that the group or others can use is left as it is by the rule: createFolder and open change no mode and refuse nothing")
    void existingPathsAreNotChangedOrJudged() throws IOException {
        Path folder = Files.createDirectory(directory.resolve("shared"));
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxrwxr-x"));
        Path file = Files.writeString(folder.resolve("payload.part"), "bytes");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-r--"));

        rule.createFolder(folder);
        try (FileChannel channel = rule.open(file, Set.of(StandardOpenOption.READ))) {
            assertThat(channel.size()).isEqualTo(5);
        }

        assertThat(Files.getPosixFilePermissions(folder)).isEqualTo(PosixFilePermissions.fromString("rwxrwxr-x"));
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-rw-r--"));
        assertThat(Files.readString(file)).isEqualTo("bytes");
    }
}
