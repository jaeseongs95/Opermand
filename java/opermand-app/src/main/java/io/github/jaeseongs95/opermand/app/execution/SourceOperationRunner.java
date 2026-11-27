package io.github.jaeseongs95.opermand.app.execution;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.opermand.app.remote.ExecutionControl;
import io.github.jaeseongs95.opermand.app.remote.ExecutionLimits;
import io.github.jaeseongs95.opermand.app.remote.ExecutionReport;
import io.github.jaeseongs95.opermand.app.remote.PreparedCommand;
import io.github.jaeseongs95.opermand.app.remote.RemoteExecutor;
import io.github.jaeseongs95.opermand.app.remote.SourceArguments;
import io.github.jaeseongs95.opermand.app.remote.WorkingDirectory;
import io.github.jaeseongs95.opermand.app.target.TargetPlatform;
import io.github.jaeseongs95.opermand.app.target.TargetPlatformRegistry;
import io.github.jaeseongs95.opermand.app.target.TargetSource;
import io.github.jaeseongs95.opermand.domain.error.DispatchFailure;
import io.github.jaeseongs95.opermand.domain.error.DispatchReason;
import io.github.jaeseongs95.opermand.domain.error.DispatchRefusedException;
import io.github.jaeseongs95.opermand.domain.error.DispatchStage;
import io.github.jaeseongs95.opermand.domain.error.PortalException;
import io.github.jaeseongs95.opermand.domain.id.Digester;
import io.github.jaeseongs95.opermand.domain.operation.Operation;
import io.github.jaeseongs95.opermand.domain.operation.OperationKind;
import io.github.jaeseongs95.opermand.domain.operation.SourceInput;
import io.github.jaeseongs95.opermand.domain.target.ConnectionProfile;
import io.github.jaeseongs95.opermand.domain.target.ResolvedTarget;
import io.github.jaeseongs95.opermand.domain.target.Setting;
import io.github.jaeseongs95.opermand.domain.target.SourceRuntime;
import io.github.jaeseongs95.opermand.domain.text.SourceText;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs operations of kind {@code SOURCE} (stage 4 design B-3, defect SSH_PORTAL-D-0170).
 *
 * <p>Consumes the private {@link SourceText} from {@link SourcePlanRegistry}, verifies its UTF-8 byte length and SHA-256 against the stored
 * {@link SourceInput}, reconstructs {@link SourceArguments} from {@code argvJson}, resolves the target's {@link SourceRuntime}, prepares the fixed
 * Python receiver via {@link TargetPlatform#prepareSource}, and delegates execution and output capture to {@link RemoteCommandRun}. If the private
 * plan is absent (such as for a queued {@code SOURCE} operation after a restart) or its digest does not match, dispatch is refused before any remote
 * attempt with {@link DispatchReason#PRIVATE_PLAN} at {@link DispatchStage#SOURCE_PLAN}.
 */
@Component
public class SourceOperationRunner implements OperationRunner {

    private final RemoteCommandRun run;
    private final SourcePlanRegistry plans;
    private final Digester digester;
    private final JsonMapper json;

    public SourceOperationRunner(RemoteExecutor executor, TargetSource targets, TargetPlatformRegistry platforms, CommandOutputWriter outputs, ExecutionLimits limits,
                                 SourcePlanRegistry plans, Digester digester, JsonMapper json) {
        this.run = new RemoteCommandRun(executor, targets, platforms, outputs, limits);
        this.plans = Objects.requireNonNull(plans, "plans");
        this.digester = Objects.requireNonNull(digester, "digester");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public OperationKind kind() {
        return OperationKind.SOURCE;
    }

    @Override
    public ExecutionReport run(Operation operation, ExecutionControl control) {
        if (!(operation.input() instanceof SourceInput input)) {
            throw new IllegalArgumentException("Not a source operation: " + operation.kind());
        }
        SourceText source = plans.take(operation.id())
            .orElseThrow(() -> new DispatchRefusedException(DispatchFailure.refused(DispatchReason.PRIVATE_PLAN, DispatchStage.SOURCE_PLAN)));
        verifySourceIntegrity(source, input);
        SourceArguments args = parseArguments(input.argvJson().value());
        return run.execute(operation, control, (platform, resolved, profile) -> prepare(platform, resolved, profile, input, source, args));
    }

    private void verifySourceIntegrity(SourceText source, SourceInput input) {
        byte[] bytes = source.value().getBytes(StandardCharsets.UTF_8);
        if (bytes.length != input.sourceBytes() || !digester.digest(bytes).equals(input.sourceSha256())) {
            throw new DispatchRefusedException(DispatchFailure.refused(DispatchReason.PRIVATE_PLAN, DispatchStage.SOURCE_PLAN));
        }
    }

    private SourceArguments parseArguments(String argvJson) {
        try {
            JsonNode tree = json.readTree(argvJson);
            if (!tree.isArray()) {
                throw invalidCommand(null);
            }
            List<String> values = new ArrayList<>(tree.size());
            for (JsonNode element : tree) {
                if (!element.isString()) {
                    throw invalidCommand(null);
                }
                values.add(element.asString());
            }
            return new SourceArguments(values);
        } catch (JacksonException | IllegalArgumentException e) {
            throw invalidCommand(e);
        }
    }

    private static PreparedCommand prepare(TargetPlatform platform, ResolvedTarget resolved, ConnectionProfile profile, SourceInput input, SourceText source, SourceArguments args) {
        if (!(resolved.target().sourceRuntime() instanceof Setting.Given<SourceRuntime> runtime)) {
            throw new DispatchRefusedException(DispatchFailure.refused(DispatchReason.TARGET_CHANGED, DispatchStage.TARGET_GUARD));
        }
        try {
            WorkingDirectory workdir = input.cwd().value().isEmpty()
                ? RemoteCommandRun.defaultWorkdirOf(platform.pathSyntax(), resolved, profile)
                : RemoteCommandRun.parseWorkdir(platform.pathSyntax(), input.cwd().value());
            return platform.prepareSource(source, args, workdir, runtime.value(), input.elevation());
        } catch (PortalException | IllegalArgumentException e) {
            throw invalidCommand(e);
        }
    }

    private static DispatchRefusedException invalidCommand(Throwable cause) {
        return cause == null
            ? new DispatchRefusedException(DispatchFailure.refused(DispatchReason.INVALID_COMMAND, DispatchStage.COMMAND_VALIDATION))
            : new DispatchRefusedException(DispatchFailure.refused(DispatchReason.INVALID_COMMAND, DispatchStage.COMMAND_VALIDATION), cause);
    }
}
