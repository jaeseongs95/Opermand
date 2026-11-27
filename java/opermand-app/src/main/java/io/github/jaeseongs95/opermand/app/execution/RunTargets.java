package io.github.jaeseongs95.opermand.app.execution;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.opermand.app.target.TargetSource;
import io.github.jaeseongs95.opermand.domain.error.DispatchFailure;
import io.github.jaeseongs95.opermand.domain.error.DispatchReason;
import io.github.jaeseongs95.opermand.domain.error.DispatchRefusedException;
import io.github.jaeseongs95.opermand.domain.error.DispatchStage;
import io.github.jaeseongs95.opermand.domain.operation.Operation;
import io.github.jaeseongs95.opermand.domain.target.ResolvedTarget;
import io.github.jaeseongs95.opermand.domain.target.TargetLookup;

/**
 * The target of an operation that is about to run, as it is configured now. The runners of the file and agent CLI kinds hand it to their use case together with the
 * revision the operation was approved for; the use case and the adapter's guard compare the two before anything is sent. A target that is gone or switched off is a
 * refusal before dispatch ({@code TARGET_CHANGED} at the target guard), not an unknown result.
 */
@Component
public class RunTargets {

    private final TargetSource targets;

    public RunTargets(TargetSource targets) {
        this.targets = targets;
    }

    /** @throws DispatchRefusedException {@code TARGET_CHANGED} at {@code TARGET_GUARD} when the target is not registered and enabled */
    public ResolvedTarget of(Operation operation) {
        if (targets.lookup(operation.targetId()) instanceof TargetLookup.Found found) {
            return found.target();
        }
        throw new DispatchRefusedException(DispatchFailure.refused(DispatchReason.TARGET_CHANGED, DispatchStage.TARGET_GUARD));
    }
}
