package io.github.jaeseongs95.opermand.app.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.opermand.app.output.FormatSpan;
import io.github.jaeseongs95.opermand.app.output.OutputBinding;
import io.github.jaeseongs95.opermand.app.output.OutputBudget;
import io.github.jaeseongs95.opermand.app.output.OutputFormat;
import io.github.jaeseongs95.opermand.app.output.OutputFormats;
import io.github.jaeseongs95.opermand.app.output.OutputId;
import io.github.jaeseongs95.opermand.app.output.OutputLookup;
import io.github.jaeseongs95.opermand.app.output.OutputManifest;
import io.github.jaeseongs95.opermand.app.output.OutputManifestStore;
import io.github.jaeseongs95.opermand.app.output.OutputPayloadStore;
import io.github.jaeseongs95.opermand.app.output.OutputReservation;
import io.github.jaeseongs95.opermand.app.output.OutputState;
import io.github.jaeseongs95.opermand.app.output.OutputTarget;
import io.github.jaeseongs95.opermand.app.output.RetainedBytes;
import io.github.jaeseongs95.opermand.app.output.StreamFrame;
import io.github.jaeseongs95.opermand.app.output.StreamInfo;
import io.github.jaeseongs95.opermand.app.remote.AgentCliPlan;
import io.github.jaeseongs95.opermand.app.remote.CancelSource;
import io.github.jaeseongs95.opermand.app.remote.CommandKind;
import io.github.jaeseongs95.opermand.app.remote.ElevationPlan;
import io.github.jaeseongs95.opermand.app.remote.ExecReply;
import io.github.jaeseongs95.opermand.app.remote.ExecutionControl;
import io.github.jaeseongs95.opermand.app.remote.ExecutionLimits;
import io.github.jaeseongs95.opermand.app.remote.ExecutionReport;
import io.github.jaeseongs95.opermand.app.remote.ExecutionTimeline;
import io.github.jaeseongs95.opermand.app.remote.PathPolicyBinding;
import io.github.jaeseongs95.opermand.app.remote.PermissionSupport;
import io.github.jaeseongs95.opermand.app.remote.PreparedCommand;
import io.github.jaeseongs95.opermand.app.remote.RemoteExecution;
import io.github.jaeseongs95.opermand.app.remote.RemoteExecutor;
import io.github.jaeseongs95.opermand.app.remote.SourceArguments;
import io.github.jaeseongs95.opermand.app.remote.StdinBytes;
import io.github.jaeseongs95.opermand.app.remote.WorkingDirectory;
import io.github.jaeseongs95.opermand.app.storage.DiskSpace;
import io.github.jaeseongs95.opermand.app.storage.PayloadRead;
import io.github.jaeseongs95.opermand.app.target.TargetPlatform;
import io.github.jaeseongs95.opermand.app.target.TargetPlatformRegistry;
import io.github.jaeseongs95.opermand.app.target.TargetSource;
import io.github.jaeseongs95.opermand.domain.agentcli.AgentCliIntent;
import io.github.jaeseongs95.opermand.domain.agentcli.AgentRelease;
import io.github.jaeseongs95.opermand.domain.approval.ApprovalPolicy;
import io.github.jaeseongs95.opermand.domain.capacity.Capacity;
import io.github.jaeseongs95.opermand.domain.classify.CommandClassification;
import io.github.jaeseongs95.opermand.domain.command.CommandLineGrammar;
import io.github.jaeseongs95.opermand.domain.command.TargetRule;
import io.github.jaeseongs95.opermand.domain.error.CapacityErrorCode;
import io.github.jaeseongs95.opermand.domain.error.DispatchReason;
import io.github.jaeseongs95.opermand.domain.error.DispatchRefusedException;
import io.github.jaeseongs95.opermand.domain.error.DispatchStage;
import io.github.jaeseongs95.opermand.domain.error.RequestRefusedException;
import io.github.jaeseongs95.opermand.domain.id.Digest;
import io.github.jaeseongs95.opermand.domain.id.Digester;
import io.github.jaeseongs95.opermand.domain.id.OperationId;
import io.github.jaeseongs95.opermand.domain.id.OwnerId;
import io.github.jaeseongs95.opermand.domain.id.RequestKey;
import io.github.jaeseongs95.opermand.domain.id.Sha256Digester;
import io.github.jaeseongs95.opermand.domain.id.TargetId;
import io.github.jaeseongs95.opermand.domain.operation.Admission;
import io.github.jaeseongs95.opermand.domain.operation.CommandInput;
import io.github.jaeseongs95.opermand.domain.operation.ConflictKey;
import io.github.jaeseongs95.opermand.domain.operation.Deadline;
import io.github.jaeseongs95.opermand.domain.operation.DecisionReason;
import io.github.jaeseongs95.opermand.domain.operation.Elevation;
import io.github.jaeseongs95.opermand.domain.operation.ExecutionOutcome;
import io.github.jaeseongs95.opermand.domain.operation.NewOperation;
import io.github.jaeseongs95.opermand.domain.operation.Operation;
import io.github.jaeseongs95.opermand.domain.operation.OperationKind;
import io.github.jaeseongs95.opermand.domain.operation.SourceInput;
import io.github.jaeseongs95.opermand.domain.operation.StartAuthority;
import io.github.jaeseongs95.opermand.domain.path.PathSyntax;
import io.github.jaeseongs95.opermand.domain.target.AccessMode;
import io.github.jaeseongs95.opermand.domain.target.AuthMethod;
import io.github.jaeseongs95.opermand.domain.target.CapacityOverride;
import io.github.jaeseongs95.opermand.domain.target.ConnectionProfile;
import io.github.jaeseongs95.opermand.domain.target.DesktopSettings;
import io.github.jaeseongs95.opermand.domain.target.PosixPythonExecutable;
import io.github.jaeseongs95.opermand.domain.target.ProfileAlias;
import io.github.jaeseongs95.opermand.domain.target.ResolvedTarget;
import io.github.jaeseongs95.opermand.domain.target.Setting;
import io.github.jaeseongs95.opermand.domain.target.SourceRuntime;
import io.github.jaeseongs95.opermand.domain.target.Target;
import io.github.jaeseongs95.opermand.domain.target.TargetCapabilities;
import io.github.jaeseongs95.opermand.domain.target.TargetDisplayName;
import io.github.jaeseongs95.opermand.domain.target.TargetLookup;
import io.github.jaeseongs95.opermand.domain.target.TargetOs;
import io.github.jaeseongs95.opermand.domain.target.Toggle;
import io.github.jaeseongs95.opermand.domain.target.TtyMode;
import io.github.jaeseongs95.opermand.domain.target.WindowsDirectory;
import io.github.jaeseongs95.opermand.domain.target.WindowsPythonExecutable;
import io.github.jaeseongs95.opermand.domain.target.WindowsRuntime;
import io.github.jaeseongs95.opermand.domain.text.CommandText;
import io.github.jaeseongs95.opermand.domain.text.SourceText;
import io.github.jaeseongs95.opermand.domain.text.Utf8Text;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit and boundary tests for {@link SourcePlanRegistry} and {@link SourceOperationRunner} (stage 4 design B-3, defect SSH_PORTAL-D-0170).
 */
