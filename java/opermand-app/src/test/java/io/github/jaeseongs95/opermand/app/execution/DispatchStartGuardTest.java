package io.github.jaeseongs95.opermand.app.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.scheduling.TaskScheduler;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.jaeseongs95.opermand.app.artifact.ArtifactDestination;
import io.github.jaeseongs95.opermand.app.artifact.ArtifactDirection;
import io.github.jaeseongs95.opermand.app.artifact.ArtifactOrigin;
import io.github.jaeseongs95.opermand.app.artifact.ArtifactPayloadStore;
import io.github.jaeseongs95.opermand.app.artifact.ArtifactProvenance;
import io.github.jaeseongs95.opermand.app.artifact.ArtifactRecord;
import io.github.jaeseongs95.opermand.app.artifact.ArtifactRegistry;
import io.github.jaeseongs95.opermand.app.artifact.ArtifactReservation;
import io.github.jaeseongs95.opermand.app.artifact.ArtifactRetention;
import io.github.jaeseongs95.opermand.app.artifact.ArtifactSpec;
import io.github.jaeseongs95.opermand.app.artifact.ArtifactState;
import io.github.jaeseongs95.opermand.app.artifact.ArtifactSubmission;
import io.github.jaeseongs95.opermand.app.artifact.BindingKind;
import io.github.jaeseongs95.opermand.app.artifact.ConsumeState;
import io.github.jaeseongs95.opermand.app.artifact.ContentDigest;
import io.github.jaeseongs95.opermand.app.artifact.StagingTimes;
import io.github.jaeseongs95.opermand.app.audit.AuditAction;
import io.github.jaeseongs95.opermand.app.audit.AuditEvent;
import io.github.jaeseongs95.opermand.app.event.OperationEvents;
import io.github.jaeseongs95.opermand.app.file.DownloadService;
import io.github.jaeseongs95.opermand.app.file.FilePermission;
import io.github.jaeseongs95.opermand.app.file.TransferOutcomes;
import io.github.jaeseongs95.opermand.app.file.TransferSupport;
import io.github.jaeseongs95.opermand.app.file.UploadJob;
import io.github.jaeseongs95.opermand.app.file.UploadService;
import io.github.jaeseongs95.opermand.app.history.ExecutionTimelineStore;
import io.github.jaeseongs95.opermand.app.journal.ClaimResult;
import io.github.jaeseongs95.opermand.app.journal.ClaimStore;
import io.github.jaeseongs95.opermand.app.journal.JournalInsert;
import io.github.jaeseongs95.opermand.app.journal.OperationJournal;
import io.github.jaeseongs95.opermand.app.journal.OperationQueueView;
import io.github.jaeseongs95.opermand.app.journal.TargetFilter;
import io.github.jaeseongs95.opermand.app.maintenance.MaintenanceStore;
import io.github.jaeseongs95.opermand.app.operation.ApprovalJudge;
import io.github.jaeseongs95.opermand.app.operation.AuditGate;
import io.github.jaeseongs95.opermand.app.operation.ExpiryTimers;
import io.github.jaeseongs95.opermand.app.operation.MaintenanceView;
import io.github.jaeseongs95.opermand.app.operation.OperationExpiry;
import io.github.jaeseongs95.opermand.app.operation.OperationWrites;
import io.github.jaeseongs95.opermand.app.operation.OutcomeAudit;
import io.github.jaeseongs95.opermand.app.operation.RunningExecutions;
import io.github.jaeseongs95.opermand.app.operation.TerminalNotifier;
import io.github.jaeseongs95.opermand.app.operation.TerminalOperationListener;
import io.github.jaeseongs95.opermand.app.policy.CommandPolicyStore;
import io.github.jaeseongs95.opermand.app.remote.DownloadRequest;
import io.github.jaeseongs95.opermand.app.remote.DownloadResult;
import io.github.jaeseongs95.opermand.app.remote.ElevationPlan;
import io.github.jaeseongs95.opermand.app.remote.ElevationRequest;
import io.github.jaeseongs95.opermand.app.remote.ElevationResult;
import io.github.jaeseongs95.opermand.app.remote.ExecutionControl;
import io.github.jaeseongs95.opermand.app.remote.ExecutionReport;
import io.github.jaeseongs95.opermand.app.remote.ExecutionTimeline;
import io.github.jaeseongs95.opermand.app.remote.FileMode;
import io.github.jaeseongs95.opermand.app.remote.PathPolicyBinding;
import io.github.jaeseongs95.opermand.app.remote.PayloadSink;
import io.github.jaeseongs95.opermand.app.remote.PayloadSource;
import io.github.jaeseongs95.opermand.app.remote.PermissionRequest;
import io.github.jaeseongs95.opermand.app.remote.PermissionResult;
import io.github.jaeseongs95.opermand.app.remote.PermissionSupport;
import io.github.jaeseongs95.opermand.app.remote.RemoteFileTransfer;
import io.github.jaeseongs95.opermand.app.remote.ReplaceMethod;
import io.github.jaeseongs95.opermand.app.remote.TransferLimits;
import io.github.jaeseongs95.opermand.app.remote.UnsupportedReason;
import io.github.jaeseongs95.opermand.app.remote.UploadRequest;
import io.github.jaeseongs95.opermand.app.remote.UploadResult;
import io.github.jaeseongs95.opermand.app.storage.PayloadRead;
import io.github.jaeseongs95.opermand.app.target.TargetPlatform;
import io.github.jaeseongs95.opermand.app.target.TargetPlatformRegistry;
import io.github.jaeseongs95.opermand.app.target.TargetSource;
import io.github.jaeseongs95.opermand.domain.approval.ApprovalPolicy;
import io.github.jaeseongs95.opermand.domain.capacity.Capacity;
import io.github.jaeseongs95.opermand.domain.command.CommandPolicyDocument;
import io.github.jaeseongs95.opermand.domain.command.PolicySnapshot;
import io.github.jaeseongs95.opermand.domain.error.DispatchFailure;
import io.github.jaeseongs95.opermand.domain.error.DispatchReason;
import io.github.jaeseongs95.opermand.domain.error.DispatchRefusedException;
import io.github.jaeseongs95.opermand.domain.error.DispatchStage;
import io.github.jaeseongs95.opermand.domain.error.OperationErrorCode;
import io.github.jaeseongs95.opermand.domain.error.RequestErrorCode;
import io.github.jaeseongs95.opermand.domain.error.RequestRefusedException;
import io.github.jaeseongs95.opermand.domain.file.FileBinding;
import io.github.jaeseongs95.opermand.domain.file.FileName;
import io.github.jaeseongs95.opermand.domain.file.FileTargetBinding;
import io.github.jaeseongs95.opermand.domain.id.ArtifactId;
import io.github.jaeseongs95.opermand.domain.id.Digest;
import io.github.jaeseongs95.opermand.domain.id.OperationId;
import io.github.jaeseongs95.opermand.domain.id.OwnerId;
import io.github.jaeseongs95.opermand.domain.id.RequestKey;
import io.github.jaeseongs95.opermand.domain.id.Sha256Digester;
import io.github.jaeseongs95.opermand.domain.id.TargetId;
import io.github.jaeseongs95.opermand.domain.operation.Admission;
import io.github.jaeseongs95.opermand.domain.operation.CommandInput;
import io.github.jaeseongs95.opermand.domain.operation.CompletionEvidence;
import io.github.jaeseongs95.opermand.domain.operation.ConflictKey;
import io.github.jaeseongs95.opermand.domain.operation.Deadline;
import io.github.jaeseongs95.opermand.domain.operation.DecisionReason;
import io.github.jaeseongs95.opermand.domain.operation.DownloadInput;
import io.github.jaeseongs95.opermand.domain.operation.Elevation;
import io.github.jaeseongs95.opermand.domain.operation.ExecutionOutcome;
import io.github.jaeseongs95.opermand.domain.operation.NewOperation;
import io.github.jaeseongs95.opermand.domain.operation.Operation;
import io.github.jaeseongs95.opermand.domain.operation.OperationEvent;
import io.github.jaeseongs95.opermand.domain.operation.OperationInput;
import io.github.jaeseongs95.opermand.domain.operation.OperationKind;
import io.github.jaeseongs95.opermand.domain.operation.OperationState;
import io.github.jaeseongs95.opermand.domain.operation.OverwriteMode;
import io.github.jaeseongs95.opermand.domain.operation.Reconciliation;
import io.github.jaeseongs95.opermand.domain.operation.RecordedResult;
import io.github.jaeseongs95.opermand.domain.operation.RemoteState;
import io.github.jaeseongs95.opermand.domain.operation.Settlement;
import io.github.jaeseongs95.opermand.domain.operation.StartAuthority;
import io.github.jaeseongs95.opermand.domain.operation.StateCounts;
import io.github.jaeseongs95.opermand.domain.operation.UploadInput;
import io.github.jaeseongs95.opermand.domain.operation.UploadProtocol;
import io.github.jaeseongs95.opermand.domain.path.PosixRemotePath;
import io.github.jaeseongs95.opermand.domain.schedule.ConcurrencyLimits;
import io.github.jaeseongs95.opermand.domain.schedule.OperationScheduler;
import io.github.jaeseongs95.opermand.domain.schedule.QueuedOperation;
import io.github.jaeseongs95.opermand.domain.schedule.SchedulingView;
import io.github.jaeseongs95.opermand.domain.target.AccessMode;
import io.github.jaeseongs95.opermand.domain.target.AuthMethod;
import io.github.jaeseongs95.opermand.domain.target.CapacityOverride;
import io.github.jaeseongs95.opermand.domain.target.ConnectionProfile;
import io.github.jaeseongs95.opermand.domain.target.DesktopSettings;
import io.github.jaeseongs95.opermand.domain.target.ProfileAlias;
import io.github.jaeseongs95.opermand.domain.target.ResolvedTarget;
import io.github.jaeseongs95.opermand.domain.target.Setting;
import io.github.jaeseongs95.opermand.domain.target.Target;
import io.github.jaeseongs95.opermand.domain.target.TargetDisplayName;
import io.github.jaeseongs95.opermand.domain.target.TargetLookup;
import io.github.jaeseongs95.opermand.domain.target.TargetOs;
import io.github.jaeseongs95.opermand.domain.target.Toggle;
import io.github.jaeseongs95.opermand.domain.target.TtyMode;
import io.github.jaeseongs95.opermand.domain.target.WindowsRuntime;
import io.github.jaeseongs95.opermand.domain.text.CommandText;
import io.github.jaeseongs95.opermand.domain.text.Utf8Text;

