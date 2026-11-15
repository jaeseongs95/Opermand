package io.github.jaeseongs95.portal.store.journal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import io.github.jaeseongs95.portal.app.journal.JournalInsert;
import io.github.jaeseongs95.portal.app.journal.OperationJournal;
import io.github.jaeseongs95.portal.domain.error.ExecutionAttempt;
import io.github.jaeseongs95.portal.domain.error.OperationErrorCode;
import io.github.jaeseongs95.portal.domain.error.RequestErrorCode;
import io.github.jaeseongs95.portal.domain.error.RequestRefusedException;
import io.github.jaeseongs95.portal.domain.id.OperationId;
import io.github.jaeseongs95.portal.domain.id.OperationIdGenerator;
import io.github.jaeseongs95.portal.domain.id.RequestKey;
import io.github.jaeseongs95.portal.domain.operation.Admission;
import io.github.jaeseongs95.portal.domain.operation.NewOperation;
import io.github.jaeseongs95.portal.domain.operation.Operation;
import io.github.jaeseongs95.portal.domain.operation.OperationEvent;
import io.github.jaeseongs95.portal.domain.operation.OperationState;
import io.github.jaeseongs95.portal.store.Savepoints;

/**
 * {@link OperationJournal} over the journal tables (design 8.2 V1, 2.x {@code operation-store.mjs}). The id and the time of a new operation are made here from the
 * {@link OperationIdGenerator} and the one {@link Clock}; a transition is stored by {@link OperationStorage#persist}, a compare-and-set on the version.
 *
 * <p>A request key is unique. The first intake wins; a second intake of the same key with the same contract digest returns the stored operation, with another digest it
 * is {@code OPERATION_KEY_CONFLICT}. Two transactions that accept the same key at the same moment meet at the unique key; the loser's insert runs in a nested
 * transaction (a savepoint), so that on PostgreSQL its failure does not abort the surrounding transaction, and then it reads the winner's row.
 */
@Repository
public class JdbcOperationJournal implements OperationJournal {

    private final OperationRowRepository operations;
    private final OperationEventRowRepository events;
    private final OperationStorage storage;
    private final OperationIdGenerator ids;
    private final Clock clock;
    private final Savepoints savepoints;

    @Autowired
    public JdbcOperationJournal(OperationRowRepository operations, OperationEventRowRepository events, OperationStorage storage,
                                OperationIdGenerator ids, Clock clock, Savepoints savepoints) {
        this.operations = operations;
        this.events = events;
        this.storage = storage;
        this.ids = ids;
        this.clock = clock;
        this.savepoints = savepoints;
    }

    @Override
    @Transactional
    public JournalInsert accept(NewOperation spec, Admission admission) {
        return accept(spec, admission, ids.next(), clock.instant(), () -> { });
    }

    @Override
    @Transactional
    public JournalInsert accept(NewOperation spec, Admission admission, OperationId id, Instant at, Runnable beforeInsert) {
        Optional<OperationRow> known = operations.findByRequestKey(spec.requestKey().value());
        if (known.isPresent()) {
            return answerFor(known.orElseThrow(), spec);
        }
        // The place in the ready order is read before the check runs. A check that counts the queue (the room in it) therefore sees every insert whose place this
        // transaction can see; an insert that commits after the place was read takes the same place, and the unique position makes one of the two lose (READ_COMMITTED is
        // enough: the loser is told OPERATION_VERSION_CONFLICT below and decides again). Reading it after the check left a window in which the queue could exceed its room.
        Long place = admission instanceof Admission.Queue ? operations.maxReadySequence() + 1 : null;
        beforeInsert.run();
        Operation created = Operation.admit(spec, id, at, admission);
        try {
            savepoints.run(() -> storage.insertNew(created, place));
            return new JournalInsert.Inserted(created);
        } catch (DuplicateKeyException e) {
            // Either another intake stored the same key first (this one answers with that operation), or two operations were queued in the same instant and took the same
            // ready position: this one is told to read again and decide again, as a transition into QUEUED is (save).
            Optional<OperationRow> winner = operations.findByRequestKey(spec.requestKey().value());
            if (winner.isEmpty()) {
                // Nothing was stored and nothing was attempted: the same key may be sent again (it is idempotent), so this answer says so. The default of the code says
                // retryable false and attempt unknown, and a client that does not resend what it cannot place would lose the request.
                throw new RequestRefusedException(OperationErrorCode.OPERATION_VERSION_CONFLICT, true, ExecutionAttempt.NOT_ATTEMPTED, e);
            }
            return answerFor(winner.orElseThrow(), spec);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Operation> find(OperationId id) {
        return operations.findById(id.value()).map(storage::load);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Operation> findByRequestKey(RequestKey key) {
        return operations.findByRequestKey(key.value()).map(storage::load);
    }

    @Override
    @Transactional
    public Operation save(Operation next, long expectedVersion) {
        return save(next, expectedVersion, () -> { });
    }

    @Override
    @Transactional
    public Operation save(Operation next, long expectedVersion, Runnable beforeWrite) {
        OperationRow before = operations.findById(next.id().value()).orElseThrow(() -> new RequestRefusedException(RequestErrorCode.OPERATION_NOT_FOUND));
        if (before.version() != expectedVersion) {
            throw new RequestRefusedException(OperationErrorCode.OPERATION_VERSION_CONFLICT);
        }
        if (next.state() == OperationState.STARTING && before.state() != OperationState.STARTING) {
            // The limits are kept by the claim; a transition into STARTING that did not pass it would take a slot nobody counted.
            throw new RequestRefusedException(OperationErrorCode.INVALID_OPERATION_TRANSITION);
        }
        // The place in the ready order is read before the check of the room runs, as for a new operation (see accept): the one that loses the place is told below.
        Long place = next.state() == OperationState.QUEUED ? operations.maxReadySequence() + 1 : null;
        beforeWrite.run();
        try {
            return storage.persist(before, next, expectedVersion, place);
        } catch (DuplicateKeyException e) {
            // Two operations were queued in the same instant and took the same ready position; the row is as it was and nothing was attempted, so this one is told to read
            // again and decide again (a retry is safe).
            throw new RequestRefusedException(OperationErrorCode.OPERATION_VERSION_CONFLICT, true, ExecutionAttempt.NOT_ATTEMPTED, e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<OperationEvent> events(OperationId id) {
        return events.findByOperationIdOrderByVersionAsc(id.value()).stream().map(row -> new OperationEvent(row.version(), row.state(), row.occurredAt())).toList();
    }

    private JournalInsert answerFor(OperationRow row, NewOperation spec) {
        Operation stored = storage.load(row);
        if (!stored.hasContract(spec.contractDigest())) {
            throw new RequestRefusedException(RequestErrorCode.OPERATION_KEY_CONFLICT);
        }
        return new JournalInsert.Existing(stored);
    }
}