class SourceOperationRunnerTest {

    private static final Instant NOW = Instant.parse("2026-11-27T06:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final OperationId OP_ID = new OperationId("a".repeat(48));
    private static final OperationId OP_ID_2 = new OperationId("b".repeat(48));
    private static final OperationId OP_ID_3 = new OperationId("c".repeat(48));
    private static final TargetId LINUX_ID = new TargetId("lin");
    private static final TargetId WIN_ID = new TargetId("win");
    private static final ProfileAlias READ_PROFILE = new ProfileAlias("ro");
    private static final ProfileAlias WRITE_PROFILE = new ProfileAlias("rw");
    private static final ExecutionControl CONTROL = new ExecutionControl(new CancelSource(), () -> { });

    private final Digester digester = new Sha256Digester();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    @DisplayName("D-0170 SourcePlanRegistry bounds entries by requestCapacity, consumes once on take, and clears on terminal event")
    void registryBoundsConsumesAndClearsOnTerminal() {
        SourcePlanRegistry registry = new SourcePlanRegistry(new ApprovalPolicy(new Capacity(2)));
        SourceText s1 = new SourceText("print(1)\n");
        SourceText s2 = new SourceText("print(2)\n");
        SourceText s3 = new SourceText("print(3)\n");

        registry.register(OP_ID, s1);
        registry.register(OP_ID_2, s2);
        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.totalBytes()).isEqualTo(18L);

        assertThatThrownBy(() -> registry.register(OP_ID_3, s3))
            .isInstanceOfSatisfying(RequestRefusedException.class, e -> assertThat(e.code()).isEqualTo(CapacityErrorCode.REQUEST_CAPACITY_EXCEEDED));

        // Re-registering an existing id updates its byte accounting in place without exceeding entry capacity.
        SourceText s1Updated = new SourceText("print(100)\n");
        registry.register(OP_ID, s1Updated);
        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.totalBytes()).isEqualTo(20L);

        assertThat(registry.take(OP_ID)).contains(s1Updated);
        assertThat(registry.take(OP_ID)).isEmpty();
        assertThat(registry.size()).isEqualTo(1);
        assertThatThrownBy(() -> new SourcePlanRegistry(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourcePlanRegistry(-2)).isInstanceOf(IllegalArgumentException.class);

        ResolvedTarget linux = resolvedLinux(Setting.unset());
        Operation pending = pendingSourceOperation(OP_ID_2, linux, s2, "[]", "/", Elevation.NONE);
        Operation expired = pending.expire(NOW.plusSeconds(60));
        registry.onTerminal(expired);
        registry.remove(OP_ID_2);
        assertThat(registry.size()).isZero();
        assertThat(registry.totalBytes()).isZero();
    }

