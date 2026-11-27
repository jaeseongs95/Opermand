package io.github.jaeseongs95.opermand.app.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A link whose target is missing, standing where a private file should be (the known-hosts file, the audit file), is answered as what it is: not a regular file. The file
 * store of a path is the file store of its target, so asking it for the one of such a link used to fail with {@code NoSuchFileException} (defect D-0146).
 */
class PrivateStoragesDanglingLinkTest {

    private final PrivateStorages storages = new PrivateStorages(List.of(new PosixPrivateStorage(), new AclPrivateStorage()));

    @Test
    @DisplayName("S-D0146-01 D-0146 a link without a target where the private file should be is left as it is and then refused as 'not a regular file', not as NoSuchFileException")
    void aDanglingLinkIsNotARegularFile(@TempDir Path folder) throws IOException {
        Path link = folder.resolve("known_hosts");
        Files.createSymbolicLink(link, folder.resolve("missing-target"));

        assertThatCode(() -> storages.createPrivateFile(link)).doesNotThrowAnyException();

        assertThat(Files.isSymbolicLink(link)).as("the link was not replaced").isTrue();
        assertThat(Files.exists(folder.resolve("missing-target"))).as("and its target was not created through it").isFalse();
        assertThatThrownBy(() -> storages.requireRegularFile(link)).isInstanceOf(UnsafeStorageException.class).hasMessageContaining("not a regular file");
    }
}
