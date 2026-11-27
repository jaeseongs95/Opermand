package io.github.jaeseongs95.opermand.app.execution;

import java.util.List;
import java.util.Objects;

import io.github.jaeseongs95.opermand.app.remote.ExecutionControl;
import io.github.jaeseongs95.opermand.app.remote.ExecutionLimits;
import io.github.jaeseongs95.opermand.app.remote.ExecutionReport;
import io.github.jaeseongs95.opermand.app.remote.ExecutionTimeline;
import io.github.jaeseongs95.opermand.app.remote.PreparedCommand;
import io.github.jaeseongs95.opermand.app.remote.RemoteExecution;
import io.github.jaeseongs95.opermand.app.remote.RemoteExecutor;
import io.github.jaeseongs95.opermand.app.remote.RemoteTarget;
import io.github.jaeseongs95.opermand.app.remote.WorkingDirectory;
import io.github.jaeseongs95.opermand.app.target.TargetPlatform;
import io.github.jaeseongs95.opermand.app.target.TargetPlatformRegistry;
import io.github.jaeseongs95.opermand.app.target.TargetSource;
import io.github.jaeseongs95.opermand.domain.error.DispatchFailure;
import io.github.jaeseongs95.opermand.domain.error.DispatchReason;
import io.github.jaeseongs95.opermand.domain.error.DispatchRefusedException;
import io.github.jaeseongs95.opermand.domain.error.DispatchStage;
import io.github.jaeseongs95.opermand.domain.error.ExecutionFailure;
import io.github.jaeseongs95.opermand.domain.operation.ExecutionOutcome;
import io.github.jaeseongs95.opermand.domain.operation.ExecutionReason;
import io.github.jaeseongs95.opermand.domain.operation.Operation;
import io.github.jaeseongs95.opermand.domain.path.PathSyntax;
import io.github.jaeseongs95.opermand.domain.target.AccessMode;
import io.github.jaeseongs95.opermand.domain.target.ConnectionProfile;
import io.github.jaeseongs95.opermand.domain.target.ResolvedTarget;
import io.github.jaeseongs95.opermand.domain.target.Setting;
import io.github.jaeseongs95.opermand.domain.target.TargetLookup;
import io.github.jaeseongs95.opermand.domain.target.WindowsDirectory;

/**
 * Shared target resolution, output reservation, and single-attempt {@link RemoteExecutor} invocation used by both
 * {@link CommandOperationRunner} and {@link SourceOperationRunner} (stage 4 design B-3).
 */
final class RemoteCommandRun {

    @FunctionalInterface
    interface CommandPreparer {

        PreparedCommand prepare(TargetPlatform platform, ResolvedTarget resolved, ConnectionProfile profile);
    }

    private final RemoteExecutor executor;
    private final TargetSource targets;
    private final TargetPlatformRegistry platforms;
    private final CommandOutputWriter outputs;
    private final ExecutionLimits limits;

    RemoteCommandRun(RemoteExecutor executor, TargetSource targets, TargetPlatformRegistry platforms, CommandOutputWriter outputs, ExecutionLimits limits) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.platforms = Objects.requireNonNull(platforms, "platforms");
        this.outputs = Objects.requireNonNull(outputs, "outputs");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    ExecutionReport execute(Operation operation, ExecutionControl control, CommandPreparer preparer) {
        if (!(targets.lookup(operation.targetId()) instanceof TargetLookup.Found found)) {
            throw new DispatchRefusedException(DispatchFailure.refused(DispatchReason.TARGET_CHANGED, DispatchStage.TARGET_GUARD));
        }
        ResolvedTarget resolved = found.target();
        TargetPlatform platform = platforms.platformOf(resolved.target().os());
        AccessMode access = operation.profile().equals(resolved.target().readProfile()) ? AccessMode.READ_ONLY : AccessMode.READ_WRITE;
        ConnectionProfile profile = access == AccessMode.READ_ONLY ? resolved.reader() : resolved.writer();
        PreparedCommand prepared = preparer.prepare(platform, resolved, profile);
        RemoteExecution execution = new RemoteExecution(new RemoteTarget(operation.targetId(), operation.targetRevision(), operation.profile(), access), prepared, limits,
            platform.execReply());
        return switch (outputs.open(operation, limits)) {
            case OutputOpening.Refused refused -> refusedBeforeStart(refused.failure());
            case OutputOpening.Opened opened -> runWith(opened.output(), execution, control);
        };
    }

    /** The directory a command starts in: the Windows setting of the target, then the working directory of the profile, then the login directory. */
    static WorkingDirectory defaultWorkdirOf(PathSyntax syntax, ResolvedTarget resolved, ConnectionProfile profile) {
        if (resolved.target().windows().workdir() instanceof Setting.Given<WindowsDirectory> windows) {
            return parseWorkdir(syntax, windows.value().value());
        }
        if (profile.workdir() instanceof Setting.Given<String> posix) {
            return parseWorkdir(syntax, posix.value());
        }
        return new WorkingDirectory.Default();
    }

    /** Converts a non-empty working directory string into a {@link WorkingDirectory}, handling both drive roots and regular paths. */
    static WorkingDirectory parseWorkdir(PathSyntax syntax, String raw) {
        if (raw.isEmpty()) {
            return new WorkingDirectory.Default();
        }
        if (syntax == PathSyntax.WINDOWS && WindowsDirectory.isDriveRoot(raw)) {
            return new WorkingDirectory.DriveRoot(new WindowsDirectory(raw));
        }
        if (syntax == PathSyntax.POSIX && "/".equals(raw)) {
            return new WorkingDirectory.Default();
        }
        return new WorkingDirectory.At(syntax.path(raw));
    }

    /** Runs the prepared command with the reserved output. An output that was reserved and not used is given up, whatever ended the run. */
    private ExecutionReport runWith(OpenOutput output, RemoteExecution execution, ExecutionControl control) {
        ExecutionReport report;
        try {
            report = executor.run(execution, output, control);
        } catch (RuntimeException e) {
            output.abandon();
            throw e;
        }
        return new ExecutionReport(output.finish(report.outcome()), report.timeline());
    }

    /** The command was not started because its output could not be reserved: failed, certain, nothing sent. */
    private static ExecutionReport refusedBeforeStart(ExecutionFailure failure) {
        ExecutionOutcome base = ExecutionOutcome.notDispatched(ExecutionReason.INPUT_FAILED, List.of("The output of the command could not be reserved"));
        ExecutionOutcome refused = new ExecutionOutcome(base.reason(), base.remoteState(), base.exit(), base.signalDelivery(), base.cleanup(), base.reconciliation(),
            base.outputState(), base.capture(), base.timeout(), base.stdout(), base.stderr(), failure, base.failed(), base.texts(), base.evidence());
        return new ExecutionReport(refused, ExecutionTimeline.empty());
    }
}