    @Test
    @DisplayName("D-0170 missing private plan (after restart) is refused before dispatch with PRIVATE_PLAN at SOURCE_PLAN")
    void missingPrivatePlanRefusesBeforeDispatch() {
        SourceText source = new SourceText("print('lost')\n");
        ResolvedTarget linux = resolvedLinux(Setting.unset());
        Operation op = startingSourceOperation(OP_ID, linux, source, "[]", "/", Elevation.NONE);
        AtomicInteger execCalls = new AtomicInteger();
        SourceOperationRunner runner = runner(linux, new SourcePlanRegistry(4), (exec, sink, ctrl) -> {
            execCalls.incrementAndGet();
            return new ExecutionReport(ExecutionOutcome.exited(0, List.of()), ExecutionTimeline.empty());
        }, new FakeManifestStore(false), new FakePayloadStore(digester));

        assertThat(runner.kind()).isEqualTo(OperationKind.SOURCE);
        assertThatThrownBy(() -> runner.run(op, CONTROL))
            .isInstanceOfSatisfying(DispatchRefusedException.class, e -> {
                assertThat(e.failure().reason()).isEqualTo(DispatchReason.PRIVATE_PLAN);
                assertThat(e.failure().stage()).isEqualTo(DispatchStage.SOURCE_PLAN);
            });
        assertThat(execCalls.get()).isZero();
    }

    @Test
    @DisplayName("D-0170 tampered or mismatched source digest is refused with PRIVATE_PLAN at SOURCE_PLAN")
    void digestMismatchRefusesBeforeDispatch() {
        SourceText approved = new SourceText("print('approved')\n");
        SourceText different = new SourceText("print('tampered')\n");
        ResolvedTarget linux = resolvedLinux(Setting.unset());
        Operation op = startingSourceOperation(OP_ID, linux, approved, "[]", "/", Elevation.NONE);
        SourcePlanRegistry registry = new SourcePlanRegistry(4);
        registry.register(OP_ID, different);

        SourceOperationRunner runner = runner(linux, registry, (exec, sink, ctrl) -> new ExecutionReport(ExecutionOutcome.exited(0, List.of()), ExecutionTimeline.empty()),
            new FakeManifestStore(false), new FakePayloadStore(digester));

        assertThatThrownBy(() -> runner.run(op, CONTROL))
            .isInstanceOfSatisfying(DispatchRefusedException.class, e -> {
                assertThat(e.failure().reason()).isEqualTo(DispatchReason.PRIVATE_PLAN);
                assertThat(e.failure().stage()).isEqualTo(DispatchStage.SOURCE_PLAN);
            });
        assertThat(registry.size()).isZero();
    }

    @Test
    @DisplayName("D-0170 malformed argvJson is refused with INVALID_COMMAND at COMMAND_VALIDATION")
    void malformedArgvJsonRefuses() {
        SourceText source = new SourceText("print(1)\n");
        ResolvedTarget linux = resolvedLinux(Setting.unset());
        for (String badJson : List.of("not-json", "{\"a\":1}", "[123]")) {
            SourcePlanRegistry registry = new SourcePlanRegistry(4);
            registry.register(OP_ID, source);
            Operation op = startingSourceOperation(OP_ID, linux, source, badJson, "/", Elevation.NONE);
            SourceOperationRunner runner = runner(linux, registry, (exec, sink, ctrl) -> new ExecutionReport(ExecutionOutcome.exited(0, List.of()), ExecutionTimeline.empty()),
                new FakeManifestStore(false), new FakePayloadStore(digester));
            assertThatThrownBy(() -> runner.run(op, CONTROL)).as(badJson)
                .isInstanceOfSatisfying(DispatchRefusedException.class, e -> {
                    assertThat(e.failure().reason()).isEqualTo(DispatchReason.INVALID_COMMAND);
                    assertThat(e.failure().stage()).isEqualTo(DispatchStage.COMMAND_VALIDATION);
                });
        }
    }

    @Test
    @DisplayName("D-0170 Linux SOURCE runs with custom PosixPythonExecutable, parsed argv, cwd, and Elevation.SUDO")
    void linuxSourceRunsWithConfiguredInterpreterAndElevation() {
        SourceText source = new SourceText("print('hello')\n");
        PosixPythonExecutable custom = new PosixPythonExecutable("/usr/local/bin/python3.12");
        ResolvedTarget linux = resolvedLinux(Setting.given(custom));
        Operation op = startingSourceOperation(OP_ID, linux, source, "[\"--flag\",\"val\"]", "/srv/work", Elevation.SUDO);
        SourcePlanRegistry registry = new SourcePlanRegistry(4);
        registry.register(OP_ID, source);

        AtomicReference<SourceRuntime> capturedRuntime = new AtomicReference<>();
        AtomicReference<Elevation> capturedElevation = new AtomicReference<>();
        AtomicReference<SourceArguments> capturedArgs = new AtomicReference<>();
        AtomicReference<WorkingDirectory> capturedWorkdir = new AtomicReference<>();
        AtomicReference<RemoteExecution> capturedExecution = new AtomicReference<>();

        TargetPlatform linuxPlatform = new RecordingPlatform(TargetOs.LINUX, (src, args, workdir, runtime, elevation) -> {
            capturedRuntime.set(runtime);
            capturedElevation.set(elevation);
            capturedArgs.set(args);
            capturedWorkdir.set(workdir);
            return new PreparedCommand("prepared-linux", new StdinBytes(src.value().getBytes(StandardCharsets.UTF_8)), CommandKind.SOURCE);
        });
        TargetPlatform windowsPlatform = new RecordingPlatform(TargetOs.WINDOWS, (s, a, w, r, e) -> {
            throw new IllegalStateException("unexpected");
        });
        SourceOperationRunner runner = new SourceOperationRunner((exec, sink, ctrl) -> {
            capturedExecution.set(exec);
            return new ExecutionReport(ExecutionOutcome.exited(0, List.of()), ExecutionTimeline.empty());
        }, id -> id.equals(LINUX_ID) ? new TargetLookup.Found(linux) : new TargetLookup.NotRegistered(id),
            new TargetPlatformRegistry(List.of(linuxPlatform, windowsPlatform)),
            outputWriter(new FakeManifestStore(false), new FakePayloadStore(digester)), ExecutionLimits.defaults(), registry, digester, json);

        ExecutionReport report = runner.run(op, CONTROL);

        assertThat(report.outcome().exit()).isEqualTo(new io.github.jaeseongs95.opermand.domain.operation.ExitStatus.Exited(0));
        assertThat(capturedRuntime.get()).isEqualTo(new SourceRuntime.Posix(custom));
        assertThat(capturedElevation.get()).isEqualTo(Elevation.SUDO);
        assertThat(capturedArgs.get().values()).containsExactly("--flag", "val");
        assertThat(capturedWorkdir.get()).isEqualTo(new WorkingDirectory.At(PathSyntax.POSIX.path("/srv/work")));
        assertThat(capturedExecution.get().command().commandLine()).isEqualTo("prepared-linux");
        assertThat(registry.size()).isZero();
    }

