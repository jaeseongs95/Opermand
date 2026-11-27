package io.github.jaeseongs95.opermand.app.recovery;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jaeseongs95.opermand.app.artifact.ArtifactOrigin;
import io.github.jaeseongs95.opermand.app.artifact.ArtifactRecord;
import io.github.jaeseongs95.opermand.app.artifact.ArtifactRegistry;
import io.github.jaeseongs95.opermand.app.artifact.ArtifactRetention;
import io.github.jaeseongs95.opermand.app.artifact.ArtifactSubmission;
import io.github.jaeseongs95.opermand.app.artifact.StagedAction;
import io.github.jaeseongs95.opermand.app.artifact.StagedActionState;
import io.github.jaeseongs95.opermand.app.file.StagedFileService;
import io.github.jaeseongs95.opermand.app.journal.OperationJournal;
import io.github.jaeseongs95.opermand.app.operation.TerminalNotifier;
import io.github.jaeseongs95.opermand.app.storage.ReapGate;
import io.github.jaeseongs95.opermand.app.storage.ReapPermission;
import io.github.jaeseongs95.opermand.domain.id.OperationId;
import io.github.jaeseongs95.opermand.domain.operation.Operation;

/**
 * Settles what an operation that ended left half done, from what is stored and not from what the memory of the last run knew (defect D-0157). When an operation reaches a
 * final state the {@link TerminalNotifier} tells its listeners, and they unpin the artifact of an upload ({@code UploadArtifactRelease}) and settle the action of a staged file
 * ({@code StagedActionSettlement}). That is a call in memory: an application that stops between the stored final state and the call, or a listener that fails, leaves the artifact
 * {@code PINNED} for good (nothing removes a pinned artifact, its bytes stay charged) and the action {@code SUBMITTED} for good (the staged file is busy and a move is never
 * consumed). This class looks for exactly those leftovers: an artifact that is pinned by an operation, and an action that is submitted for an operation, whose operation is in a
 * final state, and tells the same listeners again. They are idempotent by contract, so telling again is harmless.
 *
 * <p>It runs once at the start ({@link #settleAfterRestart()}, called by {@link StartupRecovery} after the journal was recovered and before the dispatcher opens, so no operation
 * ends meanwhile) and again every minute ({@link #sweepOnSchedule()}), for operations that ended more than a minute ago: a younger one is still being told by the call that
 * ended it. An operation that is {@code UNKNOWN} is not final and keeps its artifact: the owner may need the bytes to compare with the target. A maintenance reservation, or a
 * guard of the file stores that fails, holds the sweep back like it holds the reaper back, because settling a move deletes bytes.
 */
@Component
public class SettlementRecovery {

    private static final Logger LOG = LoggerFactory.getLogger(SettlementRecovery.class);
    /** How long an operation that ended is left to the call that ended it, before the sweep tells its listeners again. */
    static final Duration SETTLE_GRACE = Duration.ofMinutes(1);

    private final StagedFileService staged;
    private final ArtifactRegistry registry;
    private final OperationJournal journal;
    private final TerminalNotifier terminal;
    private final ReapGate gate;
    private final Clock clock;
    private final TransactionOperations readTx;

    @Autowired
    public SettlementRecovery(StagedFileService staged, ArtifactRegistry registry, OperationJournal journal, TerminalNotifier terminal, ReapGate gate, Clock clock,
                              PlatformTransactionManager transactionManager) {
        this(staged, registry, journal, terminal, gate, clock, readOnly(transactionManager));
    }

    public SettlementRecovery(StagedFileService staged, ArtifactRegistry registry, OperationJournal journal, TerminalNotifier terminal, ReapGate gate, Clock clock) {
        this(staged, registry, journal, terminal, gate, clock, TransactionOperations.withoutTransaction());
    }

    private SettlementRecovery(StagedFileService staged, ArtifactRegistry registry, OperationJournal journal, TerminalNotifier terminal, ReapGate gate, Clock clock,
                               TransactionOperations readTx) {
        this.staged = staged;
        this.registry = registry;
        this.journal = journal;
        this.terminal = terminal;
        this.gate = gate;
        this.clock = clock;
        this.readTx = readTx;
    }

    private static TransactionOperations readOnly(PlatformTransactionManager transactionManager) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setReadOnly(true);
        return tx;
    }

    /**
     * The settlement of the start: the reserved actions that the stop cut off (submitted when their operation reached the journal, refused when it did not), then every
     * leftover of an operation that ended. Returns how many operations the listeners were told about.
     */
    public int settleAfterRestart() {
        int reservations = staged.recoverReservations();
        int told = sweep(clock.instant());
        LOG.info("The settlement after the start: {} reserved action(s) of staged files were settled, {} ended operation(s) were told to their listeners again", reservations, told);
        return told;
    }

    /** One minute after the start and one minute after each sweep. */
    @Scheduled(initialDelay = 90_000L, fixedDelay = 60_000L)
    public void sweepOnSchedule() {
        sweep(clock.instant().minus(SETTLE_GRACE));
    }

    /**
     * Tells the listeners of every operation that is in a final state, was last changed no later than {@code endedBefore}, and still holds an artifact or an action. A failure
     * of one operation is logged and does not stop the others.
     *
     * @param endedBefore the latest time an operation may have last changed to be told about
     * @return how many operations the listeners were told about
     */
    public int sweep(Instant endedBefore) {
        if (gate.permission() instanceof ReapPermission.Suspended suspended) {
            LOG.debug("The settlement sweep is held back: {}", suspended.reason());
            return 0;
        }
        Set<OperationId> held = Objects.requireNonNull(readTx.execute(status -> {
            Set<OperationId> ids = new LinkedHashSet<>();
            for (ArtifactRecord record : registry.holding()) {
                ids.addAll(operationsHeldBy(record));
            }
            return ids;
        }), "held");
        int told = 0;
        for (OperationId id : held) {
            told += tellIfEnded(id, endedBefore);
        }
        return told;
    }

    /** The operations that an artifact depends on: the one that pinned it, and the ones its submitted actions wait for. */
    private Set<OperationId> operationsHeldBy(ArtifactRecord record) {
        Set<OperationId> held = new LinkedHashSet<>();
        if (record.spec().retention() == ArtifactRetention.PINNED && record.submission() instanceof ArtifactSubmission.SubmittedAs submitted) {
            held.add(submitted.operationId());
        }
        if (record.spec().origin() instanceof ArtifactOrigin.Staged) {
            for (StagedAction action : registry.actions(record.id())) {
                if (action.state() == StagedActionState.SUBMITTED) {
                    held.add(action.requestId());
                }
            }
        }
        return held;
    }

    private int tellIfEnded(OperationId id, Instant endedBefore) {
        try {
            Operation operation = readTx.execute(status -> journal.find(id).orElse(null));
            if (operation == null || !operation.state().isFinal() || operation.updatedAt().isAfter(endedBefore)) {
                return 0;
            }
            terminal.ended(operation);
            return 1;
        } catch (RuntimeException e) {
            LOG.warn("The settlement of the ended operation {} failed; it is tried again by the next sweep", id.value(), e);
            return 0;
        }
    }
}
