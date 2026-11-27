package io.github.jaeseongs95.opermand.app.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.jaeseongs95.opermand.app.artifact.ArtifactPayloadStore;
import io.github.jaeseongs95.opermand.app.artifact.ArtifactRegistry;
import io.github.jaeseongs95.opermand.app.artifact.ArtifactReservation;
import io.github.jaeseongs95.opermand.app.artifact.StagingTimes;
import io.github.jaeseongs95.opermand.app.file.DownloadService;
import io.github.jaeseongs95.opermand.app.file.TransferOutcomes;
import io.github.jaeseongs95.opermand.app.file.TransferSupport;
import io.github.jaeseongs95.opermand.app.remote.CancelSource;
import io.github.jaeseongs95.opermand.app.remote.DownloadRequest;
import io.github.jaeseongs95.opermand.app.remote.DownloadResult;
import io.github.jaeseongs95.opermand.app.remote.ElevationRequest;
import io.github.jaeseongs95.opermand.app.remote.ElevationResult;
import io.github.jaeseongs95.opermand.app.remote.ExecutionControl;
import io.github.jaeseongs95.opermand.app.remote.ExecutionReport;
import io.github.jaeseongs95.opermand.app.remote.PathPolicyBinding;
import io.github.jaeseongs95.opermand.app.remote.PayloadSink;
import io.github.jaeseongs95.opermand.app.remote.PayloadSource;
import io.github.jaeseongs95.opermand.app.remote.PermissionRequest;
import io.github.jaeseongs95.opermand.app.remote.PermissionResult;
import io.github.jaeseongs95.opermand.app.remote.RemoteFileTransfer;
import io.github.jaeseongs95.opermand.app.remote.TransferLimits;
import io.github.jaeseongs95.opermand.app.remote.UploadRequest;
import io.github.jaeseongs95.opermand.app.remote.UploadResult;
import io.github.jaeseongs95.opermand.app.storage.DiskSpace;
import io.github.jaeseongs95.opermand.app.target.TargetPlatform;
import io.github.jaeseongs95.opermand.app.target.TargetPlatformRegistry;
import io.github.jaeseongs95.opermand.app.target.TargetSource;
import io.github.jaeseongs95.opermand.domain.error.DispatchFailure;
import io.github.jaeseongs95.opermand.domain.error.DispatchReason;
import io.github.jaeseongs95.opermand.domain.error.DispatchRefusedException;
import io.github.jaeseongs95.opermand.domain.error.DispatchStage;
import io.github.jaeseongs95.opermand.domain.error.ExecutionErrorCode;
import io.github.jaeseongs95.opermand.domain.error.ExecutionFailure;
import io.github.jaeseongs95.opermand.domain.error.ExecutionStage;
import io.github.jaeseongs95.opermand.domain.error.RemoteOutcome;
import io.github.jaeseongs95.opermand.domain.error.RequestErrorCode;
import io.github.jaeseongs95.opermand.domain.error.RequestRefusedException;
import io.github.jaeseongs95.opermand.domain.file.FileBinding;
import io.github.jaeseongs95.opermand.domain.file.FileTargetBinding;
import io.github.jaeseongs95.opermand.domain.id.ArtifactId;
import io.github.jaeseongs95.opermand.domain.id.OperationId;
import io.github.jaeseongs95.opermand.domain.id.OwnerId;
import io.github.jaeseongs95.opermand.domain.id.RequestKey;
import io.github.jaeseongs95.opermand.domain.id.Sha256Digester;
import io.github.jaeseongs95.opermand.domain.id.TargetId;
import io.github.jaeseongs95.opermand.domain.operation.Admission;
import io.github.jaeseongs95.opermand.domain.operation.ConflictKey;
import io.github.jaeseongs95.opermand.domain.operation.Deadline;
import io.github.jaeseongs95.opermand.domain.operation.DecisionReason;
import io.github.jaeseongs95.opermand.domain.operation.DownloadInput;
import io.github.jaeseongs95.opermand.domain.operation.Elevation;
import io.github.jaeseongs95.opermand.domain.operation.ExecutionOutcome;
import io.github.jaeseongs95.opermand.domain.operation.NewOperation;
import io.github.jaeseongs95.opermand.domain.operation.Operation;
import io.github.jaeseongs95.opermand.domain.operation.OperationKind;
import io.github.jaeseongs95.opermand.domain.operation.OperationState;
import io.github.jaeseongs95.opermand.domain.operation.Reconciliation;
import io.github.jaeseongs95.opermand.domain.operation.RemoteState;
import io.github.jaeseongs95.opermand.domain.path.PosixRemotePath;
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
import io.github.jaeseongs95.opermand.domain.text.Utf8Text;