    @Test
    @DisplayName("D-0170 Windows SOURCE runs with WindowsPythonExecutable and DriveRoot cwd, and refuses when target lacks Python")
    void windowsSourceRunsWithInterpreterAndRefusesWithoutIt() {
        SourceText source = new SourceText("print('win')\n");
        WindowsPythonExecutable py = new WindowsPythonExecutable("C:\\Python312\\python.exe");
        ResolvedTarget winWithPy = resolvedWindows(Setting.given(py));
        Operation op = startingSourceOperation(OP_ID, winWithPy, source, "[\"a\"]", "D:\\", Elevation.SUDO);
        SourcePlanRegistry registry = new SourcePlanRegistry(4);
        registry.register(OP_ID, source);

        AtomicReference<SourceRuntime> capturedRuntime = new AtomicReference<>();
        AtomicReference<WorkingDirectory> capturedWorkdir = new AtomicReference<>();
        TargetPlatform linuxPlatform = new RecordingPlatform(TargetOs.LINUX, (s, a, w, r, e) -> {
            throw new IllegalStateException("unexpected");
        });
        TargetPlatform windowsPlatform = new RecordingPlatform(TargetOs.WINDOWS, (src, args, workdir, runtime, elevation) -> {
            capturedRuntime.set(runtime);
            capturedWorkdir.set(workdir);
            return new PreparedCommand("prepared-win", new StdinBytes(src.value().getBytes(StandardCharsets.UTF_8)), CommandKind.SOURCE);
        });
        SourceOperationRunner runner = new SourceOperationRunner((exec, sink, ctrl) -> new ExecutionReport(ExecutionOutcome.exited(0, List.of()), ExecutionTimeline.empty()),
            id -> id.equals(WIN_ID) ? new TargetLookup.Found(winWithPy) : new TargetLookup.NotRegistered(id),
            new TargetPlatformRegistry(List.of(linuxPlatform, windowsPlatform)),
            outputWriter(new FakeManifestStore(false), new FakePayloadStore(digester)), ExecutionLimits.defaults(), registry, digester, json);

        ExecutionReport report = runner.run(op, CONTROL);
        assertThat(report.outcome().exit()).isEqualTo(new io.github.jaeseongs95.opermand.domain.operation.ExitStatus.Exited(0));
        assertThat(capturedRuntime.get()).isEqualTo(new SourceRuntime.WindowsPython(py));
        assertThat(capturedWorkdir.get()).isEqualTo(new WorkingDirectory.DriveRoot(new WindowsDirectory("D:\\")));

        ResolvedTarget winNoPy = resolvedWindows(Setting.unset());
        Operation opNoPy = startingSourceOperation(OP_ID_2, winNoPy, source, "[]", "C:\\", Elevation.NONE);
        registry.register(OP_ID_2, source);
        SourceOperationRunner noPyRunner = new SourceOperationRunner((exec, sink, ctrl) -> {
            throw new AssertionError("must not execute");
        }, id -> id.equals(WIN_ID) ? new TargetLookup.Found(winNoPy) : new TargetLookup.NotRegistered(id),
            new TargetPlatformRegistry(List.of(linuxPlatform, windowsPlatform)),
            outputWriter(new FakeManifestStore(false), new FakePayloadStore(digester)), ExecutionLimits.defaults(), registry, digester, json);

        assertThatThrownBy(() -> noPyRunner.run(opNoPy, CONTROL))
            .isInstanceOfSatisfying(DispatchRefusedException.class, e -> {
                assertThat(e.failure().reason()).isEqualTo(DispatchReason.TARGET_CHANGED);
                assertThat(e.failure().stage()).isEqualTo(DispatchStage.TARGET_GUARD);
            });
    }

