package io.github.jaeseongs95.portal.migrate.target;

import java.util.Optional;

import io.github.jaeseongs95.portal.domain.id.OperationId;
import io.github.jaeseongs95.portal.domain.id.RequestKey;
import io.github.jaeseongs95.portal.domain.operation.Operation;

/** What the 3.0 store already holds, read only: an operation by request key or id, and how many operations are in the unknown state. */
public interface ExistingHistory {

    /** {@code Optional} has the one meaning "no such operation". */
    Optional<Operation> byRequestKey(RequestKey key);

    Optional<Operation> byId(OperationId id);

    /** The operations in the unknown state: the count the 3.0 scheduler compares with its unknown budget before it starts a new operation. */
    int unknownOperations();
}
