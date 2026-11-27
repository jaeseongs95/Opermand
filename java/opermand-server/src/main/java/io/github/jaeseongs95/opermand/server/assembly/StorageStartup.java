package io.github.jaeseongs95.opermand.server.assembly;

import java.time.Clock;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jaeseongs95.opermand.app.lifecycle.StartupProtection;
import io.github.jaeseongs95.opermand.app.lifecycle.StartupSequence;
import io.github.jaeseongs95.opermand.app.lifecycle.StartupStep;
import io.github.jaeseongs95.opermand.app.maintenance.MaintenanceStore;
import io.github.jaeseongs95.opermand.store.artifact.ArtifactStartupCheck;
import io.github.jaeseongs95.opermand.store.output.OutputStartupCheck;

/**
 * Reconciles the file stores with their metadata once, at the start (design 8.5): payload files without a row go to quarantine, a byte tail that was never acknowledged is cut
 * off, a deletion that was begun is finished, an output that was pending is given up. It lives in the server because the checks belong to opermand-store and the guard to
 * opermand-app; only the assembly holds both.
 *
 * <p>The guard comes first: when the protection of a store fails, {@link StartupProtection#verify()} stops the start before a file is read (RC-B-004). While a maintenance
 * reservation holds the installation (it is stored, so it survives a restart) the expired outputs are not deleted either: only the recovery that deletes nothing runs
 * (RC-A-173).
 *
 * <p>It is a {@link StartupStep} that runs after the protection check and before the recovery of the journal, which opens the dispatcher ({@link StartupSequence} runs the
 * steps by their {@link Order}, defect D-0159): a command that starts reserves an output, and the outputs that the last run left pending must be given up before it, not after.
 */
@Component
@Order(StartupStep.STORAGE)
public class StorageStartup implements StartupStep {

    private final StartupProtection startupProtection;
    private final MaintenanceStore maintenance;
    private final ArtifactStartupCheck artifacts;
    private final OutputStartupCheck outputs;
    private final Clock clock;
    private final TransactionOperations readTx;
    private final TransactionOperations writeTx;

    @Autowired
    public StorageStartup(StartupProtection startupProtection, MaintenanceStore maintenance, ArtifactStartupCheck artifacts, OutputStartupCheck outputs, Clock clock,
                          PlatformTransactionManager transactionManager) {
        this(startupProtection, maintenance, artifacts, outputs, clock, readOnly(transactionManager), new TransactionTemplate(transactionManager));
    }

    public StorageStartup(StartupProtection startupProtection, MaintenanceStore maintenance, ArtifactStartupCheck artifacts, OutputStartupCheck outputs, Clock clock) {
        this(startupProtection, maintenance, artifacts, outputs, clock, TransactionOperations.withoutTransaction(), TransactionOperations.withoutTransaction());
    }

    private StorageStartup(StartupProtection startupProtection, MaintenanceStore maintenance, ArtifactStartupCheck artifacts, OutputStartupCheck outputs, Clock clock,
                           TransactionOperations readTx, TransactionOperations writeTx) {
        this.startupProtection = startupProtection;
        this.maintenance = maintenance;
        this.artifacts = artifacts;
        this.outputs = outputs;
        this.clock = clock;
        this.readTx = readTx;
        this.writeTx = writeTx;
    }

    private static TransactionOperations readOnly(PlatformTransactionManager transactionManager) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setReadOnly(true);
        return tx;
    }

    @Override
    public void run() {
        startupProtection.verify();
        Consumer<Runnable> inTransaction = work -> writeTx.executeWithoutResult(status -> work.run());
        artifacts.reconcile(inTransaction);
        if (Boolean.TRUE.equals(readTx.execute(status -> maintenance.occupied()))) {
            outputs.recover(clock.instant(), inTransaction);
        } else {
            outputs.reconcile(clock.instant(), inTransaction);
        }
    }
}
