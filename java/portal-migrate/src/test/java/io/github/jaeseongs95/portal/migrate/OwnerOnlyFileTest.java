package io.github.jaeseongs95.portal.migrate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.portal.migrate.settings.OwnerOnlyFile;

/** The file that holds secrets is private to its owner on the file system the test runs on (permission bits on Linux, an access control list on Windows), written whole or not at all. */
class OwnerOnlyFileTest {

    private static final byte[] CONTENT = "KEY=invented-value\n".getBytes(StandardCharsets.UTF_8);

    private static void assertOnlyThese(Path folder, String... names) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            assertThat(files.map(path -> path.getFileName().toString())).containsExactlyInAnyOrder(names);
        }
    }

    @Test
    void aNewFileHasTheContentAndOnlyItsOwnerCanUseIt(@TempDir Path root) throws IOException {
        Path file = root.resolve("settings.env");

        OwnerOnlyFile.createNew(file, CONTENT);

        assertThat(Files.readAllBytes(file)).isEqualTo(CONTENT);
        LegacyEnvFixture.assertOwnerOnly(file);
        assertOnlyThese(root, "settings.env");
    }

    @Test
    void theMissingFoldersAreMadeAndTheFileIsStillPrivate(@TempDir Path root) throws IOException {
        Path file = root.resolve("a").resolve("b").resolve("settings.env");

        OwnerOnlyFile.createNew(file, CONTENT);

        assertThat(Files.readAllBytes(file)).isEqualTo(CONTENT);
        LegacyEnvFixture.assertOwnerOnly(file);
    }

    @Test
    void aFileThatIsThereIsNeverReplacedAndNothingIsLeftBeside(@TempDir Path root) throws IOException {
        Path file = root.resolve("settings.env");
        Files.writeString(file, "what the operator wrote\n");

        assertThatThrownBy(() -> OwnerOnlyFile.createNew(file, CONTENT)).isInstanceOf(FileAlreadyExistsException.class);

        assertThat(Files.readString(file)).isEqualTo("what the operator wrote\n");
        assertOnlyThese(root, "settings.env");
    }

    @Test
    void aFolderAtThePathFailsAndLeavesNoStagingFile(@TempDir Path root) throws IOException {
        Path file = root.resolve("settings.env");
        Files.createDirectory(file);

        assertThatThrownBy(() -> OwnerOnlyFile.createNew(file, CONTENT)).isInstanceOf(IOException.class);

        assertOnlyThese(root, "settings.env");
        assertThat(file).isDirectory();
    }

    @Test
    void aLargeContentIsWrittenWhole(@TempDir Path root) throws IOException {
        Path file = root.resolve("settings.env");
        byte[] large = ("K=" + "v".repeat(1_000_000) + "\n").getBytes(StandardCharsets.UTF_8);

        OwnerOnlyFile.createNew(file, large);

        assertThat(Files.readAllBytes(file)).isEqualTo(large);
    }
}