/**
 * What a download answers when something goes wrong that is not a result of the SFTP adapter (cases S-D0013-24 to S-D0013-26 and S-D0013-33, defect D-0013 rework R1 and
 * audit F4): a download only reads, so a failure of the portal's own store or code is a read-only failure and never an unknown outcome with a reconciliation, wherever it
 * happens on the way from the dispatcher to the adapter. The runner is the one place that sorts the exceptions. The registry and the payload store are JDK proxies that
 * record their calls, the target source and the remote transfer are plain classes (there is no mocking library in this project).
 */
class DownloadOperationRunnerFailureTest {

    private static final Sha256Digester DIGEST = new Sha256Digester();
    private static final ArtifactId RESERVED = new ArtifactId("a".repeat(48));
    private static final TargetId TARGET_ID = new TargetId("web-1");
    private static final ExecutionControl CONTROL = new ExecutionControl(new CancelSource(), () -> { });

    private final List<String> registryCalls = new ArrayList<>();
    private final List<String> payloadCalls = new ArrayList<>();
    private final AtomicInteger adapterCalls = new AtomicInteger();
    private final ResolvedTarget target = resolvedTarget();
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void captureLog() {
        logger = (Logger) LoggerFactory.getLogger(DownloadOperationRunner.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void releaseLog() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("S-D0013-24 D-0013 a store failure that is no refusal (a runtime exception of the database) while the artifact is reserved is a read-only failure, not an exception that settles as UNKNOWN")
    void aStoreFailureAtTheReservationIsAReadOnlyFailure() {
        IllegalStateException storeFailure = new IllegalStateException("injected: the artifact table cannot be read");
        DownloadOperationRunner runner = runner(found(), () -> {
            throw storeFailure;
        }, platform(TargetOs.LINUX, () -> new PathPolicyBinding.NoRootPolicy()), () -> new DownloadResult.Failed(io.github.jaeseongs95.opermand.app.remote.TransferFailureCause.CONNECT_FAILED));

        ExecutionReport report = runner.run(operation(), CONTROL);

        assertReadOnlyLocalFailure(report.outcome());
        assertThat(adapterCalls).as("nothing was sent to the target").hasValue(0);
        assertCauseLogged(storeFailure);
    }

    @Test
    @DisplayName("S-D0013-25 D-0013 an unexpected runtime exception of the adapter is a read-only failure; the reserved artifact is discarded and the cause is logged")
    void anAdapterRuntimeExceptionIsAReadOnlyFailure() {
        IllegalStateException adapterFailure = new IllegalStateException("injected: a state error inside the adapter");
        DownloadOperationRunner runner = runner(found(), () -> new ArtifactReservation.Reserved(RESERVED), linux(), () -> {
            throw adapterFailure;
        });

        ExecutionReport report = runner.run(operation(), CONTROL);

        assertReadOnlyLocalFailure(report.outcome());
        assertThat(adapterCalls).hasValue(1);
        assertDiscarded();
        assertCauseLogged(adapterFailure);
    }

    @Test
    @DisplayName("S-D0013-26 D-0013 a DispatchRefusedException of the adapter leaves the runner as it is, a RequestRefusedException becomes a refusal before dispatch, and the artifact is discarded in both cases")
    void theRefusalsPassThrough() {
        DispatchRefusedException dispatch = new DispatchRefusedException(DispatchFailure.refused(DispatchReason.TARGET_CHANGED, DispatchStage.TARGET_GUARD));
        RequestRefusedException request = new RequestRefusedException(RequestErrorCode.TARGET_CAPABILITY_UNSUPPORTED);

        DownloadOperationRunner first = runner(found(), () -> new ArtifactReservation.Reserved(RESERVED), linux(), () -> {
            throw dispatch;
        });
        assertThatThrownBy(() -> first.run(operation(), CONTROL)).isSameAs(dispatch);
        assertDiscarded();

        registryCalls.clear();
        payloadCalls.clear();
        DownloadOperationRunner second = runner(found(), () -> new ArtifactReservation.Reserved(RESERVED), linux(), () -> {
            throw request;
        });
        assertThatThrownBy(() -> second.run(operation(), CONTROL)).isInstanceOfSatisfying(DispatchRefusedException.class, refused -> {
            assertThat(refused.failure()).isEqualTo(DispatchFailure.refused(DispatchReason.UNKNOWN, DispatchStage.FILE_GUARD));
            assertThat(refused.getCause()).isSameAs(request);
        });
        assertDiscarded();
        assertThat(appender.list).as("a refusal is not logged as a failure").isEmpty();
    }

    @Test
    @DisplayName("S-D0013-33 D-0013 an exception before the transfer (the target lookup, the platform's path policy) is a read-only failure too; a target that is gone is still a refusal before dispatch")
    void anExceptionBeforeTheTransferIsAReadOnlyFailure() {
        IllegalStateException lookupFailure = new IllegalStateException("injected: the target source failed");
        DownloadOperationRunner lookup = runner(id -> {
            throw lookupFailure;
        }, () -> new ArtifactReservation.Reserved(RESERVED), linux(), () -> new DownloadResult.Failed(io.github.jaeseongs95.opermand.app.remote.TransferFailureCause.CONNECT_FAILED));

        assertReadOnlyLocalFailure(lookup.run(operation(), CONTROL).outcome());
        assertCauseLogged(lookupFailure);
        assertThat(adapterCalls).hasValue(0);
        assertThat(registryCalls).as("nothing was reserved").isEmpty();

        appender.list.clear();
        IllegalStateException policyFailure = new IllegalStateException("injected: the platform cannot say its path policy");
        DownloadOperationRunner policy = runner(found(), () -> new ArtifactReservation.Reserved(RESERVED), platform(TargetOs.LINUX, () -> {
            throw policyFailure;
        }), () -> new DownloadResult.Failed(io.github.jaeseongs95.opermand.app.remote.TransferFailureCause.CONNECT_FAILED));

        assertReadOnlyLocalFailure(policy.run(operation(), CONTROL).outcome());
        assertCauseLogged(policyFailure);
        assertThat(adapterCalls).hasValue(0);
        assertThat(registryCalls).as("the policy is asked before anything is reserved").isEmpty();

        DownloadOperationRunner gone = runner(id -> new TargetLookup.NotRegistered(id), () -> new ArtifactReservation.Reserved(RESERVED), linux(),
            () -> new DownloadResult.Failed(io.github.jaeseongs95.opermand.app.remote.TransferFailureCause.CONNECT_FAILED));
        assertThatThrownBy(() -> gone.run(operation(), CONTROL)).isInstanceOfSatisfying(DispatchRefusedException.class,
            refused -> assertThat(refused.failure()).isEqualTo(DispatchFailure.refused(DispatchReason.TARGET_CHANGED, DispatchStage.TARGET_GUARD)));
    }

    private void assertReadOnlyLocalFailure(ExecutionOutcome outcome) {
        assertThat(outcome.verdict()).isEqualTo(OperationState.FAILED);
        assertThat(outcome.remoteState()).as("a download changes nothing on the target").isEqualTo(RemoteState.NOT_DISPATCHED);
        assertThat(outcome.reconciliation()).isEqualTo(Reconciliation.NOT_REQUIRED);
        assertThat(outcome.failure()).isEqualTo(new ExecutionFailure.Classified(ExecutionStage.OPERATION, ExecutionErrorCode.OPERATION_FAILED, RemoteOutcome.NOT_STARTED));
        assertThat(outcome.texts().get(0)).isEqualTo("LOCAL_IO_FAILED: The portal failed locally (its storage or an internal error); nothing on the target changed.");
    }

    private void assertDiscarded() {
        assertThat(registryCalls).as("the reserved row is removed in the registry's order").containsSubsequence("reserve", "beginDelete", "release:REFUSED");
        assertThat(payloadCalls).containsExactly("usable", "delete");
    }

    private void assertCauseLogged(Throwable cause) {
        List<ILoggingEvent> events = appender.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN)).toList();
        assertThat(events).as("the cause is logged once at WARN or above").hasSize(1);
        assertThat(events.get(0).getThrowableProxy()).as("with the throwable").isNotNull();
        assertThat(events.get(0).getThrowableProxy().getClassName()).isEqualTo(cause.getClass().getName());
        assertThat(events.get(0).getThrowableProxy().getMessage()).isEqualTo(cause.getMessage());
    }

