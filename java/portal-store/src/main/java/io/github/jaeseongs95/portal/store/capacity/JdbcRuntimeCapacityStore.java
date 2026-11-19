package io.github.jaeseongs95.portal.store.capacity;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.data.relational.core.conversion.DbActionExecutionException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jaeseongs95.portal.app.capacity.CapacityState;
import io.github.jaeseongs95.portal.app.capacity.RuntimeCapacityStore;
import io.github.jaeseongs95.portal.domain.error.CapacityErrorCode;
import io.github.jaeseongs95.portal.domain.error.RequestRefusedException;
import io.github.jaeseongs95.portal.store.Savepoints;

/**
 * {@link RuntimeCapacityStore} over {@code runtime_capacity} (one row, Spring Data JDBC {@code @Version}) and {@code runtime_capacity_target} (SSH_PORTAL-D-0124).
 * {@link #compareAndSet} delegates optimistic locking to Spring Data JDBC {@code @Version}: the winner increments {@code version} and replaces override rows in one transaction,
 * while a stale writer receives {@link OptimisticLockingFailureException} translated to {@code CAPACITY_REVISION_CONFLICT}.
 */
@Repository
public class JdbcRuntimeCapacityStore implements RuntimeCapacityStore {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcRuntimeCapacityStore.class);
    private static final int THE_ROW = 1;
    private static final long FIRST_VERSION = 1L;

    private final RuntimeCapacityRowRepository capacities;
    private final RuntimeCapacityTargetRowRepository targets;
    private final JdbcAggregateOperations aggregates;
    private final CapacityMapper mapper;
    private final TransactionTemplate transactions;
    private final Savepoints savepoints;

    public JdbcRuntimeCapacityStore(RuntimeCapacityRowRepository capacities, RuntimeCapacityTargetRowRepository targets, JdbcAggregateOperations aggregates,
                                    CapacityMapper mapper, PlatformTransactionManager transactionManager, Savepoints savepoints) {
        this.capacities = capacities;
        this.targets = targets;
        this.aggregates = aggregates;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.savepoints = savepoints;
    }

    /** The global row and the override rows are read in one snapshot so that a write that commits in between cannot mix two states. */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<CapacityState> read() {
        return load();
    }

    @Override
    public CapacityState initialize(CapacityState initial) {
        requireDistinctTargets(initial);
        Optional<CapacityState> existing = transactions.execute(status -> load());
        if (existing != null && existing.isPresent()) {
            return existing.orElseThrow();
        }
        try {
            savepoints.run(() -> insertFirst(initial));
            return new CapacityState(FIRST_VERSION, initial.queueCapacity(), initial.maxConcurrency(), initial.targets());
        } catch (DuplicateKeyException e) {
            LOG.info("Another writer initialized the runtime capacity first; the stored state is used", e);
            Optional<CapacityState> winner = transactions.execute(status -> load());
            return winner == null ? initial : winner.orElseThrow(() -> new IllegalStateException("The runtime capacity row vanished after a duplicate insert", e));
        }
    }

    @Override
    @Transactional
    public CapacityState compareAndSet(long expectedVersion, CapacityState next) {
        requireDistinctTargets(next);
        if (expectedVersion < FIRST_VERSION) {
            throw new RequestRefusedException(CapacityErrorCode.CAPACITY_REVISION_CONFLICT);
        }
        RuntimeCapacityRow saved;
        try {
            saved = capacities.save(mapper.row(next, expectedVersion));
        } catch (OptimisticLockingFailureException e) {
            throw new RequestRefusedException(CapacityErrorCode.CAPACITY_REVISION_CONFLICT, e);
        } catch (DbActionExecutionException e) {
            if (e.getCause() instanceof OptimisticLockingFailureException) {
                throw new RequestRefusedException(CapacityErrorCode.CAPACITY_REVISION_CONFLICT, e);
            }
            throw e;
        }
        targets.deleteAllTargets();
        for (RuntimeCapacityTargetRow row : mapper.targetRows(next)) {
            aggregates.insert(row);
        }
        return new CapacityState(saved.version(), next.queueCapacity(), next.maxConcurrency(), next.targets());
    }

    private Optional<CapacityState> load() {
        return capacities.findById(THE_ROW).map(row -> mapper.state(row, targets.findAllOrdered()));
    }

    private void insertFirst(CapacityState initial) {
        aggregates.insert(mapper.row(initial, FIRST_VERSION));
        for (RuntimeCapacityTargetRow row : mapper.targetRows(initial)) {
            aggregates.insert(row);
        }
    }

    private void requireDistinctTargets(CapacityState state) {
        Set<String> seen = new HashSet<>();
        for (CapacityState.TargetCapacity target : state.targets()) {
            if (!seen.add(target.targetId().value())) {
                throw new RequestRefusedException(CapacityErrorCode.INVALID_CAPACITY_TARGETS);
            }
        }
    }
}
