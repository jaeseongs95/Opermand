package io.github.jaeseongs95.portal.store.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.jaeseongs95.portal.app.storage.PayloadRead;
import io.github.jaeseongs95.portal.app.storage.PayloadWrite;
import io.github.jaeseongs95.portal.domain.error.ArtifactErrorCode;
import io.github.jaeseongs95.portal.domain.error.ErrorCode;
import io.github.jaeseongs95.portal.domain.error.RequestRefusedException;
import io.github.jaeseongs95.portal.domain.id.ArtifactId;
import io.github.jaeseongs95.portal.domain.id.ArtifactIdGenerator;
import io.github.jaeseongs95.portal.store.StorageRoots;
import io.github.jaeseongs95.portal.store.TestStorage;

/**
 * The file store on a real folder (JUnit's {@code @TempDir}), no Spring. Whether the folder's file store has POSIX permissions, symbolic links or hard-link counts
 * is asked of the file store (never of the operating system's name); a case that is about one of them runs only where the file store has it, and says so.
 */
class FileArtifactPayloadStoreTest {

    private static final Logger LOG = LoggerFactory.getLogger(FileArtifactPayloadStoreTest.class);
    private static final String UNIX_VIEW = "unix";
    private static final String POSIX_VIEW = "posix";

    private final ArtifactIdGenerator ids = new ArtifactIdGenerator(new SecureRandom());
    private final ArtifactFixtures fixtures = new ArtifactFixtures();

    @Test
    @DisplayName("RC-B-093: an append writes at the end; an unacknowledged tail is cut back by truncate; a wrong offset, an oversize chunk and a truncate past the end are refused; the file ends up byte for byte the original")
    void aTailIsCutBackAndTheFileIsTheOriginal(@TempDir Path dir) throws IOException {
        FileArtifactPayloadStore store = storeIn(dir);
        ArtifactId id = ids.next();
        int chunk = ArtifactLimits.CHUNK_BYTES;
        byte[] first = fixtures.bytes(chunk, 0x97);
        byte[] tail = {0, (byte) 0xff, 0x0a};
        byte[] crashTail = "unacknowledged crash tail".getBytes(StandardCharsets.UTF_8);

        assertThat(store.append(id, 0, first)).isEqualTo(new PayloadWrite(chunk));
        assertThat(store.append(id, chunk, crashTail).storedBytes()).isEqualTo(chunk + crashTail.length);
        store.truncate(id, chunk);
        assertThat(store.length(id)).isEqualTo(chunk);

        assertRefused(() -> store.append(id, chunk + 1, tail), ArtifactErrorCode.ARTIFACT_CHUNK_CONFLICT);
        assertRefused(() -> store.append(id, chunk, fixtures.bytes(chunk + 1, 1)), ArtifactErrorCode.ARTIFACT_INVALID_CHUNK);
        assertRefused(() -> store.append(id, chunk, new byte[0]), ArtifactErrorCode.ARTIFACT_INVALID_CHUNK);
        assertRefused(() -> store.truncate(id, chunk + 10L), ArtifactErrorCode.ARTIFACT_SEQUENCE_OR_SIZE);

        assertThat(store.append(id, chunk, tail).storedBytes()).isEqualTo(chunk + 3L);
        byte[] expected = new byte[chunk + 3];
        System.arraycopy(first, 0, expected, 0, chunk);
        System.arraycopy(tail, 0, expected, chunk, 3);
        assertThat(read(store, id)).isEqualTo(expected);
    }

    @Test
    @DisplayName("S2-DB-09 (file half): when append returns, every byte is already in the file that another reader sees, so the metadata commit that follows can only be behind the bytes")
    void theBytesAreInTheFileWhenAppendReturns(@TempDir Path dir) throws IOException {
        FileArtifactPayloadStore store = storeIn(dir);
        ArtifactId id = ids.next();
        byte[] data = fixtures.bytes(1000, 0x41);

        PayloadWrite written = store.append(id, 0, data);

        assertThat(written.storedBytes()).isEqualTo(1000);
        assertThat(Files.readAllBytes(dir.resolve("artifacts").resolve(id.value() + ".part"))).isEqualTo(data);
    }

