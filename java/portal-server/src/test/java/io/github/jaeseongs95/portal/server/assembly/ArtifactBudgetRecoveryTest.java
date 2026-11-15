package io.github.jaeseongs95.portal.server.assembly;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
import io.github.jaeseongs95.portal.app.artifact.ChunkDigest;
import io.github.jaeseongs95.portal.app.artifact.QuarantineNotice;
import io.github.jaeseongs95.portal.app.artifact.ReleaseReason;
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
import io.github.jaeseongs95.portal.app.storage.ReapGate;
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

    /** The line the owner reads: the number of files in quarantine and their bytes (the same text as the start-up check's). */
    private static final String QUARANTINE_LINE = "Artifact quarantine: %d file(s), %d retained byte(s) (not charged to the staging budget, only the owner clears them)";

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
    private final ReapGate gate;
    private final QuarantineNotice quarantine;
    private final List<ListAppender<ILoggingEvent>> appenders = new ArrayList<>();

    ArtifactBudgetRecoveryTest(ArtifactRegistry registry, ArtifactPayloadStore payloads, ArtifactReaper reaper, DownloadService downloads, StorageRoots roots, FakeRemote remote,
                               FakeTargets targets, TransferSupport support, TransferOutcomes outcomes, TransferLimits limits, StagingTimes times, Digester digester,
                               JdbcTemplate jdbc, Clock clock, ReapGate gate, QuarantineNotice quarantine) {
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
        this.gate = gate;
        this.quarantine = quarantine;
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
    @DisplayName("S-D0012-01 (D-0012): a download holds only the size its file announced: twelve downloads that overlap are all stored, although twelve full reservations (12 x 10,000) are more than the 100,000 byte budget")
    void overlappingDownloadsHoldOnlyTheSizeTheirFilesAnnounced() {
        int downloadsToOverlap = 12;
        remote.served = new byte[1_000];
        List<DownloadExecution> executions = new ArrayList<>();
        int[] started = {0};
        long[] chargedWhenAllAreOpen = {-1L};
        // Each download, once the size of its file is announced, starts the next one before it takes its own first block; the last one reads the budget.
        remote.whileTransferring = () -> {
            started[0]++;
            if (started[0] < downloadsToOverlap) {
                executions.add(downloads.download(downloadJob(), CONTROL));
            } else {
                chargedWhenAllAreOpen[0] = registry.budget().chargedBytes();
            }
        };

        executions.add(downloads.download(downloadJob(), CONTROL));

        assertThat(executions).as("none of the twelve is refused as over quota").hasSize(downloadsToOverlap).allSatisfy(execution -> assertThat(execution).isInstanceOf(DownloadExecution.Stored.class));
        assertThat(chargedWhenAllAreOpen[0]).as("while all twelve are open each holds 1,000 bytes, not the 10,000 of the largest file").isEqualTo(12_000L);
        assertThat(registry.budget().chargedBytes()).as("and each is charged its real size when it is ready").isEqualTo(12_000L);
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

    @Test
    @DisplayName("S-D0012-04 (D-0012): a discard that finishes between the reap's list and its removal is not a failure: the row is gone, so the run counts it as removed, reports no failure and logs no warning")
    void aDiscardThatFinishesBetweenTheListAndTheRemovalIsNotAFailure() {
        ArtifactId discarding = aRowThatAFailedDiscardLeftDeleting();
        RegistryProbe probe = new RegistryProbe();
        // The reap has listed the row; now the discard that is still running finishes (the bytes go, the row is released) before the reap takes the row.
        probe.afterDeletingIsListed = () -> {
            payloads.delete(discarding);
            registry.release(discarding, ReleaseReason.REFUSED);
        };
        ArtifactReaper racing = new ArtifactReaper(probe.registry, payloads, gate, clock, quarantine);
        List<ILoggingEvent> logged = captureReaperLog();

        ReapReport report = racing.reap();

        assertThat(report).as("the row is gone, so it is counted as removed and not as a failure").isEqualTo(new ReapReport.Reaped(1, 0));
        assertThat(logged).as("no warning: nothing stays until the next run").noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN));
        assertThat(probe.reasons).as("the reap released nothing itself, the discard did").isEmpty();
        assertThat(registry.holding()).isEmpty();
        assertThat(registry.budget().chargedBytes()).isZero();
    }

    @Test
    @DisplayName("S-D0012-04 (D-0012): the code that is taken as 'already removed' is ARTIFACT_NOT_FOUND alone: any other refusal of the removal is still a failure with its warning")
    void anyOtherRefusalOfTheRemovalIsStillAFailure() {
        ArtifactId discarding = aRowThatAFailedDiscardLeftDeleting();
        RegistryProbe probe = new RegistryProbe();
        probe.beginDeleteRefusal = new RequestRefusedException(ArtifactErrorCode.ARTIFACT_CHANGED);
        ArtifactReaper reaperOfConflicts = new ArtifactReaper(probe.registry, payloads, gate, clock, quarantine);
        List<ILoggingEvent> logged = captureReaperLog();

        ReapReport report = reaperOfConflicts.reap();

        assertThat(report).isEqualTo(new ReapReport.Reaped(0, 1));
        assertThat(logged).as("the failure is warned about, with its cause").anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getThrowableProxy()).isNotNull();
        });
        assertThat(registry.find(discarding)).as("the row stays for the next run").isPresent();
    }

    @Test
    @DisplayName("S-D0012-05 (D-0012): the retry of a row left DELETING is released as ABANDONED, not as EXPIRED, because the row has not expired; a row that did expire is still EXPIRED")
    void aRowLeftDeletingIsNotReleasedAsExpired() {
        ArtifactId left = aRowThatAFailedDiscardLeftDeleting();
        ArtifactId old = expiredArtifact(new byte[300]);
        RegistryProbe probe = new RegistryProbe();
        ArtifactReaper recordingReaper = new ArtifactReaper(probe.registry, payloads, gate, clock, quarantine);

        ReapReport report = recordingReaper.reap();

        assertThat(report).isEqualTo(new ReapReport.Reaped(2, 0));
        assertThat(probe.reasons).as("one release per row, with the reason of each").containsEntry(left, ReleaseReason.ABANDONED).containsEntry(old, ReleaseReason.EXPIRED).hasSize(2);
        assertThat(registry.holding()).isEmpty();
    }

    @Test
    @DisplayName("S-D0012-07 (D-0012): every reap run that happens tells the owner, in one INFO line, how many files are in quarantine and how many bytes they hold")
    void everyReapRunLogsTheQuarantineFigures() throws IOException {
        byte[] held = new byte[150_000];
        payloads.append(LEGACY, 0, held);
        payloads.quarantine(LEGACY);
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            ReapReport report = reaper.reap();

            assertThat(report).isInstanceOf(ReapReport.Reaped.class);
            assertThat(appender.list).filteredOn(event -> event.getLevel() == Level.INFO).extracting(ILoggingEvent::getFormattedMessage)
                .as("one file of 150,000 bytes in quarantine").contains(QUARANTINE_LINE.formatted(1, 150_000));
        } finally {
            root.detachAppender(appender);
        }
    }

    // ---- helpers ----

    /** A download whose discard failed: the payload store cannot delete, so the row stays {@code DELETING} and charged (the state S-D0012-02 starts from). */
    private ArtifactId aRowThatAFailedDiscardLeftDeleting() {
        UndeletablePayloads locked = new UndeletablePayloads(payloads);
        DownloadService service = new DownloadService(registry, locked, remote, support, outcomes, limits, times, digester, clock);
        remote.downloadAnswer = new DownloadResult.Refused(TransferErrorCode.PERMISSION_DENIED, new FileDiagnostic.SftpStatus(3));
        assertThat(service.download(downloadJob(), CONTROL)).isInstanceOf(DownloadExecution.NotStored.class);
        List<ArtifactRecord> holding = registry.holding();
        assertThat(holding).extracting(ArtifactRecord::state).containsExactly(ArtifactState.DELETING);
        return holding.getFirst().id();
    }

    private ArtifactId expiredArtifact(byte[] bytes) {
        ArtifactSpec expired = new ArtifactSpec(ArtifactDirection.UPLOAD, new FileName("old.bin"), bytes.length, new ArtifactOrigin.Transfer(), new ArtifactDestination.Unassigned(),
            OverwriteMode.REPLACE, Elevation.NONE, UploadProtocol.UNSPECIFIED, ArtifactRetention.RELEASABLE, Duration.ofNanos(1_000));
        ArtifactId id = ((ArtifactReservation.Reserved) registry.reserve(expired, clock.instant())).id();
        payloads.append(id, 0, bytes);
        registry.append(id, 0, new ChunkDigest(bytes.length, digester.digest(bytes)));
        registry.markReady(id, digester.digest(bytes));
        return id;
    }

    /**
     * The real registry (the rows and the budget are real) behind a {@link Proxy} that adds what a test needs and a real run cannot give on cue: a step that runs right after
     * the list of {@code DELETING} rows was taken (the other thread finishing its discard at exactly that point, no sleep), a refusal of {@code beginDelete}, and the reason of
     * every release (the registry only logs it).
     */
    private final class RegistryProbe {

        Runnable afterDeletingIsListed = () -> { };
        RuntimeException beginDeleteRefusal;
        final Map<ArtifactId, ReleaseReason> reasons = new LinkedHashMap<>();
        final ArtifactRegistry registry = (ArtifactRegistry) Proxy.newProxyInstance(ArtifactRegistry.class.getClassLoader(), new Class<?>[] {ArtifactRegistry.class},
            (proxy, method, args) -> {
                if (method.getName().equals("beginDelete") && beginDeleteRefusal != null) {
                    throw beginDeleteRefusal;
                }
                if (method.getName().equals("release")) {
                    reasons.put((ArtifactId) args[0], (ReleaseReason) args[1]);
                }
                Object answer;
                try {
                    answer = method.invoke(ArtifactBudgetRecoveryTest.this.registry, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
                if (method.getName().equals("deleting")) {
                    afterDeletingIsListed.run();
                }
                return answer;
            });
    }

    /** The events {@link ArtifactReaper} logs from now on; the appender is detached by {@link #detachTheReaperLog()}. */
    private List<ILoggingEvent> captureReaperLog() {
        Logger reaperLog = (Logger) LoggerFactory.getLogger(ArtifactReaper.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        reaperLog.addAppender(appender);
        appenders.add(appender);
        return appender.list;
    }

    @AfterEach
    void detachTheReaperLog() {
        Logger reaperLog = (Logger) LoggerFactory.getLogger(ArtifactReaper.class);
        appenders.forEach(reaperLog::detachAppender);
        appenders.clear();
    }

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
