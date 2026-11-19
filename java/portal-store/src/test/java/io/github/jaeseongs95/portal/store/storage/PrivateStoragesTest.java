package io.github.jaeseongs95.portal.store.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.portal.app.storage.PrivateStorage;
import io.github.jaeseongs95.portal.app.storage.PrivateStorages;
import io.github.jaeseongs95.portal.app.storage.UnsafeStorageException;
import io.github.jaeseongs95.portal.store.TestStorage;

/** The choice of the rule by what the file store offers, and what is the same for every rule: a path must be a regular file or a folder, nothing else is asked. */
class PrivateStoragesTest {

    /** A rule that supports what it is told to; it creates with the plain calls of the JDK. */
    private static final class Rule implements PrivateStorage {

        private final boolean supported;

        Rule(boolean supported) {
            this.supported = supported;
        }

        @Override
        public boolean supports(FileStore store) {
            return supported;
        }

        @Override
        public void createFolder(Path folder) throws IOException {
            Files.createDirectories(folder);
        }

        @Override
        public FileChannel open(Path file, Set<? extends OpenOption> options) throws IOException {
            return FileChannel.open(file, options);
        }
    }

    @Test
    @DisplayName("S2-DB-10: the first rule that supports the file store is the one used; a rule that does not support it is not asked")
    void theFirstSupportingRuleIsChosen(@TempDir Path directory) throws IOException {
        Rule no = new Rule(false);
        Rule first = new Rule(true);
        Rule second = new Rule(true);
        PrivateStorages storages = new PrivateStorages(List.of(no, first, second));

        assertThat(storages.ruleFor(directory)).isSameAs(first);
        assertThat(storages.ruleFor(directory.resolve("not").resolve("there").resolve("yet"))).as("the file store of the nearest folder that exists").isSameAs(first);
    }

    @Test
    @DisplayName("S-D0117-07, S2-DB-10: a file store that no rule supports is refused, never accepted as not checked, and nothing is created with the rights of the parent")
    void aFileStoreWithoutARuleIsRefused(@TempDir Path directory) {
        PrivateStorages storages = new PrivateStorages(List.of(new Rule(false)));
        Path folder = directory.resolve("new");
        Path file = directory.resolve("new.part");

        assertThatThrownBy(() -> storages.ruleFor(directory)).isInstanceOf(UnsafeStorageException.class).hasMessageContaining("neither");
        assertThatThrownBy(() -> storages.createFolder(folder)).isInstanceOf(UnsafeStorageException.class);
        assertThatThrownBy(() -> storages.open(file, Set.of(java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)))
            .isInstanceOf(UnsafeStorageException.class);
        assertThatThrownBy(() -> new PrivateStorages(List.of()).ruleFor(directory)).as("no rule at all is the same refusal, not an empty list of checks")
            .isInstanceOf(UnsafeStorageException.class);
        assertThat(folder).doesNotExist();
        assertThat(file).doesNotExist();
    }

    @Test
    @DisplayName("S-D0117-04: what is not a regular file is refused as a file and what is not a folder as a folder; a missing path is neither; the reason says nothing of rights or owners")
    void kindsAreChecked(@TempDir Path directory) throws IOException {
        PrivateStorages storages = TestStorage.rules();
        Path file = Files.createFile(directory.resolve("plain"));

        assertThatThrownBy(() -> storages.requireRegularFile(directory)).isInstanceOf(UnsafeStorageException.class).hasMessageEndingWith(": not a regular file");
        assertThatThrownBy(() -> storages.requireFolder(file)).isInstanceOf(UnsafeStorageException.class).hasMessageEndingWith(": not a folder");
        assertThatThrownBy(() -> storages.requireRegularFile(directory.resolve("missing"))).isInstanceOf(UnsafeStorageException.class);
        assertThatThrownBy(() -> storages.requireFolder(directory.resolve("missing"))).isInstanceOf(UnsafeStorageException.class);

        assertThatCode(() -> storages.requireRegularFile(file)).doesNotThrowAnyException();
        assertThatCode(() -> storages.requireFolder(directory)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("S-D0117-11: a file is created only with CREATE_NEW (CREATE alone is refused and creates nothing), and an existing file that is opened with CREATE_NEW is not taken for created: it stays as it is, with its rights")
    void theFileSystemDecidesWhetherTheCallCreatedTheFile(@TempDir Path directory) throws IOException {
        PrivateStorages storages = TestStorage.rules();
        Path file = directory.resolve("payload.part");

        assertThatThrownBy(() -> storages.open(file, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE))).isInstanceOf(IllegalArgumentException.class);
        assertThat(file).doesNotExist();

        Files.writeString(file, "kept");
        TestStorage.openUp(file);
        assertThatThrownBy(() -> storages.open(file, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))).isInstanceOf(FileAlreadyExistsException.class);

        assertThat(Files.readString(file)).isEqualTo("kept");
        assertThatThrownBy(() -> TestStorage.assertOwnerOnly(file)).as("the rights that others have are not taken away, as nobody created the file").isInstanceOf(AssertionError.class);
        try (FileChannel channel = storages.open(file, Set.of(StandardOpenOption.WRITE, StandardOpenOption.APPEND))) {
            assertThat(channel.size()).isEqualTo(4);
        }
    }

    @Test
    @DisplayName("S-D0117-10: several first starts that create the same new folder at the same moment all succeed (the one that loses a creation does not fail, and does not judge what the other made); the folder is owner only")
    void severalCreationsOfTheSameFolderAllSucceed(@TempDir Path directory) throws Exception {
        PrivateStorages storages = TestStorage.rules();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 25; round++) {
                Path folder = directory.resolve("round-" + round).resolve("inner");
                CyclicBarrier together = new CyclicBarrier(threads);
                List<Future<Void>> results = new ArrayList<>();
                for (int thread = 0; thread < threads; thread++) {
                    results.add(pool.submit(() -> {
                        together.await();
                        storages.createFolder(folder);
                        return null;
                    }));
                }
                for (Future<Void> result : results) {
                    result.get(); // an ExecutionException here is a creation that failed
                }
                TestStorage.assertOwnerOnly(folder);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("S-D0117-01: a file and a folder that others can use are accepted, and so is an empty file (an empty payload is a normal state)")
    void wideAndEmptyPathsAreAccepted(@TempDir Path directory) throws IOException {
        PrivateStorages storages = TestStorage.rules();
        Path folder = Files.createDirectory(directory.resolve("wide"));
        Path file = Files.createFile(folder.resolve("empty.part"));
        TestStorage.openUp(folder);
        TestStorage.openUp(file);

        assertThatCode(() -> storages.requireFolder(folder)).doesNotThrowAnyException();
        assertThatCode(() -> storages.requireRegularFile(file)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("S-D0117-02: a file that another account owns is accepted")
    void aForeignOwnedFileIsAccepted() throws IOException {
        Path foreign = TestStorage.foreignOwnedFile();

        assertThatCode(() -> TestStorage.rules().requireRegularFile(foreign)).doesNotThrowAnyException();
    }
}