    @Test
    @DisplayName("RC-A-180 / RC-B-583: binary bytes with CR, LF and a byte over 127 are stored and read back unchanged, and the open read reports its size")
    void binaryBytesRoundTrip(@TempDir Path dir) throws IOException {
        FileArtifactPayloadStore store = storeIn(dir);
        ArtifactId id = ids.next();
        byte[] bytes = {0, (byte) 255, 13, 10, (byte) 128};

        store.append(id, 0, bytes);

        try (PayloadRead read = store.open(id)) {
            assertThat(read.size()).isEqualTo(5);
            assertThat(Channels.newInputStream(read.channel()).readAllBytes()).isEqualTo(bytes);
        }
    }

    @Test
    @DisplayName("RC-A-167: a missing file is NOT_FOUND for length and open, delete of a missing file is a no-op, and an append at an offset past zero does not create a file")
    void missingFilesAreAnswered(@TempDir Path dir) {
        FileArtifactPayloadStore store = storeIn(dir);
        ArtifactId id = ids.next();

        assertRefused(() -> store.length(id), ArtifactErrorCode.ARTIFACT_NOT_FOUND);
        assertRefused(() -> store.open(id), ArtifactErrorCode.ARTIFACT_NOT_FOUND);
        assertRefused(() -> store.truncate(id, 0), ArtifactErrorCode.ARTIFACT_NOT_FOUND);
        assertRefused(() -> store.quarantine(id), ArtifactErrorCode.ARTIFACT_NOT_FOUND);
        assertRefused(() -> store.append(id, 5, new byte[] {1}), ArtifactErrorCode.ARTIFACT_CHUNK_CONFLICT);
        store.delete(id);
        store.delete(id);
        assertThat(store.stored()).isEmpty();
    }

