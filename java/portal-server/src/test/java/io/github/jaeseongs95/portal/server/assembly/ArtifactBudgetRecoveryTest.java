package io.github.jaeseongs95.portal.server.assembly;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.jaeseongs95.portal.app.artifact.ArtifactDestination;
import io.github.jaeseongs95.portal.app.artifact.ArtifactDirection;
import io.github.jaeseongs95.portal.app.artifact.ArtifactOrigin;
import io.github.jaeseongs95.portal.app.artifact.ArtifactPayloadStore;
import io.github.jaeseongs95.portal.app.artifact.ArtifactReaper;
import io.github.jaeseongs95.portal.app.artifact.ArtifactRecord;
import io.github.jaeseongs95.portal.app.artifact.ArtifactRegistry;
import io.github.jaeseongs95.portal.app.artifact.ArtifactReservation;
import io.github.jaeseongs95.portal.app.artifact.ArtifactRetention;
import io.github.jaeseongs95.portal.app.artifact.ArtifactSpec;
import io.github.jaeseongs95.portal.app.artifact.ArtifactState;
import io.github.jaeseongs95.portal.app.artifact.StagingTimes;
import io.github.jaeseongs95.portal.app.file.DownloadExecution;
import io.github.jaeseongs95.portal.app.file.DownloadJob;
import io.github.jaeseongs95.portal.app.file.DownloadService;
import io.github.jaeseongs95.portal.app.file.TransferOutcomes;
import io.github.jaeseongs95.portal.app.file.TransferSupport;
import io.github.jaeseongs95.portal.app.remote.CancelSource;
import io.github.jaeseongs95.portal.app.remote.DownloadResult;
import io.github.jaeseongs95.portal.app.remote.ExecutionControl;
import io.github.jaeseongs95.portal.app.remote.FileDiagnostic;
import io.github.jaeseongs95.portal.app.remote.TransferLimits;
import io.github.jaeseongs95.portal.app.storage.DiskSpace;
import io.github.jaeseongs95.portal.app.storage.PayloadRead;
import io.github.jaeseongs95.portal.app.storage.PayloadWrite;
import io.github.jaeseongs95.portal.app.storage.ReapReport;
import io.github.jaeseongs95.portal.domain.error.ArtifactErrorCode;
import io.github.jaeseongs95.portal.domain.error.RequestRefusedException;
import io.github.jaeseongs95.portal.domain.error.TransferErrorCode;
import io.github.jaeseongs95.portal.domain.file.FileBinding;
import io.github.jaeseongs95.portal.domain.file.FileName;
import io.github.jaeseongs95.portal.domain.file.FileTargetBinding;
import io.github.jaeseongs95.portal.domain.id.ArtifactId;
import io.github.jaeseongs95.portal.domain.id.Digester;
import io.github.jaeseongs95.portal.domain.operation.DownloadInput;
import io.github.jaeseongs95.portal.domain.operation.Elevation;
import io.github.jaeseongs95.portal.domain.operation.OverwriteMode;
import io.github.jaeseongs95.portal.domain.operation.UploadProtocol;
import io.github.jaeseongs95.portal.domain.path.PosixRemotePath;
import io.github.jaeseongs95.portal.domain.target.ResolvedTarget;
import io.github.jaeseongs95.portal.domain.text.Utf8Text;
import io.github.jaeseongs95.portal.store.StorageRoots;

/**
 * Why the artifact budget filled without the owner's knowledge (defect D-0012) and what keeps it from filling again: a row that a failed discard left {@code DELETING} is removed
 * by the next reap, and its charge is given back. The artifact store is the real one (H2 and real files); the SSH boundary is {@link FakeRemote}.
 *
 * <p>The failed discard is made by a payload store that cannot delete: {@link DownloadService} then does what it does for a real disk error (the row is {@code DELETING}, the
 * failure is logged). No mock is used (Principles 7.1); the store is a plain class that stands for a locked or unreadable file.
 */
@AssemblyTest
class ArtifactBudgetRecoveryTest {

    private static final ExecutionControl CONTROL = new ExecutionControl(new CancelSource(), () -> { });
    private static final ArtifactId LEGACY = new ArtifactId("c".repeat(48));

