package io.github.jaeseongs95.portal.store.journal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import io.github.jaeseongs95.portal.app.attachment.PreparedOperationAttachments;
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

/**
 * {@link OperationJournal} over the journal tables (design 8.2 V1, 2.x {@code operation-store.mjs}). Transaction boundaries belong to the calling service
 * (Principles 4.4); this repository opens no transactions and uses no savepoints.
 *
 * <p>A request key is unique. On a concurrent insert race on {@code request_key} or {@code ready_sequence}, {@link DuplicateKeyException} propagates out of
 * {@link #accept} with zero follow-up SQL so the caller's transaction rolls back cleanly (PostgreSQL-safe, no {@code 25P02}), and the caller reads the winner in a fresh
 * read-only transaction.
 */
@Repository
public class JdbcOperationJournal implements OperationJournal {

    private final OperationRowRepository operations;
    private final OperationEventRowRepository events;
    private final OperationStorage storage;
    private final OperationIdGenerator ids;
    private final Clock clock;

    @Autowired
    public JdbcOperationJournal(OperationRowRepository operations, OperationEventRowRepository events, OperationStorage storage,
                                OperationIdGenerator ids, Clock clock) {
        this.operations = operations;
        this.events = events;
        this.storage = storage;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public JournalInsert accept(NewOperation spec, Admission admission) {
        return accept(spec, admission, ids.next(), clock.instant(), null, () -> { });
    }

    @Override
    public JournalInsert accept(NewOperation spec, Admission admission, OperationId id, Instant at, Runnable beforeInsert) {
        return accept(spec, admission, id, at, null, beforeInsert);
    }

    @Override
    public JournalInsert accept(NewOperation spec, Admission admission, OperationId id, Instant at, PreparedOperationAttachments prepared, Runnable beforeInsert) {
        Optional<OperationRow> known = operations.findByRequestKey(spec.requestKey().value());
        if (known.isPresent()) {
            return answerFor(known.orElseThrow(), spec);
        }
        Long place = admission instanceof Admission.Queue ? operations.maxReadySequence() + 1 : null;
        beforeInsert.run();
        Operation created = Operation.admit(spec, id, at, admission);
        storage.insertNew(created, place, prepared);
        return new JournalInsert.Inserted(created);
    }

    @Override
    public Optional<Operation> find(OperationId id) {
        return fetch(id).get();
    }

    @Override
    public Supplier<Optional<Operation>> fetch(OperationId id) {
        Optional<OperationStorage.LoadedOperation> raw = operations.findById(id.value()).map(storage::fetchRaw);
        return () -> raw.map(storage::hydrate);
    }

    @Override
    public Optional<Operation> findByRequestKey(RequestKey key) {
        return fetchByRequestKey(key).get();
    }

    @Override
    public Supplier<Optional<Operation>> fetchByRequestKey(RequestKey key) {
        Optional<OperationStorage.LoadedOperation> raw = operations.findByRequestKey(key.value()).map(storage::fetchRaw);
        return () -> raw.map(storage::hydrate);
    }

    @Override
    public Operation save(Operation next, long expectedVersion) {
        return save(next, expectedVersion, null, () -> { });
    }

    @Override
    public Operation save(Operation next, long expectedVersion, Runnable beforeWrite) {
        return save(next, expectedVersion, null, beforeWrite);
    }

    @Override
    public Operation save(Operation next, long expectedVersion, PreparedOperationAttachments prepared, Runnable beforeWrite) {
        OperationRow before = operations.findById(next.id().value()).orElseThrow(() -> new RequestRefusedException(RequestErrorCode.OPERATION_NOT_FOUND));
        if (before.version() != expectedVersion) {
            throw new RequestRefusedException(OperationErrorCode.OPERATION_VERSION_CONFLICT);
        }
        if (next.state() == OperationState.STARTING && before.state() != OperationState.STARTING) {
            throw new RequestRefusedException(OperationErrorCode.INVALID_OPERATION_TRANSITION);
        }
        Long place = next.state() == OperationState.QUEUED ? operations.maxReadySequence() + 1 : null;
        beforeWrite.run();
        try {
            return storage.persist(before, next, expectedVersion, place, prepared);
        } catch (DuplicateKeyException e) {
            if (place == null) {
                throw new RequestRefusedException(OperationErrorCode.OPERATION_VERSION_CONFLICT, e);
            }
            throw new RequestRefusedException(OperationErrorCode.OPERATION_VERSION_CONFLICT, true, ExecutionAttempt.NOT_ATTEMPTED, e);
        }
    }

    @Override
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
