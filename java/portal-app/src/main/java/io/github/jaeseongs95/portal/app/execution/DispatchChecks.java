package io.github.jaeseongs95.portal.app.execution;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jaeseongs95.portal.app.audit.AuditAction;
import io.github.jaeseongs95.portal.app.audit.AuditEvent;
import io.github.jaeseongs95.portal.app.event.OperationEvents;
import io.github.jaeseongs95.portal.app.journal.OperationJournal;
import io.github.jaeseongs95.portal.app.operation.ApprovalJudge;
import io.github.jaeseongs95.portal.app.operation.AuditGate;
import io.github.jaeseongs95.portal.app.operation.MaintenanceView;
import io.github.jaeseongs95.portal.app.target.TargetSource;
import io.github.jaeseongs95.portal.domain.approval.ApprovalPolicy;
import io.github.jaeseongs95.portal.domain.approval.MaintenanceState;
import io.github.jaeseongs95.portal.domain.approval.StartDecision;
import io.github.jaeseongs95.portal.domain.error.DispatchFailure;
import io.github.jaeseongs95.portal.domain.error.DispatchReason;
import io.github.jaeseongs95.portal.domain.error.DispatchRefusedException;
import io.github.jaeseongs95.portal.domain.error.DispatchStage;
import io.github.jaeseongs95.portal.domain.error.OperationErrorCode;
import io.github.jaeseongs95.portal.domain.error.RequestRefusedException;
import io.github.jaeseongs95.portal.domain.id.OperationId;
import io.github.jaeseongs95.portal.domain.operation.ApprovalMode;
import io.github.jaeseongs95.portal.domain.operation.Operation;
import io.github.jaeseongs95.portal.domain.operation.OperationState;
import io.github.jaeseongs95.portal.domain.target.TargetLookup;

/**
 * The last check before an operation crosses to its target (stage 3 design 3.2 rows P0 and P3, 3.5 point 8). The adapter calls it through the {@code DispatchGuard} after
 * the connection is open and before it sends anything that changes the target, so a target or a policy that changed while the connection was being made is never
 * reached under the old approval. It asks again what the claim already asked, because the time and the world moved: the state of the operation, its deadline,
 * maintenance, the authority, the target and its revision, and the current target policy rule (re-evaluating deny lists across all paths and automatic approval
 * retention for automatic operations, defect D-0121).
 *
 * <p>When every check passes it writes the start audit ({@code approved}, or {@code policy-execution-started} for an automatic start; fail-closed, so an audit that
 * does not become durable stops the operation with {@code AUDIT_FAILED} at {@code START_AUDIT}, decision D3-14), and then stores {@code RUNNING}, so the journal says the
 * effect has begun before the first byte is sent; a failure to store it stops the operation with {@code PERSISTENCE_FAILED} at the start. Nothing has been sent when
 * this throws.
 */
@Component
public class DispatchChecks {

    private final OperationJournal journal;
    private final TargetSource targets;
    private final MaintenanceView maintenance;
    private final ApprovalPolicy approvalPolicy;
    private final ApprovalJudge judge;
    private final AuditGate audit;
    private final OperationEvents events;
    private final Clock clock;
    private final TransactionOperations readTx;
    private final TransactionOperations writeTx;

    @Autowired
    public DispatchChecks(OperationJournal journal, TargetSource targets, MaintenanceView maintenance, ApprovalPolicy approvalPolicy, ApprovalJudge judge,
                          AuditGate audit, OperationEvents events, Clock clock, PlatformTransactionManager transactionManager) {
        this(journal, targets, maintenance, approvalPolicy, judge, audit, events, clock, readOnly(transactionManager), new TransactionTemplate(transactionManager));
    }

    public DispatchChecks(OperationJournal journal, TargetSource targets, MaintenanceView maintenance, ApprovalPolicy approvalPolicy, ApprovalJudge judge,
                          AuditGate audit, OperationEvents events, Clock clock) {
        this(journal, targets, maintenance, approvalPolicy, judge, audit, events, clock,
            TransactionOperations.withoutTransaction(), TransactionOperations.withoutTransaction());
    }

    private DispatchChecks(OperationJournal journal, TargetSource targets, MaintenanceView maintenance, ApprovalPolicy approvalPolicy, ApprovalJudge judge,
                           AuditGate audit, OperationEvents events, Clock clock, TransactionOperations readTx, TransactionOperations writeTx) {
        this.journal = journal;
        this.targets = targets;
        this.maintenance = maintenance;
        this.approvalPolicy = approvalPolicy;
        this.judge = judge;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.readTx = readTx;
        this.writeTx = writeTx;
    }

    private static TransactionOperations readOnly(PlatformTransactionManager transactionManager) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setReadOnly(true);
        return tx;
    }

    /** Creates a stateful guard for one execution of {@code id}, so multi-step runners check and record the start once before the first remote step (defect D-0149). */
    StartGuard guardFor(OperationId id) {
        return new StartGuard(this, id);
    }

    /** @throws DispatchRefusedException a guard refused: the operation was cancelled meanwhile, the target or the policy changed, maintenance began, or the start could not be stored */
    public void beforeDispatch(OperationId id) {
        record DispatchSnapshot(Operation operation, MaintenanceState maintenance) {
        }
        DispatchSnapshot snapshot = Objects.requireNonNull(readTx.execute(status -> new DispatchSnapshot(
            journal.find(id).orElseThrow(() -> refused(DispatchReason.REQUEST_STATE, DispatchStage.REQUEST_STATE)),
            maintenance.state())), "snapshot");
        Operation operation = snapshot.operation();
        Instant now = clock.instant();
        if (operation.state() != OperationState.STARTING) {
            throw refused(DispatchReason.REQUEST_STATE, DispatchStage.REQUEST_STATE);
        }
        TargetLookup lookup = targets.lookup(operation.targetId());
        if (!(lookup instanceof TargetLookup.Found found) || !found.target().revision().equals(operation.targetRevision())) {
            throw refused(DispatchReason.TARGET_CHANGED, DispatchStage.TARGET_GUARD);
        }
        if (approvalPolicy.checkDispatch(operation, snapshot.maintenance(), now) instanceof StartDecision.Refused refusedStart) {
            throw new DispatchRefusedException(refusedStart.failure());
        }
        judge.checkBeforeDispatch(operation, found.target());
        audit.appendBefore(DispatchStage.START_AUDIT, new AuditEvent(operation.approvalMode() == ApprovalMode.AUTOMATIC ? AuditAction.POLICY_EXECUTION_STARTED
            : AuditAction.APPROVED, id, "start"));
        Operation stored;
        try {
            stored = Objects.requireNonNull(writeTx.execute(status -> journal.save(operation.markRunning(now), operation.version())), "stored");
        } catch (RequestRefusedException e) {
            if (e.code() == OperationErrorCode.OPERATION_VERSION_CONFLICT) {
                throw refused(DispatchReason.REQUEST_STATE, DispatchStage.REQUEST_STATE);
            }
            throw new DispatchRefusedException(DispatchFailure.stepFailed(DispatchStage.START_PERSISTENCE, DispatchReason.PERSISTENCE_FAILED), e);
        } catch (RuntimeException e) {
            throw new DispatchRefusedException(DispatchFailure.stepFailed(DispatchStage.START_PERSISTENCE, DispatchReason.PERSISTENCE_FAILED), e);
        }
        events.changed(stored);
    }

    private static DispatchRefusedException refused(DispatchReason reason, DispatchStage stage) {
        return new DispatchRefusedException(DispatchFailure.refused(reason, stage));
    }
}
