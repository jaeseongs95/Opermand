package io.github.jaeseongs95.portal.app.journal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import io.github.jaeseongs95.portal.domain.id.OperationId;
import io.github.jaeseongs95.portal.domain.id.RequestKey;
import io.github.jaeseongs95.portal.domain.operation.Admission;
import io.github.jaeseongs95.portal.domain.operation.NewOperation;
import io.github.jaeseongs95.portal.domain.operation.Operation;
import io.github.jaeseongs95.portal.domain.operation.OperationEvent;

/**
 * The durable journal of every operation: intake and transitions (2.x {@code operation-store.mjs}). Consumers are the use cases; the implementation is a
 * storage adapter. The journal never decides whether a transition is allowed: {@link Operation} does that, the journal only stores the result atomically.
 */
public interface OperationJournal {

    /**
     * Stores a new operation in the state the admission decided (queued with its place in the ready order, denied, or pending), with its decision and the event of its first
     * version, in one transaction. The request key is unique: the same key with the same contract returns {@link JournalInsert.Existing}, the same key with another contract
     * throws {@code RequestRefusedException(OPERATION_KEY_CONFLICT)}. The implementation makes the id and the time (an {@code OperationIdGenerator} and the one
     * {@code Clock}) and writes the first event in the same transaction.
     */
    JournalInsert accept(NewOperation spec, Admission admission);

    /**
     * The same, for a caller that made the id and the time itself, because it has to write the audit of the new operation <em>before</em> the row exists (an operation that
     * can be dispatched is never stored without its audit). A key that is already stored answers {@link JournalInsert.Existing} before anything else, so {@code beforeInsert}
     * runs only for a new operation, inside the transaction of the insert and before it: the check that has to hold at the moment of the insert (the room in the queue) goes
     * there, and what it throws stops the insert. Two operations queued in the same instant meet at the ready position; the one that loses throws
     * {@code RequestRefusedException(OPERATION_VERSION_CONFLICT)}, as a transition into queued does, and the caller decides again.
     */
    JournalInsert accept(NewOperation spec, Admission admission, OperationId id, Instant at, Runnable beforeInsert);

    /** {@code Optional} here has the one meaning "no such operation". */
    Optional<Operation> find(OperationId id);

    Optional<Operation> findByRequestKey(RequestKey key);

    /**
     * Compare-and-set: stores {@code next} when the stored version is {@code expectedVersion}, writes the event of the new state and releases or takes the concurrency
     * slots that the new state implies, all in one transaction. Another writer's change throws {@code RequestRefusedException(OPERATION_VERSION_CONFLICT)}; the
     * caller reads again and decides again.
     */
    Operation save(Operation next, long expectedVersion);

    /**
     * The same, with a check that runs inside the transaction before the new version is written: the room in the queue for a transition into queued. For such a transition the
     * place in the ready order is read before the check, so the queue cannot exceed its room however the writers interleave; the one that loses the place throws
     * {@code RequestRefusedException(OPERATION_VERSION_CONFLICT)} that is retryable and not attempted (the row is as it was).
     */
    Operation save(Operation next, long expectedVersion, Runnable beforeWrite);

    /** The transitions of one operation in version order. */
    List<OperationEvent> events(OperationId id);
}
