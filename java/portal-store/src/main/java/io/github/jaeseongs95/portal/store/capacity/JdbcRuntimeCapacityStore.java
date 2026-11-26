package io.github.jaeseongs95.portal.store.capacity;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.data.relational.core.conversion.DbActionExecutionException;
import org.springframework.stereotype.Repository;

import io.github.jaeseongs95.portal.app.capacity.CapacityState;
import io.github.jaeseongs95.portal.app.capacity.RuntimeCapacityStore;
import io.github.jaeseongs95.portal.domain.error.CapacityErrorCode;
import io.github.jaeseongs95.portal.domain.error.RequestRefusedException;

/**
 * {@link RuntimeCapacityStore} over {@code runtime_capacity} (one row, Spring Data JDBC {@code @Version}) and {@code runtime_capacity_target} (SSH_PORTAL-D-0124).
 * Transaction boundaries belong to the calling service (Principles 4.4); this repository opens no transactions and uses no savepoints.
 * {@link #initialize} lets {@link org.springframework.dao.DuplicateKeyException} propagate so the caller rolls back the transaction and reads the winner in a fresh read-only transaction.
 */
@Repository
public class JdbcRuntimeCapacityStore implements RuntimeCapacityStore {

    private static final int THE_ROW = 1;
    private static final long FIRST_VERSION = 1L;

    private final RuntimeCapacityRowRepository capacities;
    private final RuntimeCapacityTargetRowRepository targets;
    private final JdbcAggregateOperations aggregates;
    private final CapacityMapper mapper;

    public JdbcRuntimeCapacityStore(RuntimeCapacityRowRepository capacities, RuntimeCapacityTargetRowRepository targets, JdbcAggregateOperations aggregates,
                                    CapacityMapper mapper) {
        this.capacities = capacities;
        this.targets = targets;
        this.aggregates = aggregates;
        this.mapper = mapper;
    }

    @Override
    public Optional<CapacityState> read() {
        return load();
    }

    @Override
    public CapacityState initialize(CapacityState initial) {
        requireDistinctTargets(initial);
        Optional<CapacityState> existing = load();
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        insertFirst(initial);
        return new CapacityState(FIRST_VERSION, initial.queueCapacity(), initial.maxConcurrency(), initial.targets());
    }

    @Override
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