    private final ArtifactRegistry registry;
    private final ArtifactPayloadStore payloads;
    private final ArtifactReaper reaper;
    private final DownloadService downloads;
    private final StorageRoots roots;
    private final FakeRemote remote;
    private final FakeTargets targets;
    private final TransferSupport support;
    private final TransferOutcomes outcomes;
    private final TransferLimits limits;
    private final StagingTimes times;
    private final Digester digester;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    ArtifactBudgetRecoveryTest(ArtifactRegistry registry, ArtifactPayloadStore payloads, ArtifactReaper reaper, DownloadService downloads, StorageRoots roots, FakeRemote remote,
                               FakeTargets targets, TransferSupport support, TransferOutcomes outcomes, TransferLimits limits, StagingTimes times, Digester digester,
                               JdbcTemplate jdbc, Clock clock) {
        this.registry = registry;
        this.payloads = payloads;
        this.reaper = reaper;
        this.downloads = downloads;
        this.roots = roots;
        this.remote = remote;
        this.targets = targets;
        this.support = support;
        this.outcomes = outcomes;
        this.limits = limits;
        this.times = times;
        this.digester = digester;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @BeforeEach
    void clean() {
        remote.reset();
        targets.reset();
        jdbc.update("DELETE FROM staged_action");
        jdbc.update("DELETE FROM artifact_chunk");
        jdbc.update("DELETE FROM artifact");
    }

    /** The quarantine is the one place the store never deletes from; the test removes what it put there, so the shared context sees none of it. */
    @AfterEach
    void removeTheQuarantinedFile() throws IOException {
        Files.deleteIfExists(quarantined(LEGACY));
    }

    @Test
    @DisplayName("S-D0012-03 (D-0012): bytes in quarantine do not charge the staging budget: with more of them than the whole budget an upload and a download are still accepted, and a reap keeps them")
    void quarantinedBytesNeitherBlockANewTransferNorGetDeleted() throws IOException {
        byte[] held = new byte[150_000];
        Arrays.fill(held, (byte) 7);
        payloads.append(LEGACY, 0, held);
        payloads.quarantine(LEGACY);
        assertThat(Files.size(quarantined(LEGACY))).as("more than the whole 100,000 byte budget is in quarantine").isEqualTo(150_000);

        ArtifactReservation upload = registry.reserve(uploadSpec(1_090), clock.instant());
        remote.served = new byte[850];
        DownloadExecution download = downloads.download(downloadJob(), CONTROL);

        assertThat(upload).as("a 1090 byte upload is not refused as over quota").isInstanceOf(ArtifactReservation.Reserved.class);
        assertThat(download).isInstanceOf(DownloadExecution.Stored.class);
        assertThat(registry.budget().chargedBytes()).as("the budget counts the two transfers only").isEqualTo(1_090 + 850);
        assertThat(reaper.reap()).isInstanceOf(ReapReport.Reaped.class);
        assertThat(Files.readAllBytes(quarantined(LEGACY))).as("nothing deletes the quarantined bytes").isEqualTo(held);
    }

    @Test
    @DisplayName("S-D0012-02 (D-0012): a download whose discard failed leaves its row DELETING and charged; the next reap removes the row, and the budget is free again, although the row has not expired")
    void aRowThatAFailedDiscardLeftDeletingIsRemovedByTheNextReap() {
        UndeletablePayloads locked = new UndeletablePayloads(payloads);
        DownloadService service = new DownloadService(registry, locked, remote, support, outcomes, limits, times, digester, clock);
        remote.downloadAnswer = new DownloadResult.Refused(TransferErrorCode.PERMISSION_DENIED, new FileDiagnostic.SftpStatus(3));

        DownloadExecution execution = service.download(downloadJob(), CONTROL);

        assertThat(execution).isInstanceOf(DownloadExecution.NotStored.class);
        assertThat(locked.deleteCalls).as("the discard tried to delete the bytes").isEqualTo(1);
        assertThat(registry.holding()).as("the row stays").extracting(ArtifactRecord::state).containsExactly(ArtifactState.DELETING);
        assertThat(registry.budget().chargedBytes()).as("and keeps its charge").isPositive();

        ReapReport report = reaper.reap();

        assertThat(report).isEqualTo(new ReapReport.Reaped(1, 0));
        assertThat(registry.holding()).as("the next reap removed the row").isEmpty();
        assertThat(registry.budget().chargedBytes()).as("and the budget is free again").isZero();
    }

    // ---- helpers ----

    private Path quarantined(ArtifactId id) {
        return roots.artifacts().resolve("orphans").resolve(id.value() + ".part");
    }

    private ArtifactSpec uploadSpec(long size) {
        return new ArtifactSpec(ArtifactDirection.UPLOAD, new FileName("a.bin"), size, new ArtifactOrigin.Transfer(), new ArtifactDestination.Unassigned(), OverwriteMode.REPLACE,
            Elevation.NONE, UploadProtocol.UNSPECIFIED, ArtifactRetention.RELEASABLE, Duration.ofMinutes(10));
    }

    private DownloadJob downloadJob() {
        ResolvedTarget linux = targets.linux();
        return new DownloadJob(linux, linux.revision(), new DownloadInput(new PosixRemotePath("/var/log/result.bin"), Elevation.NONE,
            new FileBinding.Bound(new FileTargetBinding(FakeTargets.LINUX_ID, linux.revision())), new Utf8Text("caller"), new Utf8Text("display")));
    }

    /** A payload store whose files cannot be deleted (a locked file, a disk that fails); everything else is the real store's. */
    private static final class UndeletablePayloads implements ArtifactPayloadStore {

        private final ArtifactPayloadStore real;
        int deleteCalls;

        UndeletablePayloads(ArtifactPayloadStore real) {
            this.real = real;
        }

        @Override
        public PayloadWrite append(ArtifactId id, long offset, byte[] data) {
            return real.append(id, offset, data);
        }

        @Override
        public void truncate(ArtifactId id, long length) {
            real.truncate(id, length);
        }

        @Override
        public long length(ArtifactId id) {
            return real.length(id);
        }

        @Override
        public PayloadRead open(ArtifactId id) {
            return real.open(id);
        }

        @Override
        public void delete(ArtifactId id) {
            deleteCalls++;
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_STORAGE_IO_ERROR);
        }

        @Override
        public void quarantine(ArtifactId id) {
            real.quarantine(id);
        }

        @Override
        public Set<ArtifactId> stored() {
            return real.stored();
        }

        @Override
        public DiskSpace usable() {
            return real.usable();
        }
    }
}
