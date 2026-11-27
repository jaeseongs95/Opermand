package io.github.jaeseongs95.opermand.store.journal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.stereotype.Repository;

import io.github.jaeseongs95.opermand.app.journal.ClaimResult;
import io.github.jaeseongs95.opermand.app.journal.ClaimStore;
import io.github.jaeseongs95.opermand.domain.id.Digester;
import io.github.jaeseongs95.opermand.domain.id.OperationId;
import io.github.jaeseongs95.opermand.domain.id.TargetId;
import io.github.jaeseongs95.opermand.domain.operation.Operation;
import io.github.jaeseongs95.opermand.domain.operation.OperationState;
import io.github.jaeseongs95.opermand.domain.schedule.ConcurrencyLimits;
import io.github.jaeseongs95.opermand.domain.schedule.SaturationReason;

/**
 * {@link ClaimStore} with the limits kept by primary keys (design 13.2). Transaction boundaries belong to the calling service (Principles 4.4); this repository opens no
 * transactions and uses no savepoints.
 *
 * <p>On slot or fence contention ({@link org.springframework.dao.DuplicateKeyException}) or version conflict ({@code OPERATION_VERSION_CONFLICT}), the exception propagates
 * directly with zero follow-up SQL so the caller's transaction rolls back all inserted slot/fence rows cleanly (PostgreSQL-safe, no {@code 25P02}), and the caller translates
 * the rollback outside the transaction into {@link ClaimResult.Contended} or {@link ClaimResult.NotQueued}.
 */
@Repository
public class JdbcClaimStore implements ClaimStore {

    private static final String GLOBAL_SCOPE = "GLOBAL";
    private static final String TARGET_SCOPE_PREFIX = "TARGET:";

    private final OperationRowRepository operations;
    private final OperationSlotRowRepository slots;
    private final OperationStorage storage;
    private final JdbcAggregateOperations aggregates;
    private final Digester digester;

    public JdbcClaimStore(OperationRowRepository operations, OperationSlotRowRepository slots, OperationStorage storage, JdbcAggregateOperations aggregates,
                          Digester digester) {
        this.operations = operations;
        this.slots = slots;
        this.storage = storage;
        this.aggregates = aggregates;
        this.digester = digester;
    }

    @Override
    public ClaimResult claim(OperationId id, long expectedVersion, ConcurrencyLimits limits, Instant at) {
        Optional<OperationRow> found = operations.findById(id.value()).filter(stored -> stored.state() == OperationState.QUEUED && stored.version() == expectedVersion);
        if (found.isEmpty()) {
            return new ClaimResult.NotQueued();
        }
        OperationRow row = found.orElseThrow();
        TargetId target = new TargetId(row.targetId());
        String targetScope = TARGET_SCOPE_PREFIX + target.value();
        int globalSlot = freeSlot(GLOBAL_SCOPE, limits.globalConcurrency().value());
        if (globalSlot == 0) {
            return new ClaimResult.Saturated(SaturationReason.GLOBAL_CONCURRENCY);
        }
        int targetSlot = freeSlot(targetScope, limits.concurrencyOf(target).value());
        if (targetSlot == 0) {
            return new ClaimResult.Saturated(SaturationReason.TARGET_CONCURRENCY);
        }
        if (operations.countLiveUnknown() >= limits.unknownBudgetLimit().value()) {
            return new ClaimResult.Saturated(SaturationReason.UNKNOWN_BUDGET);
        }
        Operation queued = storage.load(row);
        // Guard against clock skew or an older pre-loop instant when the row's updatedAt is ahead of the caller's timestamp.
        Instant effective = at.isBefore(queued.updatedAt()) ? queued.updatedAt() : at;
        Operation claimed = queued.claim(effective);
        aggregates.insert(new OperationSlotRow(GLOBAL_SCOPE, globalSlot, row.id()));
        aggregates.insert(new OperationSlotRow(targetScope, targetSlot, row.id()));
        if (row.conflictKey() != null) {
            aggregates.insert(new OperationFenceRow(row.targetId(), row.targetRevision(), digester.digestUtf8(row.conflictKey()).hex(), row.id()));
        }
        storage.persist(row, claimed, expectedVersion);
        return new ClaimResult.Claimed(claimed);
    }

    /** The smallest slot number from 1 to {@code limit} that is not taken in the scope, or 0 when every slot of the limit is taken. */
    private int freeSlot(String scope, int limit) {
        List<OperationSlotRow> taken = slots.findByScopeOrderBySlotNoAsc(scope);
        int candidate = 1;
        for (OperationSlotRow row : taken) {
            if (row.slotNo() == candidate) {
                candidate++;
            } else if (row.slotNo() > candidate) {
                break;
            }
        }
        return candidate <= limit ? candidate : 0;
    }
}