    // ---- fakes ----

    private TargetSource found() {
        return id -> new TargetLookup.Found(target);
    }

    private DownloadOperationRunner runner(TargetSource source, Supplier<ArtifactReservation> reservation, TargetPlatform linuxPlatform, Supplier<DownloadResult> adapter) {
        ArtifactRegistry registry = recording(ArtifactRegistry.class, registryCalls, method -> switch (method) {
            case "reserve" -> reservation.get();
            default -> null;
        });
        ArtifactPayloadStore payloads = recording(ArtifactPayloadStore.class, payloadCalls, method -> switch (method) {
            case "usable" -> new DiskSpace(Long.MAX_VALUE);
            default -> null;
        });
        RemoteFileTransfer transfer = new RemoteFileTransfer() {
            @Override
            public UploadResult upload(UploadRequest request, PayloadSource source, ExecutionControl control) {
                throw new UnsupportedOperationException("not a download");
            }

            @Override
            public DownloadResult download(DownloadRequest request, PayloadSink sink, ExecutionControl control) {
                adapterCalls.incrementAndGet();
                return adapter.get();
            }

            @Override
            public PermissionResult applyPermissions(PermissionRequest request, ExecutionControl control) {
                throw new UnsupportedOperationException("not a download");
            }

            @Override
            public ElevationResult checkElevation(ElevationRequest request, ExecutionControl control) {
                throw new UnsupportedOperationException("not a download");
            }
        };
        TargetPlatformRegistry platforms = new TargetPlatformRegistry(List.of(linuxPlatform, platform(TargetOs.WINDOWS, () -> new PathPolicyBinding.NoRootPolicy())));
        TransferOutcomes outcomes = new TransferOutcomes();
        DownloadService service = new DownloadService(registry, payloads, transfer, new TransferSupport(platforms), outcomes, TransferLimits.defaults(), StagingTimes.defaults(),
            DIGEST, Clock.fixed(Instant.parse("2026-11-16T00:00:00Z"), ZoneOffset.UTC));
        return new DownloadOperationRunner(service, new RunTargets(source), outcomes);
    }

