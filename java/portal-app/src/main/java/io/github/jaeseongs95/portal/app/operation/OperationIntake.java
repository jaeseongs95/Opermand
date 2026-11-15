package io.github.jaeseongs95.portal.app.operation;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;

import io.github.jaeseongs95.portal.app.admission.AdmissionGate;
import io.github.jaeseongs95.portal.app.audit.AuditAction;
import io.github.jaeseongs95.portal.app.audit.AuditEvent;
import io.github.jaeseongs95.portal.app.journal.JournalInsert;
import io.github.jaeseongs95.portal.app.journal.OperationJournal;
import io.github.jaeseongs95.portal.app.journal.OperationQueueView;
import io.github.jaeseongs95.portal.domain.approval.ApprovalPolicy;
import io.github.jaeseongs95.portal.domain.approval.StartDecision;
import io.github.jaeseongs95.portal.domain.error.DispatchFailure;
import io.github.jaeseongs95.portal.domain.error.DispatchReason;
import io.github.jaeseongs95.portal.domain.error.DispatchRefusedException;
import io.github.jaeseongs95.portal.domain.error.DispatchStage;
import io.github.jaeseongs95.portal.domain.error.RequestErrorCode;
import io.github.jaeseongs95.portal.domain.error.RequestRefusedException;
import io.github.jaeseongs95.portal.domain.id.OperationId;
import io.github.jaeseongs95.portal.domain.operation.Admission;
import io.github.jaeseongs95.portal.domain.operation.NewOperation;
import io.github.jaeseongs95.portal.domain.operation.Operation;
import io.github.jaeseongs95.portal.domain.operation.OperationState;
import io.github.jaeseongs95.portal.domain.operation.StartAuthority;
import io.github.jaeseongs95.portal.domain.schedule.ConcurrencyLimits;
import io.github.jaeseongs95.portal.domain.schedule.OperationScheduler;
import io.github.jaeseongs95.portal.domain.target.ResolvedTarget;

/**
 * How an operation enters and is approved (stage 3 design 8.2 groups G5 and G6, cases B-233 to B-239 and A-142 to A-145). The domain decides everything it can
 * ({@link ApprovalPolicy}, {@link OperationScheduler}, {@link Operation}); this class reads the facts, asks, writes and announces.
 *
 * <ul>
 *   <li>{@link #submit}: an operation is accepted once per request key (the journal makes the key unique, so two callers with the same key and contract get the same
 *       operation and one admission). The owner's rule judges it at once ({@link ApprovalJudge}) and the first stored state is the judgement: queued, denied, or pending
 *       (defect D-0119). A queued operation needs room in the execution queue before anything is written; a full queue is a capacity error and nothing is stored.
 *   <li>{@link #approve}: the owner's button for a pending operation: the guards of the start, the room in the execution queue, then the audit that must be durable
 *       <em>before</em> the operation is queued (fail-closed, decision D3-14), then the queue.
 *   <li>{@link #deny}: the owner refuses a pending operation; the audit of the outcome follows the stored outcome.
 * </ul>
 */
@Service
public class OperationIntake {

    private final OperationJournal journal;
    private final OperationQueueView queueView;
    private final MaintenanceView maintenance;
    private final CurrentTargets targets;
    private final ApprovalPolicy approvalPolicy;
    private final ApprovalJudge judge;
    private final OperationScheduler scheduler;
    private final ConcurrencyLimits limits;
    private final AdmissionGate gate;
    private final AuditGate audit;
    private final OutcomeAudit outcomes;
    private final OperationWrites writes;
    private final Clock clock;

    public OperationIntake(OperationJournal journal, OperationQueueView queueView, MaintenanceView maintenance, CurrentTargets targets, ApprovalPolicy approvalPolicy,
                           ApprovalJudge judge, OperationScheduler scheduler, ConcurrencyLimits limits, AdmissionGate gate, AuditGate audit, OutcomeAudit outcomes, OperationWrites writes, Clock clock) {
        this.journal = journal;
        this.queueView = queueView;
        this.maintenance = maintenance;
        this.targets = targets;
        this.approvalPolicy = approvalPolicy;
        this.judge = judge;
        this.scheduler = scheduler;
        this.limits = limits;
        this.gate = gate;
        this.audit = audit;
        this.outcomes = outcomes;
        this.writes = writes;
        this.clock = clock;
    }