    @Test
    @DisplayName("D-0170 output reservation refusal stops before executor, and executor RuntimeException abandons output")
    void outputRefusalAndExecutorFailureAbandonOutput() {
        SourceText source = new SourceText("print(1)\n");
        ResolvedTarget linux = resolvedLinux(Setting.unset());
        Operation op = startingSourceOperation(OP_ID, linux, source, "[]", "", Elevation.NONE);

        SourcePlanRegistry reg1 = new SourcePlanRegistry(4);
        reg1.register(OP_ID, source);
        AtomicInteger calls = new AtomicInteger();
        SourceOperationRunner refusedRunner = runner(linux, reg1, (exec, sink, ctrl) -> {
            calls.incrementAndGet();
            return new ExecutionReport(ExecutionOutcome.exited(0, List.of()), ExecutionTimeline.empty());
        }, new FakeManifestStore(true), new FakePayloadStore(digester));

        ExecutionReport refused = refusedRunner.run(op, CONTROL);
        assertThat(calls.get()).isZero();
        assertThat(refused.outcome().remoteState().name()).isEqualTo("NOT_DISPATCHED");

        SourcePlanRegistry reg2 = new SourcePlanRegistry(4);
        reg2.register(OP_ID, source);
        FakeManifestStore manifests = new FakeManifestStore(false);
        SourceOperationRunner failingRunner = runner(linux, reg2, (exec, sink, ctrl) -> {
            throw new IllegalStateException("transport boom");
        }, manifests, new FakePayloadStore(digester));

        assertThatThrownBy(() -> failingRunner.run(op, CONTROL)).isInstanceOf(IllegalStateException.class).hasMessage("transport boom");
        assertThat(manifests.markedUnavailable.get()).isTrue();
    }

