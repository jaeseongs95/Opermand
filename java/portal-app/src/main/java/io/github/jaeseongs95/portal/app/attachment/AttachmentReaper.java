package io.github.jaeseongs95.portal.app.attachment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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

import io.github.jaeseongs95.portal.app.storage.ReapGate;
import io.github.jaeseongs95.portal.app.storage.ReapPermission;
import io.github.jaeseongs95.portal.app.storage.ReapReport;
import io.github.jaeseongs95.portal.domain.attachment.AttachmentId;

/**
 * Quarantines orphaned attachment files that were written before a crash or rollback and have no row in the {@code attachment} table (stage 4 design B-15, S4-AT-04,
 * S4-AT-07).
 *
 * <p>Safety invariants:
 * <ul>
 *   <li>Asks {@link ReapGate} before every run (maintenance reservation or failed storage protection halts the run).</li>
 *   <li>Protects in-flight writes (including imports) with a grace window ({@value #DEFAULT_GRACE_HOURS} hour by default): files newer than the grace window are never touched.</li>
 *   <li>Checks the {@code attachment} table in a short read-only transaction and <strong>never</strong> deletes or quarantines any file that has a metadata row (history is permanent, C2-1).</li>
 *   <li>Moves orphans to quarantine instead of permanently deleting them.</li>
 * </ul>
 */
@Component
public class AttachmentReaper {

    private static final Logger LOG = LoggerFactory.getLogger(AttachmentReaper.class);
    static final long DEFAULT_GRACE_HOURS = 1L;
    public static final Duration DEFAULT_GRACE_PERIOD = Duration.ofHours(DEFAULT_GRACE_HOURS);

    private final AttachmentStore attachments;
    private final ReapGate gate;
    private final Clock clock;
    private final Duration gracePeriod;
    private final TransactionOperations readTx;

    @Autowired
    public AttachmentReaper(AttachmentStore attachments, ReapGate gate, Clock clock, PlatformTransactionManager transactionManager) {
        this(attachments, gate, clock, DEFAULT_GRACE_PERIOD, readOnly(transactionManager));
    }

    public AttachmentReaper(AttachmentStore attachments, ReapGate gate, Clock clock) {
        this(attachments, gate, clock, DEFAULT_GRACE_PERIOD, TransactionOperations.withoutTransaction());
    }

    public AttachmentReaper(AttachmentStore attachments, ReapGate gate, Clock clock, Duration gracePeriod) {
        this(attachments, gate, clock, gracePeriod, TransactionOperations.withoutTransaction());
    }

    private AttachmentReaper(AttachmentStore attachments, ReapGate gate, Clock clock, Duration gracePeriod, TransactionOperations readTx) {
        this.attachments = Objects.requireNonNull(attachments, "attachments");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.gracePeriod = Objects.requireNonNull(gracePeriod, "gracePeriod");
        if (gracePeriod.isNegative()) {
            throw new IllegalArgumentException("gracePeriod must not be negative");
        }
        this.readTx = Objects.requireNonNull(readTx, "readTx");
    }

    private static TransactionOperations readOnly(PlatformTransactionManager transactionManager) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setReadOnly(true);
        return tx;
    }

    /** One orphan-quarantine pass. Respects {@link ReapGate}, keeps all DB I/O short and read-only, and performs file I/O outside any transaction. */
    public ReapReport reap() {
        if (gate.permission() instanceof ReapPermission.Suspended suspended) {
            return new ReapReport.Suspended(suspended.reason());
        }
        Instant cutoff = clock.instant().minus(gracePeriod);
        Set<AttachmentId> candidates = attachments.storedFilesOlderThan(cutoff);
        if (candidates.isEmpty()) {
            return new ReapReport.Reaped(0, 0);
        }
        Set<AttachmentId> referenced = Objects.requireNonNull(readTx.execute(status -> attachments.existingMetadataIds(candidates)), "referenced");
        int quarantined = 0;
        int failed = 0;
        for (AttachmentId candidate : candidates) {
            if (referenced.contains(candidate)) {
                continue;
            }
            try {
                attachments.quarantine(candidate);
                quarantined++;
                LOG.warn("Orphaned attachment file {} older than {} had no metadata row and was moved to quarantine", candidate.value(), gracePeriod);
            } catch (RuntimeException e) {
                failed++;
                LOG.warn("Orphaned attachment file {} could not be moved to quarantine and stays until the next run", candidate.value(), e);
            }
        }
        return new ReapReport.Reaped(quarantined, failed);
    }

    @Scheduled(initialDelay = 300_000L, fixedDelay = 300_000L)
    public void reapOnSchedule() {
        reap();
    }
}