    /**
     * Accepts the operation, or returns the one the same request key and contract was accepted as earlier (idempotent). The target must still be at the revision the
     * request holds; maintenance and a full request store refuse before anything is written, each with its own code. The operation is judged by the owner's rule and
     * stored once, in the state the judgement gives: {@code queued} (the audit lines are {@code submitted}, then {@code queued}), {@code denied} ({@code submitted}, then
     * {@code denied}) or {@code pending} ({@code submitted}). The audit lines of a new operation are written after the row, because its id is made by the journal.
     *
     * @throws RequestRefusedException {@code TARGET_UNAVAILABLE}, {@code TARGET_REVISION_CONFLICT}, {@code MAINTENANCE_ACTIVE}, {@code REQUEST_CAPACITY_EXCEEDED}, the code
     *     of the queue limit that is reached (for a judgement that queues), {@code OPERATION_KEY_CONFLICT} (the key was used with another contract)
     * @throws DispatchRefusedException the audit of the queueing did not become durable ({@link DispatchStage#START_AUDIT}); the operation is stored as cancelled
     */
    public Operation submit(NewOperation spec) {
        ResolvedTarget target = targets.requireAt(spec.targetId(), spec.targetRevision());
        gate.require(approvalPolicy.admit(maintenance.state(), queueView.counts()));
        Admission admission = judge.judge(spec, target);
        if (admission instanceof Admission.Queue) {
            gate.require(scheduler.admitToQueue(spec.targetId(), queueView.schedulingView(limits, clock.instant(), limits.queueCapacity().value())));
        }
        return switch (journal.accept(spec, admission)) {
            case JournalInsert.Existing existing -> existing.operation();
            case JournalInsert.Inserted inserted -> {
                Operation stored = inserted.operation();
                outcomes.recordEvent(stored, new AuditEvent(AuditAction.SUBMITTED, stored.id(), "kind " + stored.kind().wire()));
                switch (admission) {
                    case Admission.Queue queue -> {
                        // Fail-closed (decision D3-14): the audit of the queueing must be durable before the dispatcher is woken. An operation that cannot be audited is
                        // cancelled, so it cannot start later, and the refusal is the caller's.
                        try {
                            audit.appendBefore(DispatchStage.START_AUDIT, new AuditEvent(AuditAction.EXECUTION_QUEUED, stored.id(), "authority " + queue.authority().mode().wire()));
                        } catch (DispatchRefusedException e) {
                            Operation current = journal.find(stored.id()).orElse(stored);
                            outcomes.recordOutcome(writes.store(current.requestCancel(clock.instant()), current.version()));
                            throw e;
                        }
                    }
                    case Admission.Deny deny -> outcomes.recordOutcome(journal.find(stored.id()).orElse(stored));
                    case Admission.Review review -> {
                        // Waits for the owner; nothing more to audit.
                    }
                }
                Operation current = journal.find(stored.id()).orElse(stored);
                writes.announce(current);
                yield current;
            }
        };
    }

    /**
     * Queues a pending operation on the owner's approval (the button of the management screen). The refusal of a guard is a {@code DispatchRefusedException} that names
     * the reason and the step; an operation that is not pending, such as one the rule already queued, is refused at {@code REQUEST_STATE}. An audit that did not become
     * durable stops the operation at {@link DispatchStage#START_AUDIT} and leaves it pending (2.x wrote {@code execution-queued} at that step).
     *
     * @throws RequestRefusedException {@code OPERATION_NOT_FOUND}, {@code TARGET_REVISION_CONFLICT}, or the code of the queue limit that is reached
     * @throws DispatchRefusedException a start guard refused, or the audit failed
     */
    public Operation approve(OperationId id) {
        StartAuthority authority = new StartAuthority.Owner();
        Operation operation = require(id);
        targets.requireAt(operation.targetId(), operation.targetRevision());
        Instant now = clock.instant();
        if (approvalPolicy.checkStart(operation, authority, maintenance.state(), now) instanceof StartDecision.Refused refused) {
            throw new DispatchRefusedException(refused.failure());
        }
        gate.require(scheduler.admitToQueue(operation.targetId(), queueView.schedulingView(limits, now, limits.queueCapacity().value())));
        audit.appendBefore(DispatchStage.START_AUDIT, new AuditEvent(AuditAction.EXECUTION_QUEUED, id, "authority " + authority.mode().wire()));
        return writes.store(operation.enqueue(authority, now), operation.version());
    }

    /** The owner refuses a pending operation; nothing ran. Any other state is refused at {@code REQUEST_STATE}: a queued operation is cancelled, not denied. */
    public Operation deny(OperationId id) {
        Operation operation = require(id);
        if (operation.state() != OperationState.PENDING) {
            throw new DispatchRefusedException(DispatchFailure.refused(DispatchReason.REQUEST_STATE, DispatchStage.REQUEST_STATE));
        }
        Operation stored = writes.store(operation.deny(clock.instant()), operation.version());
        outcomes.recordOutcome(stored);
        return stored;
    }

    private Operation require(OperationId id) {
        return journal.find(id).orElseThrow(() -> new RequestRefusedException(RequestErrorCode.OPERATION_NOT_FOUND));
    }
}
