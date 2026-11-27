package io.github.jaeseongs95.opermand.app.capacity;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jaeseongs95.opermand.app.config.AvailableCpus;
import io.github.jaeseongs95.opermand.app.maintenance.MaintenanceStore;
import io.github.jaeseongs95.opermand.domain.capacity.CapacityDefaults;
import io.github.jaeseongs95.opermand.domain.error.CapacityErrorCode;
import io.github.jaeseongs95.opermand.domain.error.RequestErrorCode;
import io.github.jaeseongs95.opermand.domain.error.RequestRefusedException;
import io.github.jaeseongs95.opermand.domain.id.TargetId;
import io.github.jaeseongs95.opermand.domain.target.Target;

/**
 * The administrator's override of the queue and concurrency limits (cases RC-B-410 and its neighbours). A change is one optimistic-locking update on the single monotonic
 * {@code version} the administrator saw (SSH_PORTAL-D-0124). It is refused while a maintenance reservation holds the installation ({@code MAINTENANCE_ACTIVE}), for a stale
 * version ({@code CAPACITY_REVISION_CONFLICT}), or for a duplicate/unknown target, and no refusal changes the stored value.
 */
@Service
public class CapacityService {

    private static final Logger LOG = LoggerFactory.getLogger(CapacityService.class);
    private static final long INITIAL_VERSION = 1L;

    private final RuntimeCapacityStore store;
    private final MaintenanceStore maintenance;
    private final ConfiguredTargets targets;
    private final AvailableCpus cpus;
    private final TransactionOperations readTx;
    private final TransactionOperations writeTx;

    @Autowired
    public CapacityService(RuntimeCapacityStore store, MaintenanceStore maintenance, ConfiguredTargets targets, AvailableCpus cpus,
                           PlatformTransactionManager transactionManager) {
        this(store, maintenance, targets, cpus, repeatableReadOnly(transactionManager), new TransactionTemplate(transactionManager));
    }

    public CapacityService(RuntimeCapacityStore store, MaintenanceStore maintenance, ConfiguredTargets targets, AvailableCpus cpus) {
        this(store, maintenance, targets, cpus, TransactionOperations.withoutTransaction(), TransactionOperations.withoutTransaction());
    }

    private CapacityService(RuntimeCapacityStore store, MaintenanceStore maintenance, ConfiguredTargets targets, AvailableCpus cpus,
                            TransactionOperations readTx, TransactionOperations writeTx) {
        this.store = store;
        this.maintenance = maintenance;
        this.targets = targets;
        this.cpus = cpus;
        this.readTx = readTx;
        this.writeTx = writeTx;
    }

    private static TransactionOperations repeatableReadOnly(PlatformTransactionManager transactionManager) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx;
    }

    /** The stored limits; the first call of a new installation stores the defaults (two first writers: the loser rolls back and reads the winner in a fresh read-only TX). */
    public CapacityState current() {
        CapacityState existing = readTx.execute(status -> store.read().orElse(null));
        if (existing != null) {
            return existing;
        }
        CapacityState initial = defaults();
        try {
            return Objects.requireNonNull(writeTx.execute(status -> store.initialize(initial)), "capacity");
        } catch (DuplicateKeyException e) {
            LOG.info("Another writer initialized the runtime capacity first; reading the stored state in a fresh transaction", e);
            CapacityState winner = readTx.execute(status -> store.read().orElse(null));
            if (winner == null) {
                throw new IllegalStateException("The runtime capacity row vanished after a duplicate insert", e);
            }
            return winner;
        }
    }

    /**
     * Replaces the limits with {@code proposed}, under the version the administrator saw. The version of {@code proposed} is ignored: the store advances the version by 1.
     * Runs inside one transaction: maintenance, version, and target validation are checked against read-only state before any write or default initialization, so a refusal
     * never mutates or initializes the database (SSH_PORTAL-D-0124).
     */
    @Transactional
    public CapacityState update(long expectedVersion, CapacityState proposed) {
        Objects.requireNonNull(proposed, "proposed");
        if (maintenance.occupied()) {
            throw new RequestRefusedException(RequestErrorCode.MAINTENANCE_ACTIVE);
        }
        Optional<CapacityState> existing = store.read();
        CapacityState current = existing.orElseGet(this::defaults);
        if (current.version() != expectedVersion) {
            throw new RequestRefusedException(CapacityErrorCode.CAPACITY_REVISION_CONFLICT);
        }
        requireKnownTargets(current, proposed.targets());
        if (existing.isEmpty()) {
            try {
                store.initialize(current);
            } catch (DuplicateKeyException e) {
                throw new RequestRefusedException(CapacityErrorCode.CAPACITY_REVISION_CONFLICT, e);
            }
        }
        return store.compareAndSet(expectedVersion, proposed);
    }

    private void requireKnownTargets(CapacityState stored, List<CapacityState.TargetCapacity> proposed) {
        Set<TargetId> seen = new HashSet<>();
        Set<TargetId> known = new HashSet<>();
        stored.targets().forEach(target -> known.add(target.targetId()));
        targets.entries().forEach(target -> known.add(target.id()));
        for (CapacityState.TargetCapacity override : proposed) {
            if (!seen.add(override.targetId())) {
                throw new RequestRefusedException(CapacityErrorCode.INVALID_CAPACITY_TARGETS);
            }
            if (!known.contains(override.targetId())) {
                throw new RequestRefusedException(CapacityErrorCode.UNKNOWN_CAPACITY_TARGET);
            }
        }
    }

    private CapacityState defaults() {
        CapacityDefaults installation = new CapacityDefaults(cpus.count());
        List<CapacityState.TargetCapacity> own = targets.entries().stream().map(this::overrideOf).toList();
        return new CapacityState(INITIAL_VERSION, installation.queueCapacity(), installation.maxConcurrency(), own);
    }

    private CapacityState.TargetCapacity overrideOf(Target target) {
        return new CapacityState.TargetCapacity(target.id(), target.queueCapacity(), target.maxConcurrency());
    }
}
