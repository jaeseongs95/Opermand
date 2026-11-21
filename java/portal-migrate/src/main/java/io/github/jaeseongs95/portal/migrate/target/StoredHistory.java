package io.github.jaeseongs95.portal.migrate.target;

import java.util.Optional;

import io.github.jaeseongs95.portal.domain.id.OperationId;
import io.github.jaeseongs95.portal.domain.id.RequestKey;
import io.github.jaeseongs95.portal.domain.operation.Operation;
import io.github.jaeseongs95.portal.domain.operation.OperationState;
import io.github.jaeseongs95.portal.store.journal.JdbcOperationJournal;
import io.github.jaeseongs95.portal.store.journal.JdbcOperationQueueView;

/** The history of an existing 3.0 store, read through the store's own journal and queue view. */
final class StoredHistory implements ExistingHistory {

    private final JdbcOperationJournal journal;
    private final JdbcOperationQueueView queue;

    StoredHistory(JdbcOperationJournal journal, JdbcOperationQueueView queue) {
        this.journal = journal;
        this.queue = queue;
    }

    @Override
    public Optional<Operation> byRequestKey(RequestKey key) {
        return journal.findByRequestKey(key);
    }

    @Override
    public Optional<Operation> byId(OperationId id) {
        return journal.find(id);
    }

    @Override
    public int unknownOperations() {
        return queue.counts().counts().getOrDefault(OperationState.UNKNOWN, 0);
    }
}
