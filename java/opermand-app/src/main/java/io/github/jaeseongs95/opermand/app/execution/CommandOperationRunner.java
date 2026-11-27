package io.github.jaeseongs95.opermand.app.execution;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.opermand.app.remote.ExecutionControl;
import io.github.jaeseongs95.opermand.app.remote.ExecutionLimits;
import io.github.jaeseongs95.opermand.app.remote.ExecutionReport;
import io.github.jaeseongs95.opermand.app.remote.PreparedCommand;
import io.github.jaeseongs95.opermand.app.remote.RemoteExecutor;
import io.github.jaeseongs95.opermand.app.target.TargetPlatform;
import io.github.jaeseongs95.opermand.app.target.TargetPlatformRegistry;
import io.github.jaeseongs95.opermand.app.target.TargetSource;
import io.github.jaeseongs95.opermand.domain.error.DispatchFailure;
import io.github.jaeseongs95.opermand.domain.error.DispatchReason;
import io.github.jaeseongs95.opermand.domain.error.DispatchRefusedException;
import io.github.jaeseongs95.opermand.domain.error.DispatchStage;
import io.github.jaeseongs95.opermand.domain.error.PortalException;
import io.github.jaeseongs95.opermand.domain.operation.CommandInput;
import io.github.jaeseongs95.opermand.domain.operation.Operation;
import io.github.jaeseongs95.opermand.domain.operation.OperationKind;
import io.github.jaeseongs95.opermand.domain.target.ConnectionProfile;
import io.github.jaeseongs95.opermand.domain.target.ResolvedTarget;

/**
 * Runs the operations of kind {@code COMMAND} (stage 3 design 1.1 F, 3.2, 3.4). It resolves the target, lets the target's platform make the command ready for the
 * target's shell, reserves the output, and hands the command to the {@link RemoteExecutor} with the output as its sink via {@link RemoteCommandRun}. It knows no
 * operating system and no SSH library: the platform and the executor are the two extension points. The executor sends the request at most once; this class has no path
 * that sends it again.
 */
@Component
public class CommandOperationRunner implements OperationRunner {

    private final RemoteCommandRun run;

    public CommandOperationRunner(RemoteExecutor executor, TargetSource targets, TargetPlatformRegistry platforms, CommandOutputWriter outputs, ExecutionLimits limits) {
        this.run = new RemoteCommandRun(executor, targets, platforms, outputs, limits);
    }

    @Override
    public OperationKind kind() {
        return OperationKind.COMMAND;
    }

    @Override
    public ExecutionReport run(Operation operation, ExecutionControl control) {
        if (!(operation.input() instanceof CommandInput input)) {
            throw new IllegalArgumentException("Not a command operation: " + operation.kind());
        }
        return run.execute(operation, control, (platform, resolved, profile) -> prepare(platform, resolved, profile, input));
    }

    /** Makes the command ready for the shell; a command the platform cannot take is refused before dispatch, with no text of the command in the refusal. */
    private static PreparedCommand prepare(TargetPlatform platform, ResolvedTarget resolved, ConnectionProfile profile, CommandInput input) {
        try {
            return platform.prepareCommand(input.command(), RemoteCommandRun.defaultWorkdirOf(platform.pathSyntax(), resolved, profile));
        } catch (PortalException | IllegalArgumentException e) {
            throw new DispatchRefusedException(DispatchFailure.refused(DispatchReason.INVALID_COMMAND, DispatchStage.COMMAND_VALIDATION), e);
        }
    }
}
