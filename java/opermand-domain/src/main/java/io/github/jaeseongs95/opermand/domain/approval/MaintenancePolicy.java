package io.github.jaeseongs95.opermand.domain.approval;

import io.github.jaeseongs95.opermand.domain.error.DispatchFailure;
import io.github.jaeseongs95.opermand.domain.error.DispatchReason;
import io.github.jaeseongs95.opermand.domain.error.DispatchStage;

/**
 * The dispatch refusal produced when maintenance holds the bridge for a queued operation (SSH_PORTAL-D-0124).
 */
public final class MaintenancePolicy {

    /** The failure a queued operation gets when maintenance begins while it waits: refused before dispatch, for maintenance. */
    public DispatchFailure failureForQueuedOperation() {
        return DispatchFailure.refused(DispatchReason.MAINTENANCE, DispatchStage.MAINTENANCE);
    }
}
