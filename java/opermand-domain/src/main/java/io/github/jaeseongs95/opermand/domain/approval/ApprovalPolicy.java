package io.github.jaeseongs95.opermand.domain.approval;

import io.github.jaeseongs95.opermand.domain.capacity.Capacity;
import io.github.jaeseongs95.opermand.domain.error.DispatchFailure;
import io.github.jaeseongs95.opermand.domain.error.DispatchReason;
import io.github.jaeseongs95.opermand.domain.error.DispatchStage;
import io.github.jaeseongs95.opermand.domain.operation.ApprovalMode;
import io.github.jaeseongs95.opermand.domain.operation.DecisionReason;
import io.github.jaeseongs95.opermand.domain.operation.DecisionRecord;
import io.github.jaeseongs95.opermand.domain.operation.Operation;
import io.github.jaeseongs95.opermand.domain.operation.OperationKind;
import io.github.jaeseongs95.opermand.domain.operation.OperationState;
import io.github.jaeseongs95.opermand.domain.operation.StartAuthority;
import io.github.jaeseongs95.opermand.domain.operation.StateCounts;
import java.time.Instant;
import java.util.Objects;

/**
 * The rules of the approval step, as judgements over values: whether a request may enter, and whether an approved operation may be started. It holds no state of its
 * own and owns neither the queue nor the clock; the moment is an argument, read once by the use case (2.x approval-queue.mjs, the approval policy half of it).
 *
 * <p>The order of a start check is the order of the guards in 2.x {@code start} and {@code checkStart}: maintenance, the state of the request, its deadline, the
 * authority, and for an automatic start the policy's decision to execute. The first guard that refuses names the reason and the step.
 */
public final class ApprovalPolicy {

    private final Capacity requestCapacity;

    /** The policy for an approval store that holds at most {@code requestCapacity} open requests (2.x BRIDGE_REQUEST_CAPACITY, default 100). */
    public ApprovalPolicy(Capacity requestCapacity) {
        this.requestCapacity = Objects.requireNonNull(requestCapacity, "requestCapacity");
    }

    /** The configured capacity for open requests (also bounds in-memory private source plans). */
    public Capacity requestCapacity() {
        return requestCapacity;
    }

    /**
     * Whether a new request may enter. Maintenance refuses first; then the store: it is full when the number of open requests (those not yet in a terminal state)
     * has reached the capacity. A terminal operation is history and does not occupy the store (2.x kept it for an hour in memory).
     */
    public AdmissionDecision admit(MaintenanceState maintenance, StateCounts counts) {
        Objects.requireNonNull(maintenance, "maintenance");
        Objects.requireNonNull(counts, "counts");
        return switch (maintenance) {
            case MaintenanceState.Reserved reserved -> new AdmissionDecision.RejectedMaintenance();
            case MaintenanceState.Open open -> counts.open() >= requestCapacity.value()
                ? new AdmissionDecision.RejectedCapacity(CapacityKind.REQUEST_STORE) : new AdmissionDecision.Admit();
        };
    }

    /** Whether a pending operation may be started now, by the authority (2.x {@code start}). */
    public StartDecision checkStart(Operation operation, StartAuthority authority, MaintenanceState maintenance, Instant at) {
        return check(operation, OperationState.PENDING, authority, maintenance, at);
    }

    /**
     * Whether a claimed operation may cross into execution now: the guards are asked again after the claim, because time and the policy moved (2.x {@code executeScheduled}).
     * The authority is derived from what the journal keeps: an owner approval is the owner's, an automatic start is the server's policy for the scope its stored decision
     * reason stands for ({@link StartAuthority.Policy#of}, defect D-0119). 2.x kept the authority in memory ({@code job.executionAuthority}, approval-queue.mjs:185) and
     * 3.0.0 no longer infers it from the profile or from a missing decision.
     */
    public StartDecision checkDispatch(Operation operation, MaintenanceState maintenance, Instant at) {
        Objects.requireNonNull(operation, "operation");
        StartAuthority authority = switch (operation.approvalMode()) {
            case AUTOMATIC -> StartAuthority.Policy.of(operation.decision() instanceof DecisionRecord.Decided decided ? decided.reason() : DecisionReason.NO_MATCHING_RULE,
                operation.kind());
            case OWNER, NOT_DECIDED -> new StartAuthority.Owner();
        };
        return check(operation, OperationState.STARTING, authority, maintenance, at);
    }

    private StartDecision check(Operation operation, OperationState required, StartAuthority authority, MaintenanceState maintenance, Instant at) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(maintenance, "maintenance");
        Objects.requireNonNull(at, "at");
        if (maintenance instanceof MaintenanceState.Reserved) {
            return refused(DispatchReason.MAINTENANCE, DispatchStage.MAINTENANCE);
        }
        if (operation.state() != required || operation.expiry().isReached(at)) {
            return refused(DispatchReason.REQUEST_STATE, DispatchStage.REQUEST_STATE);
        }
        if (!authority.covers(operation.kind())) {
            return refused(DispatchReason.AUTHORITY, DispatchStage.AUTHORITY);
        }
        boolean automatic = authority.mode() == ApprovalMode.AUTOMATIC;
        if (automatic && !operation.decision().isExecute()) {
            boolean file = operation.kind() == OperationKind.UPLOAD || operation.kind() == OperationKind.DOWNLOAD;
            return refused(DispatchReason.AUTHORITY, file ? DispatchStage.FILE_GUARD : DispatchStage.AUTHORITY);
        }
        return new StartDecision.Startable();
    }

    private static StartDecision refused(DispatchReason reason, DispatchStage stage) {
        return new StartDecision.Refused(DispatchFailure.refused(reason, stage));
    }
}