/**
 * The per-execution start guard of {@link ExecutionDispatcher} and {@link DispatchChecks} across multi-step operations and post-start refusals (defect D-0149,
 * cases S-D0149-03, S-D0149-05 and S-D0149-06).
 */
class DispatchStartGuardTest {

    private static final Instant NOW = Instant.parse("2026-11-17T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Sha256Digester DIGESTER = new Sha256Digester();
    private static final TargetId TARGET_ID = new TargetId("linux-1");
    private static final ProfileAlias READ_PROFILE = new ProfileAlias("linux-ro");
    private static final ProfileAlias WRITE_PROFILE = new ProfileAlias("linux-rw");
    private static final ArtifactId UPLOAD_ARTIFACT = new ArtifactId("a".repeat(48));
    private static final ArtifactId DOWNLOAD_ARTIFACT = new ArtifactId("b".repeat(48));

    private final ResolvedTarget initialTarget = resolvedTarget("host-one.example");
    private final AtomicReference<ResolvedTarget> activeTarget = new AtomicReference<>(initialTarget);
    private final List<AuditEvent> audits = new ArrayList<>();
    private final MemJournal journal = new MemJournal();

    private Logger dispatcherLogger;
    private Logger downloadRunnerLogger;
    private ListAppender<ILoggingEvent> dispatcherLogs;
    private ListAppender<ILoggingEvent> downloadRunnerLogs;
    private int keySeq;

    @BeforeEach
    void captureLogs() {
        dispatcherLogger = (Logger) LoggerFactory.getLogger(ExecutionDispatcher.class);
        dispatcherLogs = new ListAppender<>();
        dispatcherLogs.start();
        dispatcherLogger.addAppender(dispatcherLogs);

        downloadRunnerLogger = (Logger) LoggerFactory.getLogger(DownloadOperationRunner.class);
        downloadRunnerLogs = new ListAppender<>();
        downloadRunnerLogs.start();
        downloadRunnerLogger.addAppender(downloadRunnerLogs);
    }

    @AfterEach
    void detachLogs() {
        dispatcherLogger.detachAppender(dispatcherLogs);
        downloadRunnerLogger.detachAppender(downloadRunnerLogs);
    }

    @Test
    @DisplayName("S-D0149-03a D-0149 an upload with explicit permission bits passes both guard calls (upload and applyPermissions), audits start once, marks RUNNING once and settles COMPLETED")
    void anExplicitPermissionUploadPassesBothGuardCallsAndCompletesOnce() {
        byte[] payload = "explicit-mode-upload".getBytes(StandardCharsets.UTF_8);
        Digest digest = DIGESTER.digest(payload);
        PosixRemotePath path = new PosixRemotePath("/etc/app/service.conf");
        UploadInput input = new UploadInput(UPLOAD_ARTIFACT, path, new FileName("service.conf"), payload.length, digest, OverwriteMode.REPLACE, Elevation.NONE,
            new FileBinding.Bound(new FileTargetBinding(TARGET_ID, initialTarget.revision())), UploadProtocol.UNSPECIFIED);
        OperationId id = journal.enqueueOwnerApproved(spec(WRITE_PROFILE, input));

        ArtifactRecord readyArtifact = new ArtifactRecord(UPLOAD_ARTIFACT,
            new ArtifactSpec(ArtifactDirection.UPLOAD, new FileName("service.conf"), payload.length, new ArtifactOrigin.Transfer(),
                new ArtifactDestination.Assigned(path, TARGET_ID, initialTarget.revision(), BindingKind.BOUND), OverwriteMode.REPLACE, Elevation.NONE,
                UploadProtocol.UNSPECIFIED, ArtifactRetention.RELEASABLE, Duration.ofMinutes(15)),
            ArtifactState.READY, payload.length, 1, new ContentDigest.Sealed(digest), new ArtifactSubmission.SubmittedAs(id),
            new ArtifactProvenance.NoProvenance(), new ConsumeState.NotConsumed(), 1L, NOW, NOW.plusSeconds(900));

        ArtifactRegistry registry = proxy(ArtifactRegistry.class, (method, args) -> "find".equals(method) ? Optional.of(readyArtifact) : null);
        ArtifactPayloadStore payloads = proxy(ArtifactPayloadStore.class,
            (method, args) -> "open".equals(method) ? new PayloadRead(Channels.newChannel(new ByteArrayInputStream(payload)), payload.length) : null);

        AtomicInteger uploadGuards = new AtomicInteger();
        AtomicInteger permissionGuards = new AtomicInteger();
        RemoteFileTransfer transfer = new RemoteFileTransfer() {
            @Override
            public UploadResult upload(UploadRequest request, PayloadSource source, ExecutionControl control) {
                control.guard().beforeDispatch();
                uploadGuards.incrementAndGet();
                return new UploadResult.Published(digest, NOW, ReplaceMethod.POSIX_RENAME);
            }

            @Override
            public DownloadResult download(DownloadRequest request, PayloadSink sink, ExecutionControl control) {
                throw new UnsupportedOperationException("not used");
            }

            @Override
            public PermissionResult applyPermissions(PermissionRequest request, ExecutionControl control) {
                control.guard().beforeDispatch();
                permissionGuards.incrementAndGet();
                return new PermissionResult.Applied();
            }

            @Override
            public ElevationResult checkElevation(ElevationRequest request, ExecutionControl control) {
                throw new UnsupportedOperationException("not used");
            }
        };

        TransferOutcomes outcomes = new TransferOutcomes();
        UploadService uploads = new UploadService(registry, payloads, transfer, new TransferSupport(platforms()), outcomes, TransferLimits.defaults());
        RunTargets runTargets = new RunTargets(targetSource());
        OperationRunner explicitUploadRunner = new OperationRunner() {
            @Override
            public OperationKind kind() {
                return OperationKind.UPLOAD;
            }

            @Override
            public ExecutionReport run(Operation operation, ExecutionControl control) {
                UploadJob job = new UploadJob(runTargets.of(operation), operation.targetRevision(), (UploadInput) operation.input(),
                    new FilePermission.Explicit(new FileMode(0640)));
                return new ExecutionReport(uploads.upload(job, control), ExecutionTimeline.empty());
            }
        };

        ExecutionDispatcher dispatcher = dispatcher(List.of(explicitUploadRunner));
        dispatcher.open();

        Operation done = journal.require(id);
        assertThat(done.state()).isEqualTo(OperationState.COMPLETED);
        assertThat(uploadGuards).hasValue(1);
        assertThat(permissionGuards).hasValue(1);
        assertThat(done.settlement()).isInstanceOfSatisfying(Settlement.Settled.class, settled -> assertThat(settled.result())
            .isInstanceOfSatisfying(RecordedResult.Executed.class, executed -> assertThat(executed.outcome().evidence())
                .isInstanceOf(CompletionEvidence.FilePublished.class)));
        assertThat(journal.events(id).stream().filter(event -> event.state() == OperationState.RUNNING).count()).isEqualTo(1);
        assertThat(audits.stream().map(AuditEvent::action).toList()).containsExactly(AuditAction.APPROVED, AuditAction.COMPLETED);
    }

    @Test
    @DisplayName("S-D0149-03b D-0149 a first-step guard refusal settles as refused before dispatch, never enters RUNNING, and repeats the exact refusal when called again on the same guard")
    void aFirstStepRefusalLeavesTheTargetUntouchedAndRepeatsOnTheSameGuard() {
        OperationId id = journal.enqueueOwnerApproved(spec(WRITE_PROFILE, new CommandInput(new CommandText("id"), new Utf8Text(""), new Utf8Text(""))));
        activeTarget.set(resolvedTarget("changed-host.example"));

        AtomicReference<DispatchRefusedException> firstRefusal = new AtomicReference<>();
        AtomicReference<DispatchRefusedException> secondRefusal = new AtomicReference<>();
        OperationRunner commandRunner = new OperationRunner() {
            @Override
            public OperationKind kind() {
                return OperationKind.COMMAND;
            }

            @Override
            public ExecutionReport run(Operation operation, ExecutionControl control) {
                try {
                    control.guard().beforeDispatch();
                } catch (DispatchRefusedException first) {
                    firstRefusal.set(first);
                    try {
                        control.guard().beforeDispatch();
                    } catch (DispatchRefusedException second) {
                        secondRefusal.set(second);
                    }
                    throw first;
                }
                throw new AssertionError("Expected beforeDispatch to refuse when target revision changed");
            }
        };

        ExecutionDispatcher dispatcher = dispatcher(List.of(commandRunner));
        dispatcher.open();

        Operation done = journal.require(id);
        assertThat(firstRefusal.get()).isNotNull();
        assertThat(secondRefusal.get()).as("a repeated call on the same guard rethrows the same initial refusal").isSameAs(firstRefusal.get());
        assertThat(done.state()).isEqualTo(OperationState.FAILED);
        assertThat(journal.events(id).stream().map(OperationEvent::state).toList()).doesNotContain(OperationState.RUNNING);
        assertThat(done.settlement()).isInstanceOfSatisfying(Settlement.Settled.class, settled -> assertThat(settled.result())
            .isEqualTo(new RecordedResult.Refused(DispatchFailure.refused(DispatchReason.TARGET_CHANGED, DispatchStage.TARGET_GUARD))));
        assertThat(audits.stream().map(AuditEvent::action).toList()).containsExactly(AuditAction.FAILED);
    }

    @Test
    @DisplayName("S-D0149-05 D-0149 a DispatchRefusedException thrown after the start guard passed settles a write operation as UNKNOWN both in RUNNING and after cancel moved it to TERMINATING")
    void aPostRunningDispatchRefusalInAWriteRunnerSettlesAsUnknownInsteadOfStayingRunningOrCancelled() {
        for (boolean cancelAfterStart : List.of(false, true)) {
            audits.clear();
            dispatcherLogs.list.clear();
            OperationId id = journal.enqueueOwnerApproved(spec(WRITE_PROFILE, new CommandInput(new CommandText("touch /srv/flag"), new Utf8Text(""), new Utf8Text(""))));
            DispatchRefusedException postStartRefusal = new DispatchRefusedException(DispatchFailure.refused(DispatchReason.POLICY_CHANGED, DispatchStage.POLICY_GUARD));

            OperationRunner runner = new OperationRunner() {
                @Override
                public OperationKind kind() {
                    return OperationKind.COMMAND;
                }

                @Override
                public ExecutionReport run(Operation operation, ExecutionControl control) {
                    control.guard().beforeDispatch();
                    if (cancelAfterStart) {
                        Operation running = journal.require(id);
                        journal.save(running.requestCancel(NOW), running.version());
                    }
                    throw postStartRefusal;
                }
            };

            ExecutionDispatcher dispatcher = dispatcher(List.of(runner));
            dispatcher.open();

            Operation done = journal.require(id);
            assertThat(done.state()).as("cancelAfterStart=%s", cancelAfterStart).isEqualTo(OperationState.UNKNOWN);
            assertThat(done.settlement()).isInstanceOfSatisfying(Settlement.Settled.class,
                settled -> assertThat(settled.reconciliation()).isEqualTo(Reconciliation.REQUIRED));
            List<ILoggingEvent> errors = dispatcherLogs.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.ERROR)).toList();
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).getThrowableProxy()).isNotNull();
            assertThat(errors.get(0).getThrowableProxy().getClassName()).isEqualTo(DispatchRefusedException.class.getName());
        }
    }

    @Test
    @DisplayName("S-D0149-06 D-0149 a DispatchRefusedException or RequestRefusedException thrown after a download started settles as a read-only local failure, discards the artifact and never becomes UNKNOWN or CANCELLED")
    void aPostRunningRefusalInDownloadOperationRunnerSettlesAsReadOnlyLocalFailure() {
        DispatchRefusedException dispatchRefusal = new DispatchRefusedException(DispatchFailure.refused(DispatchReason.REQUEST_STATE, DispatchStage.REQUEST_STATE));
        RequestRefusedException requestRefusal = new RequestRefusedException(RequestErrorCode.TARGET_CAPABILITY_UNSUPPORTED);

        for (RuntimeException failure : List.of(dispatchRefusal, requestRefusal)) {
            for (boolean cancelAfterStart : List.of(false, true)) {
                audits.clear();
                dispatcherLogs.list.clear();
                downloadRunnerLogs.list.clear();
                List<String> registryCalls = new ArrayList<>();
                List<String> payloadCalls = new ArrayList<>();

                DownloadInput input = new DownloadInput(new PosixRemotePath("/var/log/syslog"), Elevation.NONE,
                    new FileBinding.Bound(new FileTargetBinding(TARGET_ID, initialTarget.revision())), new Utf8Text("caller"), new Utf8Text("display"));
                OperationId id = journal.enqueueOwnerApproved(spec(READ_PROFILE, input));

                ArtifactRegistry registry = proxy(ArtifactRegistry.class, (method, args) -> {
                    if ("release".equals(method) && args != null && args.length == 2) {
                        registryCalls.add("release:" + args[1]);
                    } else {
                        registryCalls.add(method);
                    }
                    return "reserve".equals(method) ? new ArtifactReservation.Reserved(DOWNLOAD_ARTIFACT) : null;
                });
                ArtifactPayloadStore payloads = proxy(ArtifactPayloadStore.class, (method, args) -> {
                    payloadCalls.add(method);
                    return "usable".equals(method) ? new io.github.jaeseongs95.opermand.app.storage.DiskSpace(Long.MAX_VALUE) : null;
                });
                RemoteFileTransfer transfer = new RemoteFileTransfer() {
                    @Override
                    public UploadResult upload(UploadRequest request, PayloadSource source, ExecutionControl control) {
                        throw new UnsupportedOperationException("not used");
                    }

                    @Override
                    public DownloadResult download(DownloadRequest request, PayloadSink sink, ExecutionControl control) {
                        control.guard().beforeDispatch();
                        if (cancelAfterStart) {
                            Operation running = journal.require(id);
                            journal.save(running.requestCancel(NOW), running.version());
                        }
                        throw failure;
                    }

                    @Override
                    public PermissionResult applyPermissions(PermissionRequest request, ExecutionControl control) {
                        throw new UnsupportedOperationException("not used");
                    }

                    @Override
                    public ElevationResult checkElevation(ElevationRequest request, ExecutionControl control) {
                        throw new UnsupportedOperationException("not used");
                    }
                };

                TransferOutcomes outcomes = new TransferOutcomes();
                DownloadService service = new DownloadService(registry, payloads, transfer, new TransferSupport(platforms()), outcomes, TransferLimits.defaults(),
                    StagingTimes.defaults(), DIGESTER, CLOCK);
                DownloadOperationRunner downloadRunner = new DownloadOperationRunner(service, new RunTargets(targetSource()), outcomes);

                ExecutionDispatcher dispatcher = dispatcher(List.of(downloadRunner));
                dispatcher.open();

                Operation done = journal.require(id);
                String expectedPrefix = failure instanceof RequestRefusedException refused ? refused.code().name() + ":" : "LOCAL_IO_FAILED:";
                assertThat(done.state()).as("failure=%s cancelAfterStart=%s", failure.getClass().getSimpleName(), cancelAfterStart).isEqualTo(OperationState.FAILED);
                assertThat(done.settlement()).isInstanceOfSatisfying(Settlement.Settled.class, settled -> {
                    assertThat(settled.reconciliation()).isEqualTo(Reconciliation.NOT_REQUIRED);
                    assertThat(settled.result()).isInstanceOfSatisfying(RecordedResult.Executed.class, executed -> {
                        ExecutionOutcome outcome = executed.outcome();
                        assertThat(outcome.remoteState()).isEqualTo(RemoteState.NOT_DISPATCHED);
                        assertThat(outcome.reconciliation()).isEqualTo(Reconciliation.NOT_REQUIRED);
                        assertThat(outcome.texts().get(0)).startsWith(expectedPrefix);
                    });
                });
                assertThat(registryCalls).containsSubsequence("reserve", "beginDelete", "release:REFUSED");
                assertThat(payloadCalls).containsExactly("usable", "delete");
                List<ILoggingEvent> errors = downloadRunnerLogs.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.ERROR)).toList();
                assertThat(errors).hasSize(1);
                assertThat(errors.get(0).getThrowableProxy()).isNotNull();
                assertThat(errors.get(0).getThrowableProxy().getClassName()).isEqualTo(failure.getClass().getName());
                assertThat(dispatcherLogs.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.ERROR)).toList()).isEmpty();
            }
        }
    }

    @Test
    @DisplayName("S-D0051-01 D-0051 a refusal before dispatch makes a single write attempt (Principles 4.4 no whole-TX retry): a version conflict is logged as OPERATION_DISPATCH_PERSISTENCE_FAILED and leaves the operation for recovery")
    void aRefusalThatLosesTheRaceEveryTimeIsNotDroppedInSilence() {
        OperationId won = journal.enqueueOwnerApproved(spec(WRITE_PROFILE, new CommandInput(new CommandText("id"), new Utf8Text(""), new Utf8Text(""))));
        journal.conflictOnFailedSaves(0);

        dispatcher(List.of()).open();

        assertThat(journal.require(won).state()).as("the single attempt settles cleanly when uncontended").isEqualTo(OperationState.FAILED);
        assertThat(dispatcherLogs.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.ERROR)).toList()).isEmpty();
        audits.clear();

        OperationId lost = journal.enqueueOwnerApproved(spec(WRITE_PROFILE, new CommandInput(new CommandText("id"), new Utf8Text(""), new Utf8Text(""))));
        journal.conflictOnFailedSaves(1);

        dispatcher(List.of()).open();

        assertThat(journal.require(lost).state()).as("nothing was stored on conflict (no retry loop), so the operation stays for recovery").isEqualTo(OperationState.STARTING);
        List<ILoggingEvent> errors = dispatcherLogs.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.ERROR)).toList();
        assertThat(errors).as("the failure is told to the log, not swallowed").hasSize(1);
        assertThat(errors.get(0).getThrowableProxy()).isNotNull();
        assertThat(errors.get(0).getThrowableProxy().getMessage()).contains(OperationErrorCode.OPERATION_DISPATCH_PERSISTENCE_FAILED.name());
        assertThat(audits).as("no outcome was stored, so none is audited").isEmpty();
    }

    private ExecutionDispatcher dispatcher(List<OperationRunner> runners) {
        OperationEvents events = new OperationEvents(event -> { });
        AuditGate auditGate = new AuditGate(audits::add);
        OutcomeAudit outcomeAudit = new OutcomeAudit(auditGate, journal, CLOCK);
        TerminalNotifier terminal = new TerminalNotifier(new StaticListableBeanFactory().getBeanProvider(TerminalOperationListener.class));
        OperationExpiry expiry = new OperationExpiry(journal, events, terminal, outcomeAudit, CLOCK);
        TaskScheduler taskScheduler = proxy(TaskScheduler.class, (method, args) -> null);
        ExpiryTimers timers = new ExpiryTimers(taskScheduler, expiry);
        OperationWrites writes = new OperationWrites(journal, events, terminal, timers);
        MaintenanceStore maintenanceStore = proxy(MaintenanceStore.class, (method, args) -> "occupied".equals(method) ? Boolean.FALSE : Optional.empty());
        MaintenanceView maintenance = new MaintenanceView(maintenanceStore);
        CommandPolicyStore policies = new CommandPolicyStore() {
            @Override
            public PolicySnapshot current() {
                return new PolicySnapshot(0L, CommandPolicyDocument.empty());
            }

            @Override
            public io.github.jaeseongs95.opermand.domain.command.TargetPolicy targetPolicy(TargetId targetId) {
                return io.github.jaeseongs95.opermand.domain.command.TargetPolicy.none();
            }

            @Override
            public PolicySnapshot replace(CommandPolicyDocument next, long expectedRevision) {
                throw new UnsupportedOperationException("not used");
            }
        };
        ApprovalJudge judge = new ApprovalJudge(policies, platforms(), new io.github.jaeseongs95.opermand.domain.command.RuleApprovalPolicy());
        DispatchChecks checks = new DispatchChecks(journal, targetSource(), maintenance, new ApprovalPolicy(new Capacity(100)), judge, auditGate, events, CLOCK);
        ExecutionTimelineStore timelines = new ExecutionTimelineStore() {
            @Override
            public void record(OperationId operationId, ExecutionTimeline timeline) {
            }

            @Override
            public ExecutionTimeline timelineOf(OperationId operationId) {
                return ExecutionTimeline.empty();
            }
        };
        return new ExecutionDispatcher(journal, journal, journal, new OperationScheduler(), ConcurrencyLimits.defaults(), expiry, writes, outcomeAudit, events,
            new RunningExecutions(), checks, timelines, Runnable::run, CLOCK, runners);
    }

    private TargetSource targetSource() {
        return id -> id.equals(TARGET_ID) ? new TargetLookup.Found(activeTarget.get()) : new TargetLookup.NotRegistered(id);
    }

    private static TargetPlatformRegistry platforms() {
        return new TargetPlatformRegistry(List.of(platform(TargetOs.LINUX), platform(TargetOs.WINDOWS)));
    }

    private static TargetPlatform platform(TargetOs os) {
        return proxy(TargetPlatform.class, (method, args) -> switch (method) {
            case "os" -> os;
            case "pathPolicy" -> new PathPolicyBinding.NoRootPolicy();
            case "permissionSupport" -> os == TargetOs.LINUX ? new PermissionSupport.Supported() : new PermissionSupport.Unsupported(UnsupportedReason.WINDOWS_SFTP_CHMOD);
            case "elevationPlan" -> os == TargetOs.LINUX ? new ElevationPlan.NotSupported() : new ElevationPlan.NotSupported();
            case "classify" -> io.github.jaeseongs95.opermand.domain.classify.CommandClassification.of(io.github.jaeseongs95.opermand.domain.classify.CommandClass.SAFE);
            case "commandLineGrammar" -> new io.github.jaeseongs95.opermand.domain.command.CommandLineGrammar() {
                @Override
                public io.github.jaeseongs95.opermand.domain.command.CommandLineAnalysis analyze(CommandText command) {
                    String[] words = command.value().trim().split("\\s+");
                    return new io.github.jaeseongs95.opermand.domain.command.CommandLineAnalysis(List.of(words[0]),
                        io.github.jaeseongs95.opermand.domain.command.Completeness.COMPLETE, io.github.jaeseongs95.opermand.domain.command.SudoUse.NO_SUDO);
                }

                @Override
                public io.github.jaeseongs95.opermand.domain.command.NormalizedName normalize(String proposedName) {
                    throw new UnsupportedOperationException("not used");
                }

                @Override
                public TargetOs platform() {
                    return os;
                }
            };
            case "deniesName" -> Boolean.FALSE;
            default -> throw new UnsupportedOperationException(method);
        });
    }

    private NewOperation spec(ProfileAlias profile, OperationInput input) {
        String key = String.format("start-guard-request-%04d", ++keySeq);
        return NewOperation.of(DIGESTER, new RequestKey(key), new OwnerId("owner"), TARGET_ID, initialTarget.revision(), profile, input,
            new ConflictKey.None(), new Deadline.None());
    }

    private static ResolvedTarget resolvedTarget(String host) {
        Target entry = new Target(TARGET_ID, new TargetDisplayName("Linux one"), READ_PROFILE, WRITE_PROFILE, new ProfileAlias("cred1"), Toggle.ON, TargetOs.LINUX,
            WindowsRuntime.none(), new CapacityOverride.Inherited(), new CapacityOverride.Inherited(), new DesktopSettings.NoDesktop());
        ConnectionProfile ro = new ConnectionProfile(host, 22, "deploy", Setting.given("SHA256:test-host-key"), Setting.unset(), AuthMethod.AGENT, Setting.unset(),
            Setting.unset(), AccessMode.READ_ONLY, TtyMode.NO_TTY);
        ConnectionProfile rw = new ConnectionProfile(host, 22, "deploy", Setting.given("SHA256:test-host-key"), Setting.unset(), AuthMethod.AGENT, Setting.unset(),
            Setting.unset(), AccessMode.READ_WRITE, TtyMode.NO_TTY);
        return ResolvedTarget.of(entry, ro, rw, DIGESTER);
    }

    @FunctionalInterface
    private interface ProxyCall {
        Object invoke(String method, Object[] args);
    }

    private static <T> T proxy(Class<T> type, ProxyCall call) {
        InvocationHandler handler = (instance, method, args) -> switch (method.getName()) {
            case "equals" -> instance == args[0];
            case "hashCode" -> System.identityHashCode(instance);
            case "toString" -> type.getSimpleName() + "Proxy";
            default -> call.invoke(method.getName(), args);
        };
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    /** In-memory journal, queue view and claim store for deterministic single-thread dispatcher tests. */
    private static final class MemJournal implements OperationJournal, OperationQueueView, ClaimStore {

        private final Map<OperationId, Operation> operations = new LinkedHashMap<>();
        private final Map<OperationId, List<OperationEvent>> eventsByOperation = new LinkedHashMap<>();
        private final Map<OperationId, Long> readyOrder = new LinkedHashMap<>();
        private long idCounter;
        private long readyCounter;
        private int conflictsOnFailedSaves;

        /** The next {@code count} writes of a failed operation lose the version race, as when another writer changes the row every time it is read. */
        void conflictOnFailedSaves(int count) {
            conflictsOnFailedSaves = count;
        }

        OperationId enqueueOwnerApproved(NewOperation spec) {
            OperationId id = new OperationId(String.format("%048d", ++idCounter));
            Operation pending = Operation.admit(spec, id, NOW, Admission.of(DecisionReason.NO_MATCHING_RULE, spec.kind(), 0L));
            Operation queued = pending.enqueue(new StartAuthority.Owner(), NOW);
            operations.put(id, queued);
            List<OperationEvent> history = new ArrayList<>();
            history.add(pending.event());
            history.add(queued.event());
            eventsByOperation.put(id, history);
            readyOrder.put(id, ++readyCounter);
            return id;
        }

        Operation require(OperationId id) {
            return find(id).orElseThrow();
        }

        @Override
        public JournalInsert accept(NewOperation spec, Admission admission) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public JournalInsert accept(NewOperation spec, Admission admission, OperationId id, Instant at, Runnable beforeInsert) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public Optional<Operation> find(OperationId id) {
            return Optional.ofNullable(operations.get(id));
        }

        @Override
        public Optional<Operation> findByRequestKey(RequestKey key) {
            return operations.values().stream().filter(op -> op.requestKey().equals(key)).findFirst();
        }

        @Override
        public Operation save(Operation next, long expectedVersion) {
            return save(next, expectedVersion, () -> { });
        }

        @Override
        public Operation save(Operation next, long expectedVersion, Runnable beforeWrite) {
            Operation current = operations.get(next.id());
            if (current == null || current.version() != expectedVersion || (next.state() == OperationState.FAILED && conflictsOnFailedSaves-- > 0)) {
                throw new RequestRefusedException(OperationErrorCode.OPERATION_VERSION_CONFLICT);
            }
            beforeWrite.run();
            operations.put(next.id(), next);
            eventsByOperation.computeIfAbsent(next.id(), ignored -> new ArrayList<>()).add(next.event());
            if (next.state() != OperationState.QUEUED) {
                readyOrder.remove(next.id());
            }
            return next;
        }

        @Override
        public List<OperationEvent> events(OperationId id) {
            return List.copyOf(eventsByOperation.getOrDefault(id, List.of()));
        }

        @Override
        public ClaimResult claim(OperationId id, long expectedVersion, ConcurrencyLimits limits, Instant at) {
            Operation current = operations.get(id);
            if (current == null || current.state() != OperationState.QUEUED || current.version() != expectedVersion) {
                return new ClaimResult.NotQueued();
            }
            Operation claimed = save(current.claim(at), expectedVersion);
            return new ClaimResult.Claimed(claimed);
        }

        @Override
        public SchedulingView schedulingView(ConcurrencyLimits limits, Instant now, int queuedLimit) {
            List<QueuedOperation> queued = operations.values().stream()
                .filter(op -> op.state() == OperationState.QUEUED)
                .limit(queuedLimit)
                .map(op -> QueuedOperation.of(op, readyOrder.getOrDefault(op.id(), 0L)))
                .toList();
            int activeTotal = (int) operations.values().stream().filter(op -> op.state().isActive()).count();
            int liveUnknown = (int) operations.values().stream().filter(op -> op.state() == OperationState.UNKNOWN).count();
            Map<TargetId, Integer> activeByTarget = activeTotal == 0 ? Map.of() : Map.of(TARGET_ID, activeTotal);
            return new SchedulingView(queued, activeTotal, activeByTarget, liveUnknown, Map.of(), Set.of(), limits, now);
        }

        @Override
        public StateCounts counts() {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public List<Operation> inStates(Set<OperationState> states, TargetFilter filter, int limit) {
            throw new UnsupportedOperationException("not used");
        }
    }
}
