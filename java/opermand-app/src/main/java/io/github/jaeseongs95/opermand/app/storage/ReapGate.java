package io.github.jaeseongs95.opermand.app.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jaeseongs95.opermand.app.maintenance.MaintenanceStore;

/**
 * The one place that decides whether a reaper may delete (RC-A-173, RC-B-004). Both reapers (artifacts and outputs) ask it before every run, so the two rules are not
 * written twice: a maintenance reservation holds the reap back (the files, the metadata and the accounting must stay as they were until it is released), and so does a
 * guard of the file stores that fails (the portal never deletes bytes in a folder it cannot use). The state is read each time; this class keeps none.
 */
@Component
public class ReapGate {

    private static final Logger LOG = LoggerFactory.getLogger(ReapGate.class);

    private final MaintenanceStore maintenance;
    private final StorageProtection protection;
    private final TransactionOperations readTx;

    @Autowired
    public ReapGate(MaintenanceStore maintenance, StorageProtection protection, PlatformTransactionManager transactionManager) {
        this(maintenance, protection, readOnly(transactionManager));
    }

    public ReapGate(MaintenanceStore maintenance, StorageProtection protection) {
        this(maintenance, protection, TransactionOperations.withoutTransaction());
    }

    private ReapGate(MaintenanceStore maintenance, StorageProtection protection, TransactionOperations readTx) {
        this.maintenance = maintenance;
        this.protection = protection;
        this.readTx = readTx;
    }

    private static TransactionOperations readOnly(PlatformTransactionManager transactionManager) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setReadOnly(true);
        return tx;
    }

    /** Maintenance first in a short read-only transaction (no file I/O), then the protection of the stores outside any transaction. */
    public ReapPermission permission() {
        if (Boolean.TRUE.equals(readTx.execute(status -> maintenance.occupied()))) {
            return new ReapPermission.Suspended(ReapPermission.SuspendReason.MAINTENANCE);
        }
        if (protection.check() instanceof ProtectionCheck.Unprotected unprotected) {
            LOG.error("The {} folder cannot be used; no expired bytes are deleted", unprotected.area(), unprotected.cause());
            return new ReapPermission.Suspended(ReapPermission.SuspendReason.PROTECTION_FAILED);
        }
        return new ReapPermission.Allowed();
    }
}
