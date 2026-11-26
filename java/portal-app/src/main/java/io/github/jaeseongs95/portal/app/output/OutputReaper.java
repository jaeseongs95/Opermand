package io.github.jaeseongs95.portal.app.output;

import java.time.Clock;
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

import io.github.jaeseongs95.portal.app.storage.ReapGate;
import io.github.jaeseongs95.portal.app.storage.ReapPermission;
import io.github.jaeseongs95.portal.app.storage.ReapReport;

/**
 * Removes the command outputs whose retention has ended: the bytes first, then the row, so a crash leaves a row whose bytes are gone, never bytes without a row
 * (2.x {@code reap}). It asks the {@link ReapGate} before every run: while a maintenance reservation holds the installation, or while the guard of the file stores
 * fails, nothing is deleted and the expired bytes stay as they are (RC-B-004: a protection that fails keeps the expired output, and the application does not start without it).
 * An output that cannot be removed is counted and kept for the next run.
 */
@Component
public class OutputReaper {

    private static final Logger LOG = LoggerFactory.getLogger(OutputReaper.class);

    private final OutputManifestStore manifests;
    private final OutputPayloadStore payloads;
    private final ReapGate gate;
    private final Clock clock;
    private final TransactionOperations readTx;
    private final TransactionOperations writeTx;

    @Autowired
    public OutputReaper(OutputManifestStore manifests, OutputPayloadStore payloads, ReapGate gate, Clock clock, PlatformTransactionManager transactionManager) {
        this(manifests, payloads, gate, clock, readOnly(transactionManager), new TransactionTemplate(transactionManager));
    }

    public OutputReaper(OutputManifestStore manifests, OutputPayloadStore payloads, ReapGate gate, Clock clock) {
        this(manifests, payloads, gate, clock, TransactionOperations.withoutTransaction(), TransactionOperations.withoutTransaction());
    }

    private OutputReaper(OutputManifestStore manifests, OutputPayloadStore payloads, ReapGate gate, Clock clock,
                         TransactionOperations readTx, TransactionOperations writeTx) {
        this.manifests = manifests;
        this.payloads = payloads;
        this.gate = gate;
        this.clock = clock;
        this.readTx = readTx;
        this.writeTx = writeTx;
    }

    private static TransactionOperations readOnly(PlatformTransactionManager transactionManager) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setReadOnly(true);
        return tx;
    }

    /** One run. The scheduler calls {@link #reapOnSchedule()}; a caller that wants the report calls this. */
    public ReapReport reap() {
        if (gate.permission() instanceof ReapPermission.Suspended suspended) {
            return new ReapReport.Suspended(suspended.reason());
        }
        int removed = 0;
        int failed = 0;
        List<OutputManifest> expiredOutputs = Objects.requireNonNull(readTx.execute(status -> manifests.expired(clock.instant())), "expired");
        for (OutputManifest expired : expiredOutputs) {
            if (expired.state() == OutputState.PENDING) {
                continue;
            }
            try {
                payloads.delete(expired.id());
                writeTx.executeWithoutResult(status -> manifests.delete(expired.id()));
                removed++;
            } catch (RuntimeException e) {
                failed++;
                LOG.warn("The expired output {} could not be removed and stays until the next run", expired.id().value(), e);
            }
        }
        return new ReapReport.Reaped(removed, failed);
    }

    /** One minute after the start and one minute after each run (the interval is a setting of stage 4). */
    @Scheduled(initialDelay = 60_000L, fixedDelay = 60_000L)
    public void reapOnSchedule() {
        reap();
    }
}
