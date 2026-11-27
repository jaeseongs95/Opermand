package io.github.jaeseongs95.opermand.app.artifact;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jaeseongs95.opermand.app.storage.ReapGate;
import io.github.jaeseongs95.opermand.app.storage.ReapPermission;
import io.github.jaeseongs95.opermand.app.storage.ReapReport;

/**
 * Removes the artifacts whose time has come: the bytes, the row and the charge of each (2.x {@code reap}). It asks the {@link ReapGate} before every run, so a
 * maintenance reservation (RC-A-173) and a failing guard of the file stores (RC-B-004) hold the whole run back: nothing is deleted and nothing is un-charged until they are
 * gone. An artifact that is pinned by a submitted operation is never listed by the registry; a consumed one has no bytes and loses only its row.
 *
 * <p>A row that stayed {@code CONSUMING} is finished too (defect D-0012: a failed settlement used to charge the file for good). The order for the others is the registry's: the row goes to {@code DELETING} first, then the bytes are deleted, then the row is released, so a crash in the middle
 * leaves a row that the next run, or the start-up check, finishes: a row in {@code DELETING} is removed again whatever its expiry (defect D-0012: a failed discard of a download
 * used to leave its charge in the budget until the next start). One artifact that cannot be removed is counted and kept for the next run; it does not stop the run. A row that
 * another use case removed meanwhile (a discard that finished after the list was taken) counts as removed. A row in {@code DELETING} is released as {@code ABANDONED}, not as
 * {@code EXPIRED}, because it did not expire.
 */
@Component
public class ArtifactReaper {

    private static final Logger LOG = LoggerFactory.getLogger(ArtifactReaper.class);

    private final ArtifactRegistry registry;
    private final ArtifactPayloadStore payloads;
    private final ReapGate gate;
    private final Clock clock;
    private final QuarantineNotice quarantine;
    private final TransactionOperations readTx;
    private final TransactionOperations writeTx;

    @Autowired
    public ArtifactReaper(ArtifactRegistry registry, ArtifactPayloadStore payloads, ReapGate gate, Clock clock, QuarantineNotice quarantine,
                          PlatformTransactionManager transactionManager) {
        this(registry, payloads, gate, clock, quarantine, readOnly(transactionManager), new TransactionTemplate(transactionManager));
    }

    public ArtifactReaper(ArtifactRegistry registry, ArtifactPayloadStore payloads, ReapGate gate, Clock clock, QuarantineNotice quarantine) {
        this(registry, payloads, gate, clock, quarantine, TransactionOperations.withoutTransaction(), TransactionOperations.withoutTransaction());
    }

    private ArtifactReaper(ArtifactRegistry registry, ArtifactPayloadStore payloads, ReapGate gate, Clock clock, QuarantineNotice quarantine,
                           TransactionOperations readTx, TransactionOperations writeTx) {
        this.registry = registry;
        this.payloads = payloads;
        this.gate = gate;
        this.clock = clock;
        this.quarantine = quarantine;
        this.readTx = readTx;
        this.writeTx = writeTx;
    }

    private static TransactionOperations readOnly(PlatformTransactionManager transactionManager) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setReadOnly(true);
        return tx;
    }

    /** One run. The scheduler calls {@link #reapOnSchedule()}; a caller that wants the report (a test, the lifecycle) calls this. */
    public ReapReport reap() {
        if (gate.permission() instanceof ReapPermission.Suspended suspended) {
            return new ReapReport.Suspended(suspended.reason());
        }
        int removed = 0;
        int failed = 0;
        List<ArtifactRecord> due = Objects.requireNonNull(readTx.execute(status -> {
            List<ArtifactRecord> items = new ArrayList<>(registry.deleting());
            items.addAll(registry.consuming());
            items.addAll(registry.expired(clock.instant()));
            return items;
        }), "due");
        for (ArtifactRecord artifact : due) {
            try {
                remove(artifact);
                removed++;
            } catch (RuntimeException e) {
                failed++;
                LOG.warn("The artifact {} could not be removed and stays until the next run", artifact.id().value(), e);
            }
        }
        quarantine.log();
        return new ReapReport.Reaped(removed, failed);
    }

    /** One minute after the start and one minute after each run (2.x reaped on a timer; the interval is a setting of stage 4). */
    @Scheduled(initialDelay = 60_000L, fixedDelay = 60_000L)
    public void reapOnSchedule() {
        reap();
    }

    private void remove(ArtifactRecord due) {
        if (due.state() == ArtifactState.CONSUMING && due.consumption() instanceof ConsumeState.Consuming consuming) {
            payloads.delete(due.id());
            writeTx.executeWithoutResult(status -> registry.completeConsume(due.id(), consuming.reason()));
            return;
        }
        ReleaseReason reason = due.state() == ArtifactState.DELETING ? ReleaseReason.ABANDONED : ReleaseReason.EXPIRED;
        if (due.state() != ArtifactState.CONSUMED) {
            if (!Boolean.TRUE.equals(writeTx.execute(status -> registry.beginDeleteIfPresent(due.id())))) {
                LOG.debug("The artifact {} was removed by another use case before the reap took it", due.id().value());
                return;
            }
            payloads.delete(due.id());
        }
        writeTx.executeWithoutResult(status -> registry.release(due.id(), reason));
    }
}