    @Test
    @DisplayName("D-0170 non-SOURCE operation input throws IllegalArgumentException")
    void nonSourceInputThrows() {
        ResolvedTarget linux = resolvedLinux(Setting.unset());
        CommandInput cmd = new CommandInput(new CommandText("ls"), new Utf8Text(""), new Utf8Text(""));
        NewOperation spec = NewOperation.of(digester, new RequestKey("key-1234567890123456"), new OwnerId("owner"), linux.target().id(), linux.revision(), WRITE_PROFILE,
            cmd, new ConflictKey.None(), new Deadline.None());
        Operation op = Operation.admit(spec, OP_ID, NOW, Admission.of(DecisionReason.NO_MATCHING_RULE, OperationKind.COMMAND, 0L))
            .enqueue(new StartAuthority.Owner(), NOW).claim(NOW);
        SourceOperationRunner runner = runner(linux, new SourcePlanRegistry(4), (e, s, c) -> new ExecutionReport(ExecutionOutcome.exited(0, List.of()), ExecutionTimeline.empty()),
            new FakeManifestStore(false), new FakePayloadStore(digester));
        assertThatThrownBy(() -> runner.run(op, CONTROL)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("D-0170 byte-length mismatch, missing target, and platform prepareSource refusal map to expected DispatchReason")
    void byteLengthMismatchMissingTargetAndPrepareFailure() {
        SourceText approved = new SourceText("print('ok')\n");
        SourceText shorter = new SourceText("x\n");
        ResolvedTarget linux = resolvedLinux(Setting.unset());

        // Byte-length mismatch refuses with PRIVATE_PLAN / SOURCE_PLAN.
        SourcePlanRegistry lenReg = new SourcePlanRegistry(4);
        lenReg.register(OP_ID, shorter);
        Operation op = startingSourceOperation(OP_ID, linux, approved, "[]", "", Elevation.NONE);
        SourceOperationRunner lenRunner = runner(linux, lenReg, (e, s, c) -> new ExecutionReport(ExecutionOutcome.exited(0, List.of()), ExecutionTimeline.empty()),
            new FakeManifestStore(false), new FakePayloadStore(digester));
        assertThatThrownBy(() -> lenRunner.run(op, CONTROL))
            .isInstanceOfSatisfying(DispatchRefusedException.class, e -> {
                assertThat(e.failure().reason()).isEqualTo(DispatchReason.PRIVATE_PLAN);
                assertThat(e.failure().stage()).isEqualTo(DispatchStage.SOURCE_PLAN);
            });

        // Target disappearing before dispatch refuses with TARGET_CHANGED / TARGET_GUARD.
        SourcePlanRegistry missingTargetReg = new SourcePlanRegistry(4);
        missingTargetReg.register(OP_ID, approved);
        TargetPlatform linuxPlatform = new RecordingPlatform(TargetOs.LINUX, (src, args, w, r, e) ->
            new PreparedCommand("ok", new StdinBytes(new byte[0]), CommandKind.SOURCE));
        TargetPlatform winPlatform = new RecordingPlatform(TargetOs.WINDOWS, (src, args, w, r, e) ->
            new PreparedCommand("ok", new StdinBytes(new byte[0]), CommandKind.SOURCE));
        SourceOperationRunner missingTargetRunner = new SourceOperationRunner(
            (e, s, c) -> new ExecutionReport(ExecutionOutcome.exited(0, List.of()), ExecutionTimeline.empty()),
            TargetLookup.NotRegistered::new,
            new TargetPlatformRegistry(List.of(linuxPlatform, winPlatform)),
            outputWriter(new FakeManifestStore(false), new FakePayloadStore(digester)),
            ExecutionLimits.defaults(), missingTargetReg, digester, json);
        assertThatThrownBy(() -> missingTargetRunner.run(op, CONTROL))
            .isInstanceOfSatisfying(DispatchRefusedException.class, e -> {
                assertThat(e.failure().reason()).isEqualTo(DispatchReason.TARGET_CHANGED);
                assertThat(e.failure().stage()).isEqualTo(DispatchStage.TARGET_GUARD);
            });

        // Platform prepareSource throwing IllegalArgumentException or PortalException maps to INVALID_COMMAND / COMMAND_VALIDATION.
        for (RuntimeException failure : List.of(new IllegalArgumentException("bad arg"), new RequestRefusedException(CapacityErrorCode.REQUEST_CAPACITY_EXCEEDED))) {
            SourcePlanRegistry prepReg = new SourcePlanRegistry(4);
            prepReg.register(OP_ID, approved);
            TargetPlatform failingLinux = new RecordingPlatform(TargetOs.LINUX, (src, args, w, r, e) -> {
                throw failure;
            });
            SourceOperationRunner prepRunner = new SourceOperationRunner(
                (e, s, c) -> new ExecutionReport(ExecutionOutcome.exited(0, List.of()), ExecutionTimeline.empty()),
                id -> new TargetLookup.Found(linux),
                new TargetPlatformRegistry(List.of(failingLinux, winPlatform)),
                outputWriter(new FakeManifestStore(false), new FakePayloadStore(digester)),
                ExecutionLimits.defaults(), prepReg, digester, json);
            assertThatThrownBy(() -> prepRunner.run(op, CONTROL))
                .isInstanceOfSatisfying(DispatchRefusedException.class, e -> {
                    assertThat(e.failure().reason()).isEqualTo(DispatchReason.INVALID_COMMAND);
                    assertThat(e.failure().stage()).isEqualTo(DispatchStage.COMMAND_VALIDATION);
                });
        }
    }

    @Test
    @DisplayName("D-0170 RemoteCommandRun workdir resolution and OutputRendering.Rendered value semantics")
    void workdirResolutionAndRenderedValueSemantics() {
        Target winWithRoot = new Target(WIN_ID, new TargetDisplayName("WinRoot"), READ_PROFILE, WRITE_PROFILE, new ProfileAlias("c1"), Toggle.ON, TargetOs.WINDOWS,
            new WindowsRuntime(Setting.given(new WindowsDirectory("C:\\")), Setting.unset()), new CapacityOverride.Inherited(), new CapacityOverride.Inherited(),
            new DesktopSettings.NoDesktop());
        ResolvedTarget resolvedWinRoot = ResolvedTarget.of(winWithRoot, profile(AccessMode.READ_ONLY), profile(AccessMode.READ_WRITE), digester);
        assertThat(RemoteCommandRun.defaultWorkdirOf(PathSyntax.WINDOWS, resolvedWinRoot, resolvedWinRoot.writer()))
            .isEqualTo(new WorkingDirectory.DriveRoot(new WindowsDirectory("C:\\")));

        Target winWithDir = new Target(WIN_ID, new TargetDisplayName("WinDir"), READ_PROFILE, WRITE_PROFILE, new ProfileAlias("c1"), Toggle.ON, TargetOs.WINDOWS,
            new WindowsRuntime(Setting.given(new WindowsDirectory("C:\\work")), Setting.unset()), new CapacityOverride.Inherited(), new CapacityOverride.Inherited(),
            new DesktopSettings.NoDesktop());
        ResolvedTarget resolvedWinDir = ResolvedTarget.of(winWithDir, profile(AccessMode.READ_ONLY), profile(AccessMode.READ_WRITE), digester);
        assertThat(RemoteCommandRun.defaultWorkdirOf(PathSyntax.WINDOWS, resolvedWinDir, resolvedWinDir.writer()))
            .isEqualTo(new WorkingDirectory.At(PathSyntax.WINDOWS.path("C:\\work")));

        ResolvedTarget linux = resolvedLinux(Setting.unset());
        ConnectionProfile posixWithWorkdir = new ConnectionProfile("host.example.test", 22, "deploy", Setting.given("ssh-ed25519 AAAAC3Nza"), Setting.unset(), AuthMethod.KEY,
            Setting.given("key-1"), Setting.given("/srv/app"), AccessMode.READ_WRITE, TtyMode.NO_TTY);
        assertThat(RemoteCommandRun.defaultWorkdirOf(PathSyntax.POSIX, linux, posixWithWorkdir))
            .isEqualTo(new WorkingDirectory.At(PathSyntax.POSIX.path("/srv/app")));
        assertThat(RemoteCommandRun.parseWorkdir(PathSyntax.POSIX, "")).isEqualTo(new WorkingDirectory.Default());
        assertThat(RemoteCommandRun.parseWorkdir(PathSyntax.POSIX, "/")).isEqualTo(new WorkingDirectory.Default());
        assertThatThrownBy(() -> new WorkingDirectory.DriveRoot(new WindowsDirectory("C:\\work"))).isInstanceOf(IllegalArgumentException.class);

        OutputRendering.Rendered r1 = new OutputRendering.Rendered("{\"a\":1}".getBytes(StandardCharsets.UTF_8), "ok".getBytes(StandardCharsets.UTF_8));
        OutputRendering.Rendered r2 = new OutputRendering.Rendered("{\"a\":1}".getBytes(StandardCharsets.UTF_8), "ok".getBytes(StandardCharsets.UTF_8));
        OutputRendering.Rendered diffJson = new OutputRendering.Rendered("{\"a\":2}".getBytes(StandardCharsets.UTF_8), "ok".getBytes(StandardCharsets.UTF_8));
        OutputRendering.Rendered diffText = new OutputRendering.Rendered("{\"a\":1}".getBytes(StandardCharsets.UTF_8), "no".getBytes(StandardCharsets.UTF_8));
        assertThat(r1).isEqualTo(r2).hasSameHashCodeAs(r2).isNotEqualTo(diffJson).isNotEqualTo(diffText).isNotEqualTo("other");
        assertThat(r1.toString()).contains("jsonBytes=7").contains("textBytes=2");
    }

    private SourceOperationRunner runner(ResolvedTarget resolved, SourcePlanRegistry registry, RemoteExecutor executor, FakeManifestStore manifests, FakePayloadStore payloads) {
        TargetPlatform linux = new RecordingPlatform(TargetOs.LINUX, (src, args, w, r, e) ->
            new PreparedCommand("linux-src", new StdinBytes(src.value().getBytes(StandardCharsets.UTF_8)), CommandKind.SOURCE));
        TargetPlatform windows = new RecordingPlatform(TargetOs.WINDOWS, (src, args, w, r, e) ->
            new PreparedCommand("win-src", new StdinBytes(src.value().getBytes(StandardCharsets.UTF_8)), CommandKind.SOURCE));
        TargetSource targets = id -> id.equals(resolved.target().id()) ? new TargetLookup.Found(resolved) : new TargetLookup.NotRegistered(id);
        return new SourceOperationRunner(executor, targets, new TargetPlatformRegistry(List.of(linux, windows)), outputWriter(manifests, payloads),
            ExecutionLimits.defaults(), registry, digester, json);
    }

    private CommandOutputWriter outputWriter(FakeManifestStore manifests, FakePayloadStore payloads) {
        return new CommandOutputWriter(manifests, payloads, io.github.jaeseongs95.opermand.domain.redact.RedactedText::new, json, digester, CLOCK);
    }

    private Operation pendingSourceOperation(OperationId id, ResolvedTarget resolved, SourceText source, String argvJson, String cwd, Elevation elevation) {
        byte[] bytes = source.value().getBytes(StandardCharsets.UTF_8);
        Digest digest = digester.digest(bytes);
        SourceInput input = new SourceInput(new CommandText("python3 -"), digest, digest, bytes.length, new Utf8Text(argvJson), new Utf8Text(cwd), elevation, digest,
            new Utf8Text("ctx"), new Utf8Text("disp"));
        NewOperation spec = NewOperation.of(digester, new RequestKey("key-" + id.value().substring(0, 16)), new OwnerId("owner"), resolved.target().id(),
            resolved.revision(), WRITE_PROFILE, input, new ConflictKey.None(), new Deadline.At(NOW.plusSeconds(30)));
        return Operation.admit(spec, id, NOW, Admission.of(DecisionReason.NO_MATCHING_RULE, OperationKind.SOURCE, 0L));
    }

    private Operation startingSourceOperation(OperationId id, ResolvedTarget resolved, SourceText source, String argvJson, String cwd, Elevation elevation) {
        return pendingSourceOperation(id, resolved, source, argvJson, cwd, elevation).enqueue(new StartAuthority.Owner(), NOW).claim(NOW);
    }

    private ResolvedTarget resolvedLinux(Setting<PosixPythonExecutable> python) {
        Target target = new Target(LINUX_ID, new TargetDisplayName("Linux"), READ_PROFILE, WRITE_PROFILE, new ProfileAlias("c1"), Toggle.ON, TargetOs.LINUX,
            WindowsRuntime.none(), python, new CapacityOverride.Inherited(), new CapacityOverride.Inherited(), new DesktopSettings.NoDesktop());
        return ResolvedTarget.of(target, profile(AccessMode.READ_ONLY), profile(AccessMode.READ_WRITE), digester);
    }

    private ResolvedTarget resolvedWindows(Setting<WindowsPythonExecutable> python) {
        Target target = new Target(WIN_ID, new TargetDisplayName("Windows"), READ_PROFILE, WRITE_PROFILE, new ProfileAlias("c1"), Toggle.ON, TargetOs.WINDOWS,
            new WindowsRuntime(Setting.unset(), python), new CapacityOverride.Inherited(), new CapacityOverride.Inherited(), new DesktopSettings.NoDesktop());
        return ResolvedTarget.of(target, profile(AccessMode.READ_ONLY), profile(AccessMode.READ_WRITE), digester);
    }

    private static ConnectionProfile profile(AccessMode access) {
        return new ConnectionProfile("host.example.test", 22, "deploy", Setting.given("ssh-ed25519 AAAAC3Nza"), Setting.unset(), AuthMethod.KEY,
            Setting.given("key-1"), Setting.unset(), access, TtyMode.NO_TTY);
    }

    @FunctionalInterface
    private interface SourcePreparer {
        PreparedCommand prepare(SourceText source, SourceArguments args, WorkingDirectory workdir, SourceRuntime runtime, Elevation elevation);
    }

    private record RecordingPlatform(TargetOs os, SourcePreparer preparer) implements TargetPlatform {

        @Override
        public PathSyntax pathSyntax() {
            return os == TargetOs.WINDOWS ? PathSyntax.WINDOWS : PathSyntax.POSIX;
        }

        @Override
        public TargetCapabilities capabilities(Target target) {
            return target.capabilities();
        }

        @Override
        public CommandClassification classify(CommandText command) {
            throw new IllegalStateException("unused");
        }

        @Override
        public CommandLineGrammar commandLineGrammar() {
            throw new IllegalStateException("unused");
        }

        @Override
        public boolean deniesName(TargetRule rule, CommandText command) {
            return false;
        }

        @Override
        public PreparedCommand prepareCommand(CommandText command, WorkingDirectory workdir) {
            throw new IllegalStateException("unused");
        }

        @Override
        public PreparedCommand prepareSource(SourceText source, SourceArguments args, WorkingDirectory workdir, SourceRuntime runtime, Elevation elevation) {
            return preparer.prepare(source, args, workdir, runtime, elevation);
        }

        @Override
        public PathPolicyBinding pathPolicy(Target target) {
            return new PathPolicyBinding.NoRootPolicy();
        }

        @Override
        public PermissionSupport permissionSupport() {
            return new PermissionSupport.Supported();
        }

        @Override
        public ElevationPlan elevationPlan() {
            return new ElevationPlan.NotSupported();
        }

        @Override
        public AgentCliPlan agentCliPlan(AgentCliIntent intent, AgentRelease release) {
            throw new IllegalStateException("unused");
        }

        @Override
        public ExecReply execReply() {
            return ExecReply.AWAITED;
        }
    }

    private static final class FakeManifestStore implements OutputManifestStore {

        private final boolean refuse;
        final AtomicBoolean markedUnavailable = new AtomicBoolean();
        private final OutputId outputId = new OutputId("d".repeat(48));

        FakeManifestStore(boolean refuse) {
            this.refuse = refuse;
        }

        @Override
        public OutputLookup find(OutputId id, Instant now) {
            return new OutputLookup.Missing();
        }

        @Override
        public OutputReservation reserve(OutputBinding binding, long expectedBytes, Instant at, DiskSpace disk) {
            return refuse ? new OutputReservation.BudgetExceeded(expectedBytes, 0L, 0L) : new OutputReservation.Reserved(outputId);
        }

        @Override
        public OutputManifest recordStream(OutputId id, StreamInfo stream) {
            return new OutputManifest(id, new OutputBinding(OP_ID, new OutputTarget.Unbound()), OutputState.PENDING, NOW.plusSeconds(3600), 0L, 0L,
                new OutputFormats(Map.of()), stream, 1L);
        }

        @Override
        public OutputManifest markReady(OutputId id, OutputFormats formats, long sizeBytes) {
            return new OutputManifest(id, new OutputBinding(OP_ID, new OutputTarget.Unbound()), OutputState.READY, NOW.plusSeconds(3600), sizeBytes, sizeBytes,
                formats, new StreamInfo.NoStream(), 2L);
        }

        @Override
        public void markUnavailable(OutputId id, Instant expiresAt, RetainedBytes retained) {
            markedUnavailable.set(true);
        }

        @Override
        public OutputBudget budget() {
            return new OutputBudget(1024L * 1024L, 0L, 100, 0);
        }

        @Override
        public List<OutputManifest> expired(Instant now) {
            return List.of();
        }

        @Override
        public List<OutputManifest> inState(OutputState state) {
            return List.of();
        }

        @Override
        public void delete(OutputId id) {
            // no-op
        }
    }

    private static final class FakePayloadStore implements OutputPayloadStore {

        private final Digester digester;
        private long spooledBytes;

        FakePayloadStore(Digester digester) {
            this.digester = digester;
        }

        @Override
        public long append(OutputId id, StreamFrame frame) {
            spooledBytes += frame.data().length;
            return spooledBytes;
        }

        @Override
        public OutputFormats finish(OutputId id, byte[] json, byte[] text) {
            Digest jsonDigest = digester.digest(json);
            Digest textDigest = digester.digest(text);
            return new OutputFormats(Map.of(
                OutputFormat.JSON, new FormatSpan(0L, json.length, jsonDigest, List.of(jsonDigest)),
                OutputFormat.TEXT, new FormatSpan(json.length, text.length, textDigest, List.of(textDigest))));
        }

        @Override
        public PayloadRead openFormat(OutputId id, OutputFormat format, FormatSpan span) {
            throw new IllegalStateException("unused");
        }

        @Override
        public PayloadRead openStream(OutputId id) {
            throw new IllegalStateException("unused");
        }

        @Override
        public Digest streamDigest(OutputId id) {
            return digester.digest(new byte[0]);
        }

        @Override
        public void delete(OutputId id) {
            spooledBytes = 0L;
        }

        @Override
        public long retainedBytes(OutputId id) {
            return spooledBytes;
        }

        @Override
        public void quarantine(OutputId id) {
            // no-op
        }

        @Override
        public Set<OutputId> stored() {
            return Set.of();
        }

        @Override
        public DiskSpace usable() {
            return new DiskSpace(1024L * 1024L * 1024L);
        }
    }
}
