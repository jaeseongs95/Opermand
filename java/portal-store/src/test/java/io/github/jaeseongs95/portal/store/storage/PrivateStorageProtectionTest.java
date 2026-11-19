package io.github.jaeseongs95.portal.store.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.portal.app.storage.PrivateStorages;
import io.github.jaeseongs95.portal.app.storage.ProtectionCheck;
import io.github.jaeseongs95.portal.app.storage.StorageArea;
import io.github.jaeseongs95.portal.app.storage.UnsafeStorageException;
import io.github.jaeseongs95.portal.store.StorageRoots;
import io.github.jaeseongs95.portal.store.TestStorage;

/** The guard of the file stores as the use cases ask it (RC-B-004, S3-UC-06, S-D0117): a folder is created private, one that exists is used, one that cannot be is the answer "unprotected". */
class PrivateStorageProtectionTest {

    @Test
    @DisplayName("S3-UC-06, S-D0117-06: folders that do not exist yet are created private to their owner and are accepted; a second check accepts them too")
    void missingFoldersAreCreatedPrivate(@TempDir Path base) throws IOException {
        PrivateStorageProtection protection = new PrivateStorageProtection(new StorageRoots(base.resolve("artifacts"), base.resolve("outputs")), TestStorage.rules());

        assertThat(protection.check()).isEqualTo(new ProtectionCheck.Protected());
        assertThat(Files.isDirectory(base.resolve("artifacts"))).isTrue();
        assertThat(Files.isDirectory(base.resolve("outputs"))).isTrue();
        TestStorage.assertOwnerOnly(base.resolve("artifacts"));
        TestStorage.assertOwnerOnly(base.resolve("outputs"));
        assertThat(protection.check()).isEqualTo(new ProtectionCheck.Protected());
    }

    @Test
    @DisplayName("S-D0117-01 / RC-B-004: folders that others can use are accepted as they are, so the start and the reapers are not held back by their rights")
    void anOpenFolderIsProtected(@TempDir Path base) throws IOException {
        Path artifacts = Files.createDirectory(base.resolve("artifacts"));
        Path outputs = Files.createDirectory(base.resolve("outputs"));
        TestStorage.openUp(artifacts);
        TestStorage.openUp(outputs);
        PrivateStorageProtection protection = new PrivateStorageProtection(new StorageRoots(artifacts, outputs), TestStorage.rules());

        assertThat(protection.check()).isEqualTo(new ProtectionCheck.Protected());
    }

    @Test
    @DisplayName("S-D0117-04 / RC-B-004: a plain file where a folder should be is the answer unprotected, named by its area, with the cause kept; the answer is a value and nothing is thrown")
    void aFileInPlaceOfAFolderIsUnprotected(@TempDir Path base) throws IOException {
        Path outputs = Files.writeString(base.resolve("outputs"), "keep");
        PrivateStorageProtection protection = new PrivateStorageProtection(new StorageRoots(base.resolve("artifacts"), outputs), TestStorage.rules());

        ProtectionCheck check = protection.check();

        assertThat(check).isInstanceOfSatisfying(ProtectionCheck.Unprotected.class, unprotected -> {
            assertThat(unprotected.area()).isEqualTo(StorageArea.OUTPUTS);
            assertThat(unprotected.cause()).isInstanceOf(UnsafeStorageException.class).hasMessageEndingWith(": not a folder");
        });
        assertThat(Files.readString(outputs)).isEqualTo("keep");
    }

    @Test
    @DisplayName("S-D0117-07: a file store that cannot make a folder private is the answer unprotected and the folders are not created")
    void aFileStoreWithoutARuleIsUnprotected(@TempDir Path base) {
        PrivateStorageProtection protection = new PrivateStorageProtection(new StorageRoots(base.resolve("artifacts"), base.resolve("outputs")), new PrivateStorages(List.of()));

        assertThat(protection.check()).isInstanceOfSatisfying(ProtectionCheck.Unprotected.class, unprotected -> {
            assertThat(unprotected.area()).isEqualTo(StorageArea.ARTIFACTS);
            assertThat(unprotected.cause()).isInstanceOf(UnsafeStorageException.class);
        });
        assertThat(base.resolve("artifacts")).doesNotExist();
        assertThat(base.resolve("outputs")).doesNotExist();
    }
}