    /** What the proxy answers for a method, by its name; every call is recorded (a release with its reason). */
    @FunctionalInterface
    private interface Answer {
        Object to(String method);
    }

    private static <T> T recording(Class<T> type, List<String> calls, Answer answer) {
        InvocationHandler handler = (proxy, method, arguments) -> {
            String name = method.getName();
            if (name.equals("release") && arguments != null && arguments.length == 2) {
                calls.add("release:" + arguments[1]);
            } else {
                calls.add(name);
            }
            return answer.to(name);
        };
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    private static TargetPlatform linux() {
        return platform(TargetOs.LINUX, () -> new PathPolicyBinding.NoRootPolicy());
    }

    /** A platform that answers what a download asks of it; the rest is not used. */
    private static TargetPlatform platform(TargetOs os, Supplier<PathPolicyBinding> pathPolicy) {
        return (TargetPlatform) Proxy.newProxyInstance(TargetPlatform.class.getClassLoader(), new Class<?>[] {TargetPlatform.class}, (proxy, method, arguments) -> switch (method.getName()) {
            case "os" -> os;
            case "pathPolicy" -> pathPolicy.get();
            case "equals" -> proxy == arguments[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "platform " + os;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private Operation operation() {
        DownloadInput input = new DownloadInput(new PosixRemotePath("/var/log/result.bin"), Elevation.NONE, new FileBinding.Bound(new FileTargetBinding(TARGET_ID, target.revision())),
            new Utf8Text("caller"), new Utf8Text("display"));
        NewOperation spec = NewOperation.of(DIGEST, new RequestKey("runner-failure-request-0001"), new OwnerId("owner"), TARGET_ID, target.revision(), new ProfileAlias("web-ro"),
            input, new ConflictKey.None(), new Deadline.None());
        return Operation.admit(spec, new OperationId("1".repeat(48)), Instant.parse("2026-11-16T00:00:00Z"),
            Admission.of(DecisionReason.NO_MATCHING_RULE, OperationKind.DOWNLOAD, 0L));
    }

    private static ResolvedTarget resolvedTarget() {
        Target target = new Target(TARGET_ID, new TargetDisplayName("Web one"), new ProfileAlias("web-ro"), new ProfileAlias("web-rw"), new ProfileAlias("cred1"), Toggle.ON, TargetOs.LINUX,
            WindowsRuntime.none(), new CapacityOverride.Inherited(), new CapacityOverride.Inherited(), new DesktopSettings.NoDesktop());
        return ResolvedTarget.of(target, profile(AccessMode.READ_ONLY), profile(AccessMode.READ_WRITE), DIGEST);
    }

    private static ConnectionProfile profile(AccessMode access) {
        return new ConnectionProfile("host.example", 22, "deploy", Setting.given("SHA256:test-host-key"), Setting.unset(), AuthMethod.AGENT, Setting.unset(), Setting.unset(), access,
            TtyMode.NO_TTY);
    }
}