    @Test
    @DisplayName("RC-A-178: the root folder is created when it is not there, as a folder (not a link), empty, private to the owner where the file store has POSIX permissions")
    void theRootIsPreparedPrivately(@TempDir Path dir) throws IOException {
        FileArtifactPayloadStore store = storeIn(dir);
        Path root = dir.resolve("artifacts");
        assertThat(root).doesNotExist();

        assertThat(store.stored()).isEmpty();
        assertThat(store.usable().usableBytes()).isPositive();

        assertThat(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(Files.isSymbolicLink(root)).isFalse();
        try (Stream<Path> entries = Files.list(root)) {
            assertThat(entries.toList()).isEmpty();
        }
        ArtifactId id = ids.next();
        store.append(id, 0, new byte[] {1, 2, 3, 4});
        if (Files.getFileStore(root).supportsFileAttributeView(POSIX_VIEW)) {
            assertThat(Files.getPosixFilePermissions(root)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
            assertThat(Files.getPosixFilePermissions(root.resolve(id.value() + ".part"))).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    @DisplayName("S-D0117-04, RC-A-178: a plain file where the root folder should be is refused and its content is kept")
    void aPlainFileAsRootIsRefused(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("artifacts");
        Files.writeString(root, "keep", StandardCharsets.UTF_8);
        FileArtifactPayloadStore store = storeIn(dir);

        assertRefused(store::stored, ArtifactErrorCode.ARTIFACT_STORAGE_UNSAFE);
        assertRefused(() -> store.append(ids.next(), 0, new byte[] {1}), ArtifactErrorCode.ARTIFACT_STORAGE_UNSAFE);

        assertThat(Files.readString(root, StandardCharsets.UTF_8)).isEqualTo("keep");
    }

    @Test
    @DisplayName("S-D0117-03, RC-A-179: a symbolic link as the root folder is followed and stays a link (runs where the file store allows symbolic links)")
    void aSymbolicLinkAsRootIsFollowed(@TempDir Path dir) throws IOException {
        Path real = Files.createDirectory(dir.resolve("real"));
        Path root = dir.resolve("artifacts");
        Assumptions.assumeTrue(canLink(dir, root, real), "the file store of the test folder does not allow symbolic links; runs on the Linux CI or the Windows CI (CI에서 실행)");
        FileArtifactPayloadStore store = storeIn(dir);
        ArtifactId id = ids.next();

        assertThat(store.append(id, 0, new byte[] {1, 2}).storedBytes()).isEqualTo(2);

        assertThat(Files.isSymbolicLink(root)).isTrue();
        assertThat(Files.readAllBytes(real.resolve(id.value() + ".part"))).isEqualTo(new byte[] {1, 2});
        assertThat(store.stored()).containsExactly(id);
    }

    @Test
    @DisplayName("S-D0117-03, RC-A-166: a symbolic link in place of a payload is followed by every operation, the target is the payload, and deleting removes the link only (runs where the file store allows symbolic links)")
    void aSymbolicLinkPayloadIsFollowed(@TempDir Path dir) throws IOException {
        FileArtifactPayloadStore store = storeIn(dir);
        store.stored();
        Path target = Files.write(dir.resolve("target.bin"), new byte[] {9, 9, 9}, StandardOpenOption.CREATE_NEW);
        ArtifactId id = ids.next();
        Path link = dir.resolve("artifacts").resolve(id.value() + ".part");
        Assumptions.assumeTrue(canLink(dir, link, target), "the file store of the test folder does not allow symbolic links; runs on the Linux CI or the Windows CI (CI에서 실행)");

        assertThat(store.stored()).containsExactly(id);
        assertThat(store.length(id)).isEqualTo(3);
        assertThat(read(store, id)).isEqualTo(new byte[] {9, 9, 9});
        assertThat(store.append(id, 3, new byte[] {1}).storedBytes()).isEqualTo(4);
        store.truncate(id, 2);
        assertThat(Files.readAllBytes(target)).isEqualTo(new byte[] {9, 9});

        store.delete(id);
        assertThat(Files.exists(link, LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(Files.readAllBytes(target)).isEqualTo(new byte[] {9, 9});
    }

    @Test
    @DisplayName("S-D0117-03, RC-A-171: a payload with a second hard link is used as it is; deleting it removes the name only")
    void aHardLinkedPayloadIsUsed(@TempDir Path dir) throws IOException {
        FileArtifactPayloadStore store = storeIn(dir);
        store.stored();
        byte[] evidence = fixtures.bytes(48, 'c');
        Path original = Files.write(dir.resolve("evidence.bin"), evidence, StandardOpenOption.CREATE_NEW);
        ArtifactId id = new ArtifactId("c".repeat(48));
        Files.createLink(dir.resolve("artifacts").resolve(id.value() + ".part"), original);

        assertThat(store.stored()).containsExactly(id);
        assertThat(read(store, id)).isEqualTo(evidence);
        assertThat(store.append(id, 48, new byte[] {1}).storedBytes()).isEqualTo(49);
        assertThat(Files.size(original)).as("both names are one file").isEqualTo(49);

        store.delete(id);
        assertThat(Files.size(original)).isEqualTo(49);
        assertThat(store.stored()).isEmpty();
    }

    @Test
    @DisplayName("S-D0117-04: a folder in place of a payload and a symbolic link whose target is missing are refused by every operation with ARTIFACT_STORAGE_UNSAFE and are left as they are")
    void whatIsNotARegularFileIsRefused(@TempDir Path dir) throws IOException {
        FileArtifactPayloadStore store = storeIn(dir);
        store.stored();
        Path artifacts = dir.resolve("artifacts");
        ArtifactId folderId = ids.next();
        Files.createDirectory(artifacts.resolve(folderId.value() + ".part"));

        assertRefused(store::stored, ArtifactErrorCode.ARTIFACT_STORAGE_UNSAFE);
        assertRefused(() -> store.open(folderId), ArtifactErrorCode.ARTIFACT_STORAGE_UNSAFE);
        assertRefused(() -> store.length(folderId), ArtifactErrorCode.ARTIFACT_STORAGE_UNSAFE);
        assertRefused(() -> store.append(folderId, 0, new byte[] {1}), ArtifactErrorCode.ARTIFACT_STORAGE_UNSAFE);
        assertRefused(() -> store.truncate(folderId, 0), ArtifactErrorCode.ARTIFACT_STORAGE_UNSAFE);
        assertThat(artifacts.resolve(folderId.value() + ".part")).isDirectory();
    }

    @Test
    @DisplayName("S-D0117-04: a symbolic link whose target is missing is not a regular file: it is refused with ARTIFACT_STORAGE_UNSAFE and stays a link (runs where the file store allows symbolic links)")
    void aDanglingLinkIsRefused(@TempDir Path dir) throws IOException {
        FileArtifactPayloadStore store = storeIn(dir);
        store.stored();
        ArtifactId id = ids.next();
        Path dangling = dir.resolve("artifacts").resolve(id.value() + ".part");
        Assumptions.assumeTrue(canLink(dir, dangling, dir.resolve("no-such-target.bin")),
            "the file store of the test folder does not allow symbolic links; runs on the Linux CI or the Windows CI (CI에서 실행)");

        assertRefused(store::stored, ArtifactErrorCode.ARTIFACT_STORAGE_UNSAFE);
        assertRefused(() -> store.length(id), ArtifactErrorCode.ARTIFACT_STORAGE_UNSAFE);
        assertThat(Files.isSymbolicLink(dangling)).isTrue();
    }

    @Test
    @DisplayName("S-D0117-01: a payload and a root folder that others can use are used as they are, and no right is changed")
    void widePayloadsAreUsed(@TempDir Path dir) throws IOException {
        FileArtifactPayloadStore store = storeIn(dir);
        ArtifactId id = ids.next();
        store.append(id, 0, new byte[] {1});
        Path root = dir.resolve("artifacts");
        Path file = root.resolve(id.value() + ".part");
        TestStorage.openUp(root);
        TestStorage.openUp(file);

        assertThat(store.append(id, 1, new byte[] {2}).storedBytes()).isEqualTo(2);
        assertThat(read(store, id)).isEqualTo(new byte[] {1, 2});
        assertThat(store.stored()).containsExactly(id);
    }

    @Test
    @DisplayName("RC-A-167: a payload without metadata is moved to the orphans folder with every byte; a second one of the same id gets its own name; delete never touches the orphans")
    void aQuarantinedPayloadKeepsItsBytes(@TempDir Path dir) throws IOException {
        FileArtifactPayloadStore store = storeIn(dir);
        ArtifactId id = new ArtifactId("f".repeat(48));
        byte[] legacy = fixtures.bytes(48, 'f');
        Path orphans = dir.resolve("artifacts").resolve("orphans");

        store.append(id, 0, legacy);
        store.quarantine(id);

        assertThat(store.stored()).isEmpty();
        assertThat(Files.readAllBytes(orphans.resolve(id.value() + ".part"))).isEqualTo(legacy);

        byte[] second = fixtures.bytes(10, 'g');
        store.append(id, 0, second);
        store.quarantine(id);
        store.delete(id);
        assertThat(Files.readAllBytes(orphans.resolve(id.value() + ".part"))).isEqualTo(legacy);
        assertThat(Files.readAllBytes(orphans.resolve(id.value() + ".1.part"))).isEqualTo(second);
    }

    @Test
    @DisplayName("RC-A-169 (file half): quarantined bytes keep their content and their modification time through every later operation of the store")
    void quarantinedBytesAreNotRewritten(@TempDir Path dir) throws IOException {
        FileArtifactPayloadStore store = storeIn(dir);
        ArtifactId orphan = ids.next();
        ArtifactId other = ids.next();
        store.append(orphan, 0, new byte[] {1});
        store.quarantine(orphan);
        Path kept = dir.resolve("artifacts").resolve("orphans").resolve(orphan.value() + ".part");
        byte[] before = Files.readAllBytes(kept);
        var modified = Files.getLastModifiedTime(kept);

        store.append(other, 0, new byte[] {2, 3});
        store.truncate(other, 1);
        store.stored();
        store.delete(other);
        store.usable();

        assertThat(Files.readAllBytes(kept)).isEqualTo(before);
        assertThat(Files.getLastModifiedTime(kept)).isEqualTo(modified);
    }

    @Test
    @DisplayName("RC-B-063 (file half): after appends, a quarantine and a delete, the folder holds regular files only (no link), each with one name and, where POSIX permissions exist, private to the owner")
    void theFolderStaysClean(@TempDir Path dir) throws IOException {
        FileArtifactPayloadStore store = storeIn(dir);
        ArtifactId kept = ids.next();
        ArtifactId orphan = ids.next();
        ArtifactId removed = ids.next();
        store.append(kept, 0, new byte[] {1, 2, 3});
        store.append(orphan, 0, new byte[] {4});
        store.append(removed, 0, new byte[] {5});
        store.quarantine(orphan);
        store.delete(removed);

        Path root = dir.resolve("artifacts");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(path -> !path.equals(root)).forEach(files::add);
        }
        assertThat(files).hasSize(3);
        boolean posix = Files.getFileStore(root).supportsFileAttributeView(POSIX_VIEW);
        boolean counted = Files.getFileStore(root).supportsFileAttributeView(UNIX_VIEW);
        for (Path path : files) {
            assertThat(Files.isSymbolicLink(path)).isFalse();
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                if (counted) {
                    assertThat(Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).isEqualTo(1);
                }
                if (posix) {
                    assertThat(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).stream().map(Enum::name).filter(name -> !name.startsWith("OWNER")).toList())
                        .isEmpty();
                }
            } else {
                assertThat(path.getFileName().toString()).isEqualTo("orphans");
            }
        }
        assertThat(store.stored()).containsExactly(kept);
    }

    @Test
    @DisplayName("RC-A-164 (file half): a second writer of the same file is refused as ARTIFACT_BUSY while another holder has it locked, and the file is not changed")
    void aLockedFileIsBusy(@TempDir Path dir) throws IOException {
        FileArtifactPayloadStore store = storeIn(dir);
        ArtifactId id = ids.next();
        store.append(id, 0, new byte[] {1, 2});
        Path file = dir.resolve("artifacts").resolve(id.value() + ".part");

        try (FileChannel holder = FileChannel.open(file, StandardOpenOption.WRITE); FileLock lock = holder.lock()) {
            assertThat(lock.isValid()).isTrue();
            assertRefused(() -> store.append(id, 2, new byte[] {3}), ArtifactErrorCode.ARTIFACT_BUSY);
            assertRefused(() -> store.truncate(id, 1), ArtifactErrorCode.ARTIFACT_BUSY);
        }

        assertThat(store.length(id)).isEqualTo(2);
        assertThat(store.append(id, 2, new byte[] {3}).storedBytes()).isEqualTo(3);
    }

    private FileArtifactPayloadStore storeIn(Path dir) {
        return new FileArtifactPayloadStore(new StorageRoots(dir.resolve("artifacts"), dir.resolve("outputs")), TestStorage.rules());
    }

    private byte[] read(FileArtifactPayloadStore store, ArtifactId id) throws IOException {
        try (PayloadRead read = store.open(id)) {
            return Channels.newInputStream(read.channel()).readAllBytes();
        }
    }

    /** Tries to make {@code link} a symbolic link to {@code target}; false (logged) where the file store or the user may not. */
    private boolean canLink(Path dir, Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            LOG.info("Symbolic links are not available in {}", dir, e);
            return false;
        }
    }

    private void assertRefused(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(RequestRefusedException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
