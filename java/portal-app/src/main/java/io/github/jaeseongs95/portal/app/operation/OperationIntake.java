package io.github.jaeseongs95.portal.app.operation;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

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
import io.github.jaeseongs95.portal.domain.id.OperationIdGenerator;
import io.github.jaeseongs95.portal.domain.id.TargetId;
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
    private final OperationIdGenerator ids;
    private final OperationScheduler scheduler;
    private final ConcurrencyLimits limits;
    private final AdmissionGate gate;
    private final AuditGate audit;
    private final OutcomeAudit outcomes;
    private final OperationWrites writes;
    private final Clock clock;

    public OperationIntake(OperationJournal journal, OperationQueueView queueView, MaintenanceView maintenance, CurrentTargets targets, ApprovalPolicy approvalPolicy,
                           ApprovalJudge judge, OperationIdGenerator ids, OperationScheduler scheduler, ConcurrencyLimits limits, AdmissionGate gate, AuditGate audit, OutcomeAudit outcomes, OperationWrites writes, Clock clock) {
        this.journal = journal;
        this.queueView = queueView;
        this.maintenance = maintenance;
        this.targets = targets;
        this.approvalPolicy = approvalPolicy;
        this.judge = judge;
        this.ids = ids;
        this.scheduler = scheduler;
        this.limits = limits;
        this.gate = gate;
        this.audit = audit;
        this.outcomes = outcomes;
        this.writes = writes;
        this.clock = clock;
    }

    /**
     * Accepts the operation, or returns the one the same request key and contract was accepted as earlier (idempotent, answered before any capacity is looked at). The
     * target must still be at the revision the request holds; maintenance and a full request store refuse before anything is written, each with its own code. The operation
     * is judged by the owner's rule and stored once, in the state the judgement gives: {@code queued}, {@code denied} or {@code pending}.
     *
     * <p>The audit comes first (decision D3-14, as for the owner's approval): the id and the time are made here, the lines {@code submitted} and then {@code execution-queued}
     * or {@code denied} are written, and only then is the row stored, so a row that a dispatcher can claim never exists without its audit, and an audit that does not become
     * durable stores nothing. When the row is not stored after its audit (the room in the queue was gone at the moment of the insert, a lost race, a second line that did not
     * become durable), a {@code not-stored} line is tried once ({@link AuditAction#NOT_STORED}); if that line fails too, its failure is added to the exception that is thrown.
     *
     * <p><b>What a reader of the audit log must know:</b> the journal is the record of an operation. An id that is in the audit log only, with no row in the journal, is an
     * operation that was never stored. A {@code not-stored} line that is left when the outcome of the commit is not known does not overrule the journal either: the journal
     * wins. Nothing cleans such ids up at a restart.
     *
     * @throws RequestRefusedException {@code TARGET_UNAVAILABLE}, {@code TARGET_REVISION_CONFLICT}, {@code MAINTENANCE_ACTIVE}, {@code REQUEST_CAPACITY_EXCEEDED}, the code
     *     of the queue limit that is reached (for a judgement that queues, when the queue is full), {@code OPERATION_KEY_CONFLICT} (the key was used with another contract),
     *     {@code OPERATION_VERSION_CONFLICT} (two operations took the same place in the queue; nothing was stored and nothing was attempted, the answer is retryable, and
     *     sending the same key again is safe because the key is idempotent)
     * @throws DispatchRefusedException the audit did not become durable ({@link DispatchStage#DECISION_AUDIT}); nothing is stored
     */
    public Operation submit(NewOperation spec) {
        ResolvedTarget target = targets.requireAt(spec.targetId(), spec.targetRevision());
        Optional<Operation> known = journal.findByRequestKey(spec.requestKey());
        if (known.isPresent()) {
            if (!known.orElseThrow().hasContract(spec.contractDigest())) {
                throw new RequestRefusedException(RequestErrorCode.OPERATION_KEY_CONFLICT);
            }
            return known.orElseThrow();
        }
        gate.require(approvalPolicy.admit(maintenance.state(), queueView.counts()));
        Admission admission = judge.judge(spec, target);
        Runnable room = admission instanceof Admission.Queue ? () -> requireRoomInQueue(spec.targetId()) : () -> { };
        room.run();
        OperationId id = ids.next();
        Instant at = clock.instant();
        auditBeforeStoring(id, spec, admission);
        JournalInsert inserted;
        try {
            inserted = journal.accept(spec, admission, id, at, room);
        } catch (RuntimeException e) {
            audit.appendAfter(new AuditEvent(AuditAction.NOT_STORED, id, "not stored: " + (e instanceof RequestRefusedException refused ? refused.code().name() : e.getClass().getSimpleName())));
            throw e;
        }
        return switch (inserted) {
            case JournalInsert.Existing existing -> {
                audit.appendAfter(new AuditEvent(AuditAction.NOT_STORED, id, "not stored: the key was stored by another request"));
                yield existing.operation();
            }
            case JournalInsert.Inserted stored -> {
                writes.announce(stored.operation());
                yield stored.operation();
            }
        };
    }

    /**
     * Writes {@code submitted} and then the line of the judgement. A {@code submitted} that fails leaves no line, so nothing needs an end. When a later line fails, the
     * {@code not-stored} line is tried once; if it fails too, that failure is added to the refusal as suppressed and the refusal of the first failure is the one thrown.
     */
    private void auditBeforeStoring(OperationId id, NewOperation spec, Admission admission) {
        audit.appendBefore(DispatchStage.DECISION_AUDIT, new AuditEvent(AuditAction.SUBMITTED, id, "kind " + spec.kind().wire()));
        try {
            switch (admission) {
                case Admission.Queue queue ->
                    audit.appendBefore(DispatchStage.DECISION_AUDIT, new AuditEvent(AuditAction.EXECUTION_QUEUED, id, "authority " + queue.authority().mode().wire()));
                case Admission.Deny deny -> audit.appendBefore(DispatchStage.DECISION_AUDIT, new AuditEvent(AuditAction.DENIED, id, ""));
                case Admission.Review review -> {
                    // Waits for the owner; nothing more to audit.
                }
            }
        } catch (DispatchRefusedException refused) {
            try {
                audit.appendBefore(DispatchStage.DECISION_AUDIT, new AuditEvent(AuditAction.NOT_STORED, id, "not stored: the audit of the judgement did not become durable"));
            } catch (DispatchRefusedException ending) {
                refused.addSuppressed(ending);
            }
            throw refused;
        }
    }

    private void requireRoomInQueue(TargetId target) {
        gate.require(scheduler.admitToQueue(target, queueView.schedulingView(limits, clock.instant(), limits.queueCapacity().value())));
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
        requireRoomInQueue(operation.targetId());
        audit.appendBefore(DispatchStage.START_AUDIT, new AuditEvent(AuditAction.EXECUTION_QUEUED, id, "authority " + authority.mode().wire()));
        // The room is counted again inside the transaction of the write, after the place in the queue was read: an approval, or an intake, that commits in between cannot
        // push the queue over its room. The one that loses the place is told OPERATION_VERSION_CONFLICT (retryable, not attempted) and the operation stays pending.
        return writes.store(operation.enqueue(authority, now), operation.version(), () -> requireRoomInQueue(operation.targetId()));
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
