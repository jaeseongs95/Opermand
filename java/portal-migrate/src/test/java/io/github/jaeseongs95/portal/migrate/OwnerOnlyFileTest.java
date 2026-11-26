package io.github.jaeseongs95.portal.migrate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.portal.migrate.settings.OwnerOnlyFile;

/** The file that holds secrets is private to its owner on the file system the test runs on (permission bits on Linux, an access control list on Windows), written whole or not at all. */
class OwnerOnlyFileTest {

    private final OwnerOnlyFile files = new OwnerOnlyFile();

    private static final byte[] CONTENT = "KEY=invented-value\n".getBytes(StandardCharsets.UTF_8);

    private static void assertOnlyThese(Path folder, String... names) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            assertThat(files.map(path -> path.getFileName().toString())).containsExactlyInAnyOrder(names);
        }
    }

    @Test
    void aNewFileHasTheContentAndOnlyItsOwnerCanUseIt(@TempDir Path root) throws IOException {
        Path file = root.resolve("settings.env");

        files.createNew(file, CONTENT);

        assertThat(Files.readAllBytes(file)).isEqualTo(CONTENT);
        LegacyEnvFixture.assertOwnerOnly(file);
        assertOnlyThese(root, "settings.env");
    }

    private static void assertOwnerOnlyFolder(Path folder) throws IOException {
        FileStore store = Files.getFileStore(folder);
        if (store.supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(folder, LinkOption.NOFOLLOW_LINKS)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        } else if (store.supportsFileAttributeView("acl")) {
            AclFileAttributeView view = Files.getFileAttributeView(folder, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            UserPrincipal owner = view.getOwner();
            assertThat(view.getAcl()).isNotEmpty().allSatisfy(entry -> {
                assertThat(entry.type()).isEqualTo(AclEntryType.ALLOW);
                assertThat(entry.principal()).isEqualTo(owner);
            });
            assertThat(view.getAcl().stream().map(AclEntry::principal).distinct()).containsExactly(owner);
        }
    }

    @Test
    void theMissingFoldersAreMadeAndTheFileIsStillPrivate(@TempDir Path root) throws IOException {
        Path file = root.resolve("a").resolve("b").resolve("settings.env");

        files.createNew(file, CONTENT);

        assertThat(Files.readAllBytes(file)).isEqualTo(CONTENT);
        LegacyEnvFixture.assertOwnerOnly(file);
        assertOwnerOnlyFolder(root.resolve("a"));
        assertOwnerOnlyFolder(root.resolve("a").resolve("b"));
    }

    @Test
    void aBrokenSymbolicLinkAtThePathIsRefusedAndLeftAlone(@TempDir Path root) throws IOException {
        Path link = root.resolve("settings.env");
        Files.createSymbolicLink(link, root.resolve("does-not-exist.env"));

        assertThatThrownBy(() -> files.createNew(link, CONTENT)).isInstanceOf(FileAlreadyExistsException.class);

        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertOnlyThese(root, "settings.env");
    }

    @Test
    void aFileThatIsThereIsNeverReplacedAndNothingIsLeftBeside(@TempDir Path root) throws IOException {
        Path file = root.resolve("settings.env");
        Files.writeString(file, "what the operator wrote\n");

        assertThatThrownBy(() -> files.createNew(file, CONTENT)).isInstanceOf(FileAlreadyExistsException.class);

        assertThat(Files.readString(file)).isEqualTo("what the operator wrote\n");
        assertOnlyThese(root, "settings.env");
    }

    @Test
    void aFolderAtThePathFailsAndLeavesNoStagingFile(@TempDir Path root) throws IOException {
        Path file = root.resolve("settings.env");
        Files.createDirectory(file);

        assertThatThrownBy(() -> files.createNew(file, CONTENT)).isInstanceOf(IOException.class);

        assertOnlyThese(root, "settings.env");
        assertThat(file).isDirectory();
    }

    @Test
    void aLargeContentIsWrittenWhole(@TempDir Path root) throws IOException {
        Path file = root.resolve("settings.env");
        byte[] large = ("K=" + "v".repeat(1_000_000) + "\n").getBytes(StandardCharsets.UTF_8);

        files.createNew(file, large);

        assertThat(Files.readAllBytes(file)).isEqualTo(large);
    }
}
