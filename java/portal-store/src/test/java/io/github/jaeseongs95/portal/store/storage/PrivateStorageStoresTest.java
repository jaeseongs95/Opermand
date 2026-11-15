package io.github.jaeseongs95.portal.store.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;

import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.portal.app.audit.AuditAction;
import io.github.jaeseongs95.portal.app.audit.AuditEvent;
import io.github.jaeseongs95.portal.app.audit.AuditFailedException;
import io.github.jaeseongs95.portal.domain.error.ArtifactErrorCode;
import io.github.jaeseongs95.portal.domain.error.ExecutionErrorCode;
import io.github.jaeseongs95.portal.domain.error.RequestRefusedException;
import io.github.jaeseongs95.portal.domain.id.ArtifactId;
import io.github.jaeseongs95.portal.domain.id.OperationId;
import io.github.jaeseongs95.portal.app.output.OutputId;
import io.github.jaeseongs95.portal.store.StorageRoots;
import io.github.jaeseongs95.portal.store.TestStorage;
import io.github.jaeseongs95.portal.store.artifact.FileArtifactPayloadStore;
import io.github.jaeseongs95.portal.store.audit.AuditFileLocation;
import io.github.jaeseongs95.portal.store.audit.JsonlAuditLog;
import io.github.jaeseongs95.portal.store.output.OutputFiles;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-D0117, S2-DB-10: the three stores that write sensitive bytes (artifacts, command outputs, the audit file) use one rule. They create their folders and files private to
 * their owner, they use what exists as it is, whoever else may use it (a folder or a file that others can use is accepted), they refuse what is not a folder or not a
 * regular file, and they refuse a file store on which nothing can be made private. The tests run on whatever file store the machine has (no skip): the folder is opened up
 * with the means of that file store ({@code TestStorage#openUp}), so on a Windows PC they change real NTFS ACLs.
 */
class PrivateStorageStoresTest {

    private static final ArtifactId ARTIFACT = new ArtifactId("a".repeat(48));
    private static final OutputId OUTPUT = new OutputId("c".repeat(48));
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T00:00:00Z"), ZoneOffset.UTC);
    private static final OperationId OPERATION = new OperationId("b".repeat(48));

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    @DisplayName("S-D0117-06, RC-A-178: the artifact store makes its folder and its files private on this file store, and uses them")
    void theArtifactStoreCreatesPrivatePaths(@TempDir Path directory) throws IOException {
        Path root = directory.resolve("nested").resolve("artifacts");
        FileArtifactPayloadStore store = new FileArtifactPayloadStore(new StorageRoots(root, directory.resolve("outputs")), TestStorage.rules());

        assertThat(store.append(ARTIFACT, 0, new byte[] {1, 2, 3}).storedBytes()).isEqualTo(3);

        TestStorage.assertOwnerOnly(root);
        TestStorage.assertOwnerOnly(root.getParent());
        TestStorage.assertOwnerOnly(root.resolve(ARTIFACT.value() + ".part"));
        assertThat(store.length(ARTIFACT)).isEqualTo(3);
    }

    @Test
    @DisplayName("S-D0117-01, RC-A-179: the artifact store uses a root folder and a payload that others can use, changes no right, and answers every operation")
    void theArtifactStoreUsesWideRights(@TempDir Path directory) throws IOException {
        Path root = Files.createDirectory(directory.resolve("artifacts"));
        TestStorage.openUp(root);
        FileArtifactPayloadStore store = new FileArtifactPayloadStore(new StorageRoots(root, directory.resolve("outputs")), TestStorage.rules());

        store.append(ARTIFACT, 0, new byte[] {1, 2, 3});
        Path file = root.resolve(ARTIFACT.value() + ".part");
        TestStorage.openUp(file);

        assertThat(store.append(ARTIFACT, 3, new byte[] {4}).storedBytes()).isEqualTo(4);
        assertThat(store.length(ARTIFACT)).isEqualTo(4);
        assertThat(store.stored()).containsExactly(ARTIFACT);
        assertThat(store.usable().usableBytes()).isPositive();
        store.truncate(ARTIFACT, 2);
        assertThat(Files.readAllBytes(file)).isEqualTo(new byte[] {1, 2});
        store.quarantine(ARTIFACT);
        assertThat(store.stored()).isEmpty();
    }

    @Test
    @DisplayName("S-D0117-04: the artifact store refuses a folder in place of a payload and a plain file in place of its root with ARTIFACT_STORAGE_UNSAFE, and keeps both as they are")
    void theArtifactStoreRefusesWhatIsNotAFileOrAFolder(@TempDir Path directory) throws IOException {
        Path root = Files.createDirectory(directory.resolve("artifacts"));
        Path folderInPlace = Files.createDirectory(root.resolve(ARTIFACT.value() + ".part"));
        FileArtifactPayloadStore store = new FileArtifactPayloadStore(new StorageRoots(root, directory.resolve("outputs")), TestStorage.rules());

        assertThatThrownBy(() -> store.length(ARTIFACT)).isInstanceOfSatisfying(RequestRefusedException.class,
            e -> assertThat(e.code()).isEqualTo(ArtifactErrorCode.ARTIFACT_STORAGE_UNSAFE));
        assertThatThrownBy(() -> store.append(ARTIFACT, 0, new byte[] {1})).isInstanceOfSatisfying(RequestRefusedException.class,
            e -> assertThat(e.code()).isEqualTo(ArtifactErrorCode.ARTIFACT_STORAGE_UNSAFE));
        assertThat(folderInPlace).isDirectory();

        Path plain = Files.writeString(directory.resolve("plain-root"), "keep");
        FileArtifactPayloadStore onAFile = new FileArtifactPayloadStore(new StorageRoots(plain, directory.resolve("outputs")), TestStorage.rules());
        assertThatThrownBy(onAFile::stored).isInstanceOfSatisfying(RequestRefusedException.class, e -> assertThat(e.code()).isEqualTo(ArtifactErrorCode.ARTIFACT_STORAGE_UNSAFE));
        assertThat(Files.readString(plain)).isEqualTo("keep");
    }

    @Test
    @DisplayName("S-D0117-07, S2-DB-10: a file store that no rule supports is refused by the artifact store with ARTIFACT_STORAGE_UNSAFE, not accepted as not checked, and nothing is created")
    void theArtifactStoreRefusesAFileStoreWithoutARule(@TempDir Path directory) {
        FileArtifactPayloadStore store = new FileArtifactPayloadStore(new StorageRoots(directory.resolve("artifacts"), directory.resolve("outputs")), new PrivateStorages(List.of()));

        assertThatThrownBy(() -> store.append(ARTIFACT, 0, new byte[] {1})).isInstanceOfSatisfying(RequestRefusedException.class,
            e -> assertThat(e.code()).isEqualTo(ArtifactErrorCode.ARTIFACT_STORAGE_UNSAFE));
        assertThat(Files.exists(directory.resolve("artifacts"), LinkOption.NOFOLLOW_LINKS)).isFalse();
    }

    @Test
    @DisplayName("S-D0117-06, S-D0117-01: the output folder and its files are created private; a folder and a file that others can use are used as they are")
    void theOutputFilesCreatePrivateAndUseWhatExists(@TempDir Path directory) throws IOException {
        Path created = directory.resolve("created");
        OutputFiles files = new OutputFiles(new StorageRoots(directory, created), TestStorage.rules());
        assertThat(files.directory()).isEqualTo(created.toAbsolutePath());
        TestStorage.assertOwnerOnly(created);
        Path madeByTheStore = files.path(OUTPUT, OutputFiles.PayloadKind.OUT);
        try (FileChannel channel = files.openFresh(madeByTheStore)) {
            assertThat(channel.size()).isZero();
        }
        TestStorage.assertOwnerOnly(madeByTheStore);

        Path open = Files.createDirectory(directory.resolve("open"));
        TestStorage.openUp(open);
        OutputFiles openFiles = new OutputFiles(new StorageRoots(directory, open), TestStorage.rules());
        Path inOpen = openFiles.path(OUTPUT, OutputFiles.PayloadKind.OUT);
        try (FileChannel channel = openFiles.openFresh(inOpen)) {
            assertThat(channel.size()).isZero();
        }
        TestStorage.openUp(inOpen);
        assertThat(openFiles.sizeOf(inOpen)).isZero();
        try (FileChannel channel = openFiles.openRead(inOpen)) {
            assertThat(channel.size()).isZero();
        }
    }

    @Test
    @DisplayName("S-D0117-04, S-D0117-07: the output folder refuses a plain file in place of a folder and a folder in place of a payload (OUTPUT_CAPTURE_FAILED, the output has no code of its own), and so does a file store without a rule")
    void theOutputFilesRefuseWhatCannotBeUsed(@TempDir Path directory) throws IOException {
        Path plain = Files.writeString(directory.resolve("plain"), "keep");
        OutputFiles onAFile = new OutputFiles(new StorageRoots(directory, plain), TestStorage.rules());
        assertThatThrownBy(onAFile::directory).isInstanceOfSatisfying(RequestRefusedException.class, e -> assertThat(e.code()).isEqualTo(ExecutionErrorCode.OUTPUT_CAPTURE_FAILED));
        assertThat(Files.readString(plain)).isEqualTo("keep");

        Path folder = Files.createDirectory(directory.resolve("outputs"));
        OutputFiles files = new OutputFiles(new StorageRoots(directory, folder), TestStorage.rules());
        Path folderInPlace = Files.createDirectory(files.path(OUTPUT, OutputFiles.PayloadKind.OUT));
        assertThatThrownBy(() -> files.sizeOf(folderInPlace)).isInstanceOfSatisfying(RequestRefusedException.class,
            e -> assertThat(e.code()).isEqualTo(ExecutionErrorCode.OUTPUT_CAPTURE_FAILED));
        assertThat(folderInPlace).isDirectory();

        OutputFiles withoutRule = new OutputFiles(new StorageRoots(directory, directory.resolve("other")), new PrivateStorages(List.of()));
        assertThatThrownBy(withoutRule::directory).isInstanceOf(RequestRefusedException.class);
        assertThat(Files.exists(directory.resolve("other"), LinkOption.NOFOLLOW_LINKS)).isFalse();
    }

    @Test
    @DisplayName("S-D0117-06, S-D0117-01: the audit file is created private, and an audit file that others can use is opened as it is and its lines are kept")
    void theAuditFileIsCreatedPrivateAndUsedAsItIs(@TempDir Path directory) throws IOException {
        Path created = directory.resolve("logs").resolve("audit.jsonl");
        try (JsonlAuditLog log = new JsonlAuditLog(new AuditFileLocation(created), CLOCK, mapper, TestStorage.rules())) {
            log.append(new AuditEvent(AuditAction.SUBMITTED, OPERATION, "kept"));
        }
        TestStorage.assertOwnerOnly(created);
        TestStorage.assertOwnerOnly(created.getParent());

        TestStorage.openUp(created);
        try (JsonlAuditLog log = new JsonlAuditLog(new AuditFileLocation(created), CLOCK, mapper, TestStorage.rules())) {
            log.append(new AuditEvent(AuditAction.SUBMITTED, OPERATION, "and more"));
        }

        String text = Files.readString(created, StandardCharsets.UTF_8);
        assertThat(text).contains("\"note\":\"kept\"").contains("\"note\":\"and more\"");
    }

    @Test
    @DisplayName("S-D0117-11: several first opens of the same new output file and of the same new audit file at the same moment all succeed; the file system decides who created each, and each file is owner only")
    void severalFirstOpensOfOneFileAllSucceed(@TempDir Path directory) throws Exception {
        OutputFiles files = new OutputFiles(new StorageRoots(directory, directory.resolve("outputs")), TestStorage.rules());
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 15; round++) {
                Path output = files.path(new OutputId(Integer.toHexString(round).repeat(48).substring(0, 48)), OutputFiles.PayloadKind.STREAM);
                Path audit = directory.resolve("audit-" + round).resolve("audit.jsonl");
                CyclicBarrier together = new CyclicBarrier(threads);
                List<Future<Void>> results = new ArrayList<>();
                for (int thread = 0; thread < threads; thread++) {
                    results.add(pool.submit(() -> {
                        together.await();
                        try (FileChannel channel = files.openAppend(output)) {
                            channel.write(java.nio.ByteBuffer.wrap(new byte[] {1}));
                        }
                        try (JsonlAuditLog log = new JsonlAuditLog(new AuditFileLocation(audit), CLOCK, mapper, TestStorage.rules())) {
                            log.append(new AuditEvent(AuditAction.SUBMITTED, OPERATION, "x"));
                        }
                        return null;
                    }));
                }
                for (Future<Void> result : results) {
                    result.get(); // an ExecutionException here is an open that failed
                }
                assertThat(Files.size(output)).isEqualTo(threads);
                TestStorage.assertOwnerOnly(output);
                TestStorage.assertOwnerOnly(audit);
                assertThat(Files.readAllLines(audit)).hasSize(threads);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("S-D0117-04, S-D0117-07: a folder in place of the audit file is refused, and so is a file store without a rule; nothing is created")
    void theAuditFileRefusesWhatCannotBeUsed(@TempDir Path directory) throws IOException {
        Path folderInPlace = Files.createDirectory(directory.resolve("audit.jsonl"));
        assertThatThrownBy(() -> new JsonlAuditLog(new AuditFileLocation(folderInPlace), CLOCK, mapper, TestStorage.rules())).isInstanceOf(AuditFailedException.class);

        Path file = directory.resolve("other").resolve("audit.jsonl");
        assertThatThrownBy(() -> new JsonlAuditLog(new AuditFileLocation(file), CLOCK, mapper, new PrivateStorages(List.of()))).isInstanceOf(AuditFailedException.class);
        assertThat(Files.exists(file.getParent(), LinkOption.NOFOLLOW_LINKS)).isFalse();
    }
}
