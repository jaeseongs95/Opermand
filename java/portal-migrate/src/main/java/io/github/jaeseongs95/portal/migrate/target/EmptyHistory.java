package io.github.jaeseongs95.portal.migrate.target;

import java.util.Optional;

import io.github.jaeseongs95.portal.domain.id.OperationId;
import io.github.jaeseongs95.portal.domain.id.RequestKey;
import io.github.jaeseongs95.portal.domain.operation.Operation;

/** The history of a 3.0 store that does not exist yet: nothing. A dry run against a data folder without a store uses it, so that the dry run does not create the store. */
public final class EmptyHistory implements ExistingHistory {

    @Override
    public Optional<Operation> byRequestKey(RequestKey key) {
        return Optional.empty();
    }

    @Override
    public Optional<Operation> byId(OperationId id) {
        return Optional.empty();
    }

    @Override
    public int unknownOperations() {
        return 0;
    }
}
