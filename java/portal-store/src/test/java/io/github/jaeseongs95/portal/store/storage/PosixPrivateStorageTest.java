package io.github.jaeseongs95.portal.store.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.portal.store.TestStorage;

/**
 * What the rule makes on a file store with permission bits (a Linux or macOS volume), that it leaves what exists as it is, and what it does when the file store ignores the
 * modes (case S-D0117-07: the file store is a real one, and the read-back that it answers with is the value of the test, because a CI runner cannot mount a file system that
 * ignores modes; the limit is written in the case).
 */
class PosixPrivateStorageTest {

    private static final Set<PosixFilePermission> WIDE = PosixFilePermissions.fromString("rwxr-xr-x");

    private final PosixPrivateStorage rule = new PosixPrivateStorage();

    @TempDir
    Path directory;

    @BeforeEach
    void onlyWhereTheFileStoreHasPermissionBits() throws IOException {
        TestStorage.requireOrSkip("posix", rule.supports(Files.getFileStore(directory)),
            "the file store of the test folder has no posix view (it is an NTFS volume); the Linux CI requires it (-Pportal.test.require=posix) and fails instead of skipping");
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

    @Test
    @DisplayName("S-D0117-07: a file store that ignores the mode of a new folder: every folder that the call created is removed and the answer is UnsafeStorageException")
    void aNewFolderWithWideRightsIsRemoved() {
        PosixPrivateStorage ignoringModes = new PosixPrivateStorage(path -> WIDE);
        Path folder = directory.resolve("outer").resolve("inner");

        assertThatThrownBy(() -> ignoringModes.createFolder(folder)).isInstanceOf(UnsafeStorageException.class).hasMessageContaining("ignored the mode")
            .hasMessageContaining("rwxr-xr-x");

        assertThat(directory.resolve("outer")).doesNotExist();
    }

    @Test
    @DisplayName("S-D0117-07: when only the second folder that the call created has wide rights, the first one is removed too (nothing of this call is left behind)")
    void aWideFolderTakesTheEarlierOnesOfTheCallWithIt() {
        PosixPrivateStorage ignoringModes = new PosixPrivateStorage(path -> path.getFileName().toString().equals("inner") ? WIDE : PosixFilePermissions.fromString("rwx------"));
        Path folder = directory.resolve("outer").resolve("inner");

        assertThatThrownBy(() -> ignoringModes.createFolder(folder)).isInstanceOf(UnsafeStorageException.class);

        assertThat(directory.resolve("outer")).doesNotExist();
    }

    @Test
    @DisplayName("S-D0117-07: a file store that ignores the mode of a new file: the file is closed and removed, and the answer is UnsafeStorageException")
    void aNewFileWithWideRightsIsRemoved() {
        PosixPrivateStorage ignoringModes = new PosixPrivateStorage(path -> WIDE);
        Path file = directory.resolve("payload.part");

        assertThatThrownBy(() -> ignoringModes.open(file, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))).isInstanceOf(UnsafeStorageException.class)
            .hasMessageContaining("ignored the mode");

        assertThat(file).doesNotExist();
    }

    @Test
    @DisplayName("S-D0117-07: a folder with wide rights that cannot be removed again (its parent is not writable) is named in the message and stays; nothing is claimed to be removed that was not")
    void aWideFolderThatCannotBeRemovedIsNamed() throws IOException {
        Path parent = Files.createDirectory(directory.resolve("parent"));
        Path child = parent.resolve("child");
        // The read-back is the test's value (wide rights); it also takes the write right of the parent away, so that the removal that follows fails (an account that is not root).
        PosixPrivateStorage ignoringModes = new PosixPrivateStorage(path -> {
            Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("r-x------"));
            return WIDE;
        });
        try {
            assertThatThrownBy(() -> ignoringModes.createFolder(child)).isInstanceOfSatisfying(UnsafeStorageException.class,
                e -> assertThat(e).hasMessageContaining("ignored the mode").hasMessageContaining(child.toString()).hasMessageContaining("could not be removed again"));

            assertThat(child).as("the folder that could not be removed stays").isDirectory();
        } finally {
            Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    @DisplayName("S-D0117-07: a mode that cannot be read back is not shown to be private: what the call created is removed and the cause is kept")
    void aModeThatCannotBeReadIsRefused() {
        PosixPrivateStorage unreadable = new PosixPrivateStorage(path -> {
            throw new IOException("no permissions to read");
        });
        Path folder = directory.resolve("outer");

        assertThatThrownBy(() -> unreadable.createFolder(folder)).isInstanceOfSatisfying(UnsafeStorageException.class, e -> assertThat(e).hasCauseInstanceOf(IOException.class));

        assertThat(folder).doesNotExist();
    }

    @Test
    @DisplayName("S-D0117-07, S-D0117-01: the read-back is of the new path only: a folder and a file that exist are not read at all, whatever their rights")
    void existingPathsAreNeverReadBack() throws IOException {
        List<Path> asked = new ArrayList<>();
        PosixPrivateStorage recording = new PosixPrivateStorage(path -> {
            asked.add(path);
            return PosixFilePermissions.fromString("rwx------");
        });
        Path folder = Files.createDirectory(directory.resolve("existing"));
        Path file = Files.writeString(folder.resolve("payload.part"), "bytes");

        recording.createFolder(folder);
        try (FileChannel channel = recording.open(file, Set.of(StandardOpenOption.READ))) {
            assertThat(channel.size()).isEqualTo(5);
        }
        assertThat(asked).isEmpty();

        Path created = folder.resolve("created.part");
        try (FileChannel channel = recording.open(created, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            assertThat(channel.size()).isZero();
        }
        assertThat(asked).containsExactly(created);
    }
}
